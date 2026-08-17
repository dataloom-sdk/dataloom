package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint

/**
 * Applies explicit durable circuit permission to storage lifecycle, read,
 * mutation, acknowledgement, and checkpoint operations while preserving the
 * complete execution and recording result.
 *
 * This adapter deliberately does not implement [StorageProvider]. A plain
 * provider result cannot preserve both an already-executed durable mutation and
 * a later circuit-state persistence failure without losing replay-critical
 * evidence.
 */
public class CircuitBreakerStorageOperationAdapter(
    private val storageProvider: StorageProvider,
    executionGate: CircuitBreakerExecutionGate,
    failureClassifier: CircuitBreakerFailureClassifier =
        StorageCircuitBreakerFailureClassifier,
) {
    private val providerOperationAdapter = CircuitBreakerProviderOperationAdapter(
        executionGate = executionGate,
        failureClassifier = failureClassifier,
    )

    /** Exact descriptor of the protected storage provider. */
    public val descriptor: ProviderDescriptor
        get() = storageProvider.descriptor

    public suspend fun initialize(
        scope: CircuitBreakerScope,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = StorageCircuitOperation.INITIALIZE,
    ) {
        storageProvider.initialize(context)
    }

    public suspend fun health(
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<ProviderHealth> = execute(
        scope = scope,
        operation = StorageCircuitOperation.HEALTH,
    ) {
        storageProvider.health()
    }

    public suspend fun close(
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = StorageCircuitOperation.CLOSE,
    ) {
        storageProvider.close()
    }

    public suspend fun readOutboundChanges(
        scope: CircuitBreakerScope,
        request: OutboundChangeReadRequest,
    ): CircuitBreakerExecutionResult<OutboundChangeReadResult> = execute(
        scope = scope,
        operation = StorageCircuitOperation.READ_OUTBOUND_CHANGES,
    ) {
        storageProvider.readOutboundChanges(request)
    }

    public suspend fun applyInboundChanges(
        scope: CircuitBreakerScope,
        request: InboundChangeApplyRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = StorageCircuitOperation.APPLY_INBOUND_CHANGES,
    ) {
        storageProvider.applyInboundChanges(request)
    }

    public suspend fun acknowledgeOutboundChanges(
        scope: CircuitBreakerScope,
        request: OutboundChangeAcknowledgementRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
    ) {
        storageProvider.acknowledgeOutboundChanges(request)
    }

    public suspend fun readCheckpoint(
        scope: CircuitBreakerScope,
        request: CheckpointReadRequest,
    ): CircuitBreakerExecutionResult<SynchronizationCheckpoint?> = execute(
        scope = scope,
        operation = StorageCircuitOperation.READ_CHECKPOINT,
    ) {
        storageProvider.readCheckpoint(request)
    }

    public suspend fun writeCheckpoint(
        scope: CircuitBreakerScope,
        request: CheckpointWriteRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = StorageCircuitOperation.WRITE_CHECKPOINT,
    ) {
        storageProvider.writeCheckpoint(request)
    }

    public suspend fun readLocalConflictCandidate(
        scope: CircuitBreakerScope,
        request: LocalConflictCandidateReadRequest,
    ): CircuitBreakerExecutionResult<LocalConflictCandidateReadResult> = execute(
        scope = scope,
        operation = StorageCircuitOperation.READ_LOCAL_CONFLICT_CANDIDATE,
    ) {
        storageProvider.readLocalConflictCandidate(request)
    }

    private suspend fun <T> execute(
        scope: CircuitBreakerScope,
        operation: StorageCircuitOperation,
        block: suspend () -> io.dataloom.api.provider.ProviderOperationResult<T>,
    ): CircuitBreakerExecutionResult<T> {
        require(scope.providerId == null || scope.providerId == descriptor.id) {
            "Storage circuit scope provider must match the protected storage provider."
        }
        require(scope.operation == null || scope.operation == operation.retryOperation) {
            "Storage circuit scope operation must match ${operation.retryOperation.value}."
        }
        return providerOperationAdapter.execute(scope, block)
    }
}
