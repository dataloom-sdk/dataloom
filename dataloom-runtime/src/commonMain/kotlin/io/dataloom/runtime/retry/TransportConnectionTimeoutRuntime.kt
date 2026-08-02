package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Applies the independent CONNECTION timeout to one explicit application-owned
 * transport connection operation.
 *
 * This boundary deliberately does not decorate `TransportProvider.initialize()`:
 * provider initialization and remote connection establishment are separate
 * lifecycle concepts. Protocol adapters invoke [execute] only around the exact
 * suspending operation that establishes or acquires a remote connection.
 *
 * Completed canonical results are preserved exactly. Caller cancellation and
 * unexpected exceptions propagate. A connection timeout is recoverable because
 * no synchronization request/response exchange is owned by this boundary.
 */
public class TransportConnectionTimeoutBoundary(
    private val timeoutCoordinator: RetryTimeoutCoordinator,
) {
    /**
     * Executes one connection operation under the configured connection limit.
     *
     * [workflowStartedAt] is optional persisted evidence for a complete workflow
     * deadline. When supplied and the remaining workflow window is shorter, the
     * workflow deadline wins without being relabeled as a connection timeout.
     */
    public suspend fun <T> execute(
        workflowStartedAt: DataLoomInstant? = null,
        operation: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = when (
        val result = timeoutCoordinator.execute(
            kind = RetryTimeoutKind.CONNECTION,
            workflowStartedAt = workflowStartedAt,
            operation = operation,
        )
    ) {
        is RetryTimeoutExecutionResult.Completed -> result.value
        is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
            if (result.kind == RetryTimeoutKind.WORKFLOW) {
                TransportConnectionTimeoutErrors.workflowDeadlineExceeded()
            } else {
                TransportConnectionTimeoutErrors.connectionTimedOut()
            },
        )
        is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded -> ProviderOperationResult.Failure(
            TransportConnectionTimeoutErrors.workflowDeadlineExceeded(),
        )
        is RetryTimeoutExecutionResult.ClockRegression -> ProviderOperationResult.Failure(
            TransportConnectionTimeoutErrors.clockRegression(),
        )
    }
}

/** Production assembly for an explicit transport connection timeout boundary. */
public object TransportConnectionTimeoutRuntime {
    /**
     * Creates one cooperative connection boundary.
     *
     * Construction performs no clock read, timeout execution, I/O, identifier
     * generation, provider operation, or coroutine launch.
     */
    public fun create(
        clock: DataLoomClock,
        connectionTimeout: SchedulingDelay,
        workflowTimeout: SchedulingDelay? = null,
    ): TransportConnectionTimeoutBoundary = TransportConnectionTimeoutBoundary(
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                connectionTimeout = connectionTimeout,
                workflowTimeout = workflowTimeout,
            ),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )
}

private object TransportConnectionTimeoutErrors {
    fun connectionTimedOut(): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_CONNECTION_TIMEOUT"),
        category = ErrorCategory.NETWORK,
        recoverability = Recoverability.RECOVERABLE,
        message = "The transport connection attempt exceeded its configured timeout.",
    )

    fun workflowDeadlineExceeded(): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.NETWORK,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before the transport connection operation completed.",
    )

    fun clockRegression(): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_CONNECTION_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented transport connection timeout enforcement.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
