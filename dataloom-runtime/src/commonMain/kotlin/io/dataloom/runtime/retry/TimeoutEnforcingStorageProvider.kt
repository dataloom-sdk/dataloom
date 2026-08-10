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
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint

/**
 * [StorageProvider] decorator that enforces one explicit provider-timeout
 * boundary around lifecycle and synchronization storage operations.
 *
 * Completed provider results and the delegate descriptor are preserved exactly.
 * Caller cancellation and unexpected programming exceptions propagate.
 *
 * A timed-out storage mutation may have committed before cooperative
 * cancellation was observed. Such timeouts therefore use
 * [Recoverability.UNKNOWN] and are never presented as automatically retryable.
 * Read-only health, outbound-read, and checkpoint-read timeouts remain
 * [Recoverability.RECOVERABLE].
 */
public class TimeoutEnforcingStorageProvider(
    private val delegate: StorageProvider,
    private val timeoutCoordinator: RetryTimeoutCoordinator,
) : StorageProvider {

    override val descriptor: ProviderDescriptor
        get() = delegate.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = execute(StorageCircuitOperation.INITIALIZE) {
        delegate.initialize(context)
    }

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        execute(StorageCircuitOperation.HEALTH) {
            delegate.health()
        }

    override suspend fun close(): ProviderOperationResult<Unit> =
        execute(StorageCircuitOperation.CLOSE) {
            delegate.close()
        }

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> =
        execute(StorageCircuitOperation.READ_OUTBOUND_CHANGES) {
            delegate.readOutboundChanges(request)
        }

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageCircuitOperation.APPLY_INBOUND_CHANGES) {
            delegate.applyInboundChanges(request)
        }

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES) {
            delegate.acknowledgeOutboundChanges(request)
        }

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> =
        execute(StorageCircuitOperation.READ_CHECKPOINT) {
            delegate.readCheckpoint(request)
        }

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageCircuitOperation.WRITE_CHECKPOINT) {
            delegate.writeCheckpoint(request)
        }

    private suspend fun <T> execute(
        operation: StorageCircuitOperation,
        block: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = when (
        val result = timeoutCoordinator.execute(
            kind = RetryTimeoutKind.PROVIDER,
            operation = block,
        )
    ) {
        is RetryTimeoutExecutionResult.Completed -> result.value
        is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
            StorageTimeoutErrors.providerTimedOut(operation),
        )
        is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded -> ProviderOperationResult.Failure(
            StorageTimeoutErrors.workflowDeadlineExceeded(operation),
        )
        is RetryTimeoutExecutionResult.ClockRegression -> ProviderOperationResult.Failure(
            StorageTimeoutErrors.clockRegression(operation),
        )
    }
}

internal object StorageTimeoutErrors {
    internal const val PROVIDER_TIMEOUT_CODE: String = "STORAGE_PROVIDER_TIMEOUT"

    fun providerTimedOut(operation: StorageCircuitOperation): DataLoomError = Error(
        code = ErrorCode(PROVIDER_TIMEOUT_CODE),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = when (operation) {
            StorageCircuitOperation.HEALTH,
            StorageCircuitOperation.READ_OUTBOUND_CHANGES,
            StorageCircuitOperation.READ_CHECKPOINT,
            -> Recoverability.RECOVERABLE
            StorageCircuitOperation.INITIALIZE,
            StorageCircuitOperation.CLOSE,
            StorageCircuitOperation.APPLY_INBOUND_CHANGES,
            StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
            StorageCircuitOperation.WRITE_CHECKPOINT,
            -> Recoverability.UNKNOWN
        },
        message = when (operation) {
            StorageCircuitOperation.HEALTH,
            StorageCircuitOperation.READ_OUTBOUND_CHANGES,
            StorageCircuitOperation.READ_CHECKPOINT,
            -> "The storage provider ${operation.retryOperation.value} operation exceeded its " +
                "configured timeout."
            StorageCircuitOperation.APPLY_INBOUND_CHANGES,
            StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
            StorageCircuitOperation.WRITE_CHECKPOINT,
            -> "The storage provider ${operation.retryOperation.value} operation exceeded its " +
                "configured timeout; durable completion is not confirmed."
            StorageCircuitOperation.INITIALIZE,
            StorageCircuitOperation.CLOSE,
            -> "The storage provider ${operation.retryOperation.value} operation exceeded its " +
                "configured timeout; completion is not confirmed."
        },
    )

    fun workflowDeadlineExceeded(operation: StorageCircuitOperation): DataLoomError = Error(
        code = ErrorCode("STORAGE_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before storage provider " +
            "${operation.retryOperation.value} completed.",
    )

    fun clockRegression(operation: StorageCircuitOperation): DataLoomError = Error(
        code = ErrorCode("STORAGE_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented storage provider " +
            "${operation.retryOperation.value} timeout enforcement.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
