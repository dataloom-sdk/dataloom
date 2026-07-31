package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider

/**
 * [TransportProvider] decorator that enforces one explicit provider-timeout
 * boundary around lifecycle, push, and pull operations.
 *
 * Completed provider results and the delegate descriptor are preserved exactly.
 * Caller cancellation and unexpected programming exceptions propagate.
 *
 * A timed-out remote request may have been processed before the response was
 * lost or cooperative cancellation was observed. Push and pull timeouts
 * therefore use [Recoverability.UNKNOWN] and are never presented as
 * automatically retryable without an explicit idempotency or reconciliation
 * decision. The read-only health timeout remains [Recoverability.RECOVERABLE].
 */
public class TimeoutEnforcingTransportProvider(
    private val delegate: TransportProvider,
    private val timeoutCoordinator: RetryTimeoutCoordinator,
) : TransportProvider {

    override val descriptor: ProviderDescriptor
        get() = delegate.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = execute(TransportCircuitOperation.INITIALIZE) {
        delegate.initialize(context)
    }

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        execute(TransportCircuitOperation.HEALTH) {
            delegate.health()
        }

    override suspend fun close(): ProviderOperationResult<Unit> =
        execute(TransportCircuitOperation.CLOSE) {
            delegate.close()
        }

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> =
        execute(TransportCircuitOperation.PUSH_CHANGES) {
            delegate.pushChanges(request)
        }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        execute(TransportCircuitOperation.PULL_CHANGES) {
            delegate.pullChanges(request)
        }

    private suspend fun <T> execute(
        operation: TransportCircuitOperation,
        block: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = when (
        val result = timeoutCoordinator.execute(
            kind = RetryTimeoutKind.PROVIDER,
            operation = block,
        )
    ) {
        is RetryTimeoutExecutionResult.Completed -> result.value
        is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
            TransportTimeoutErrors.providerTimedOut(operation),
        )
        is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded -> ProviderOperationResult.Failure(
            TransportTimeoutErrors.workflowDeadlineExceeded(operation),
        )
        is RetryTimeoutExecutionResult.ClockRegression -> ProviderOperationResult.Failure(
            TransportTimeoutErrors.clockRegression(operation),
        )
    }
}

internal object TransportTimeoutErrors {
    internal const val PROVIDER_TIMEOUT_CODE: String = "TRANSPORT_PROVIDER_TIMEOUT"

    fun providerTimedOut(operation: TransportCircuitOperation): DataLoomError = Error(
        code = ErrorCode(PROVIDER_TIMEOUT_CODE),
        category = categoryFor(operation),
        severity = ErrorSeverity.ERROR,
        recoverability = if (operation == TransportCircuitOperation.HEALTH) {
            Recoverability.RECOVERABLE
        } else {
            Recoverability.UNKNOWN
        },
        message = when (operation) {
            TransportCircuitOperation.HEALTH ->
                "The transport provider health operation exceeded its configured timeout."
            TransportCircuitOperation.PUSH_CHANGES,
            TransportCircuitOperation.PULL_CHANGES,
            -> "The transport provider ${operation.retryOperation.value} operation exceeded its " +
                "configured timeout; remote completion is not confirmed."
            TransportCircuitOperation.INITIALIZE,
            TransportCircuitOperation.CLOSE,
            -> "The transport provider ${operation.retryOperation.value} operation exceeded its " +
                "configured timeout; completion is not confirmed."
        },
    )

    fun workflowDeadlineExceeded(operation: TransportCircuitOperation): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED"),
        category = categoryFor(operation),
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before transport provider " +
            "${operation.retryOperation.value} completed.",
    )

    fun clockRegression(operation: TransportCircuitOperation): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented transport provider " +
            "${operation.retryOperation.value} timeout enforcement.",
    )

    private fun categoryFor(operation: TransportCircuitOperation): ErrorCategory = when (operation) {
        TransportCircuitOperation.PUSH_CHANGES,
        TransportCircuitOperation.PULL_CHANGES,
        -> ErrorCategory.NETWORK
        TransportCircuitOperation.INITIALIZE,
        TransportCircuitOperation.HEALTH,
        TransportCircuitOperation.CLOSE,
        -> ErrorCategory.PROVIDER
    }

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
