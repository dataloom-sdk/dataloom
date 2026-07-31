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
 * Cooperative provider-timeout decorator for transport lifecycle, push, and
 * pull operations.
 *
 * This boundary does not implement protocol-specific connection, request, or
 * idle timeouts. Caller cancellation and unexpected programming exceptions
 * propagate unchanged.
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
        category = when (operation) {
            TransportCircuitOperation.PUSH_CHANGES,
            TransportCircuitOperation.PULL_CHANGES,
            -> ErrorCategory.NETWORK
            else -> ErrorCategory.PROVIDER
        },
        severity = ErrorSeverity.ERROR,
        recoverability = when (operation) {
            TransportCircuitOperation.HEALTH,
            TransportCircuitOperation.PULL_CHANGES,
            -> Recoverability.RECOVERABLE
            TransportCircuitOperation.INITIALIZE,
            TransportCircuitOperation.CLOSE,
            TransportCircuitOperation.PUSH_CHANGES,
            -> Recoverability.UNKNOWN
        },
        message = "The transport provider ${operation.retryOperation.value} operation exceeded its configured timeout.",
    )

    fun workflowDeadlineExceeded(operation: TransportCircuitOperation): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.NETWORK,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before ${operation.retryOperation.value} completed.",
    )

    fun clockRegression(operation: TransportCircuitOperation): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented ${operation.retryOperation.value} timeout enforcement.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
