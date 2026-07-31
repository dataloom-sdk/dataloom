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
    ): ProviderOperationResult<Unit> = execute(StorageTimeoutOperation.INITIALIZE) {
        delegate.initialize(context)
    }

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        execute(StorageTimeoutOperation.HEALTH) {
            delegate.health()
        }

    override suspend fun close(): ProviderOperationResult<Unit> =
        execute(StorageTimeoutOperation.CLOSE) {
            delegate.close()
        }

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> =
        execute(StorageTimeoutOperation.READ_OUTBOUND_CHANGES) {
            delegate.readOutboundChanges(request)
        }

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageTimeoutOperation.APPLY_INBOUND_CHANGES) {
            delegate.applyInboundChanges(request)
        }

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageTimeoutOperation.ACKNOWLEDGE_OUTBOUND_CHANGES) {
            delegate.acknowledgeOutboundChanges(request)
        }

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> =
        execute(StorageTimeoutOperation.READ_CHECKPOINT) {
            delegate.readCheckpoint(request)
        }

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageTimeoutOperation.WRITE_CHECKPOINT) {
            delegate.writeCheckpoint(request)
        }

    private suspend fun <T> execute(
        operation: StorageTimeoutOperation,
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

private enum class StorageTimeoutOperation(
    val label: String,
    val readOnly: Boolean = false,
    val durableMutation: Boolean = false,
) {
    INITIALIZE("initialize"),
    HEALTH("health", readOnly = true),
    CLOSE("close"),
    READ_OUTBOUND_CHANGES("read-outbound-changes", readOnly = true),
    APPLY_INBOUND_CHANGES("apply-inbound-changes", durableMutation = true),
    ACKNOWLEDGE_OUTBOUND_CHANGES(
        "acknowledge-outbound-changes",
        durableMutation = true,
    ),
    READ_CHECKPOINT("read-checkpoint", readOnly = true),
    WRITE_CHECKPOINT("write-checkpoint", durableMutation = true),
}

private object StorageTimeoutErrors {
    fun providerTimedOut(operation: StorageTimeoutOperation): DataLoomError = Error(
        code = ErrorCode("STORAGE_PROVIDER_TIMEOUT"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = if (operation.readOnly) {
            Recoverability.RECOVERABLE
        } else {
            Recoverability.UNKNOWN
        },
        message = when {
            operation.readOnly ->
                "The storage provider ${operation.label} operation exceeded its configured timeout."
            operation.durableMutation ->
                "The storage provider ${operation.label} operation exceeded its configured timeout; " +
                    "durable completion is not confirmed."
            else ->
                "The storage provider ${operation.label} operation exceeded its configured timeout; " +
                    "completion is not confirmed."
        },
    )

    fun workflowDeadlineExceeded(operation: StorageTimeoutOperation): DataLoomError = Error(
        code = ErrorCode("STORAGE_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before storage provider " +
            "${operation.label} completed.",
    )

    fun clockRegression(operation: StorageTimeoutOperation): DataLoomError = Error(
        code = ErrorCode("STORAGE_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented storage provider ${operation.label} " +
            "timeout enforcement.",
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
