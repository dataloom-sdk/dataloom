package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint

/**
 * Immutable storage-operation surface with one exact scope bound to every call.
 *
 * Construction validates all scopes before provider, state-store, clock, or
 * timeout activity. Every method preserves the complete provider execution and
 * later circuit-recording result.
 */
public class ProtectedStorageOperations internal constructor(
    private val adapter: CircuitBreakerStorageOperationAdapter,
    public val scopes: StorageCircuitScopes,
) {
    /** Exact descriptor of the protected storage provider. */
    public val descriptor: ProviderDescriptor
        get() = adapter.descriptor

    init {
        validate(scopes.initialization, StorageCircuitOperation.INITIALIZE)
        validate(scopes.health, StorageCircuitOperation.HEALTH)
        validate(scopes.close, StorageCircuitOperation.CLOSE)
        validate(scopes.readOutboundChanges, StorageCircuitOperation.READ_OUTBOUND_CHANGES)
        validate(scopes.applyInboundChanges, StorageCircuitOperation.APPLY_INBOUND_CHANGES)
        validate(
            scopes.acknowledgeOutboundChanges,
            StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
        )
        validate(scopes.readCheckpoint, StorageCircuitOperation.READ_CHECKPOINT)
        validate(scopes.writeCheckpoint, StorageCircuitOperation.WRITE_CHECKPOINT)
    }

    public suspend fun initialize(
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> =
        adapter.initialize(scopes.initialization, context)

    public suspend fun health(): CircuitBreakerExecutionResult<ProviderHealth> =
        adapter.health(scopes.health)

    public suspend fun close(): CircuitBreakerExecutionResult<Unit> =
        adapter.close(scopes.close)

    public suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): CircuitBreakerExecutionResult<OutboundChangeReadResult> =
        adapter.readOutboundChanges(scopes.readOutboundChanges, request)

    public suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): CircuitBreakerExecutionResult<Unit> =
        adapter.applyInboundChanges(scopes.applyInboundChanges, request)

    public suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): CircuitBreakerExecutionResult<Unit> =
        adapter.acknowledgeOutboundChanges(scopes.acknowledgeOutboundChanges, request)

    public suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): CircuitBreakerExecutionResult<SynchronizationCheckpoint?> =
        adapter.readCheckpoint(scopes.readCheckpoint, request)

    public suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): CircuitBreakerExecutionResult<Unit> =
        adapter.writeCheckpoint(scopes.writeCheckpoint, request)

    private fun validate(
        scope: CircuitBreakerScope,
        operation: StorageCircuitOperation,
    ) {
        require(scope.providerId == null || scope.providerId == descriptor.id) {
            "Storage protection scope provider must match the protected storage provider."
        }
        require(scope.operation == null || scope.operation == operation.retryOperation) {
            "Storage protection scope operation must match ${operation.retryOperation.value}."
        }
    }
}
