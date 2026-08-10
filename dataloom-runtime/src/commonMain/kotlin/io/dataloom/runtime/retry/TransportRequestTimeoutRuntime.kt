package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider

/**
 * Transport decorator applying the independent REQUEST timeout only to push and
 * pull operations. Lifecycle calls remain unchanged and connection/idle
 * boundaries are not inferred.
 */
public class RequestTimeoutEnforcingTransportProvider(
    private val delegate: TransportProvider,
    private val timeoutCoordinator: RetryTimeoutCoordinator,
) : TransportProvider {
    override val descriptor: ProviderDescriptor
        get() = delegate.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = delegate.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> = delegate.health()

    override suspend fun close(): ProviderOperationResult<Unit> = delegate.close()

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> = execute("push-changes") {
        delegate.pushChanges(request)
    }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> = execute("pull-changes") {
        delegate.pullChanges(request)
    }

    private suspend fun <T> execute(
        operation: String,
        block: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = when (
        val result = timeoutCoordinator.execute(
            kind = RetryTimeoutKind.REQUEST,
            operation = block,
        )
    ) {
        is RetryTimeoutExecutionResult.Completed -> result.value
        is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
            TransportRequestTimeoutErrors.requestTimedOut(operation),
        )
        is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded -> ProviderOperationResult.Failure(
            TransportRequestTimeoutErrors.workflowDeadlineExceeded(operation),
        )
        is RetryTimeoutExecutionResult.ClockRegression -> ProviderOperationResult.Failure(
            TransportRequestTimeoutErrors.clockRegression(operation),
        )
    }
}

/** Production assembly for independent transport request timeout enforcement. */
public object TransportRequestTimeoutRuntime {
    /**
     * Wraps [transportProvider] with one cooperative request timeout for push
     * and pull. Construction performs no provider call, clock read, timeout
     * execution, I/O, identifier generation, or coroutine launch.
     */
    public fun create(
        transportProvider: TransportProvider,
        clock: DataLoomClock,
        requestTimeout: SchedulingDelay,
    ): TransportProvider = RequestTimeoutEnforcingTransportProvider(
        delegate = transportProvider,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                requestTimeout = requestTimeout,
            ),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )
}

private object TransportRequestTimeoutErrors {
    fun requestTimedOut(operation: String): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_REQUEST_TIMEOUT"),
        category = ErrorCategory.NETWORK,
        recoverability = Recoverability.UNKNOWN,
        message = "The transport $operation request exceeded its configured timeout; completion is not confirmed.",
    )

    fun workflowDeadlineExceeded(operation: String): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.NETWORK,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before transport $operation completed.",
    )

    fun clockRegression(operation: String): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_REQUEST_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented transport $operation request timeout enforcement.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
