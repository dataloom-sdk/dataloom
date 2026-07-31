package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerStorageOperationAdapter
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageProviderTimeoutRuntime

/** External JVM/iOS compilation probe for storage timeout and circuit APIs. */
public object StorageCircuitTimeoutExternalConsumerProbe {

    public fun timeoutProtected(
        provider: StorageProvider,
        clock: DataLoomClock,
        timeout: SchedulingDelay,
    ): StorageProvider = StorageProviderTimeoutRuntime.create(
        storageProvider = provider,
        clock = clock,
        providerTimeout = timeout,
    )

    public fun adapter(
        provider: StorageProvider,
        gate: CircuitBreakerExecutionGate,
    ): CircuitBreakerStorageOperationAdapter =
        CircuitBreakerStorageOperationAdapter(provider, gate)

    public suspend fun initialize(
        adapter: CircuitBreakerStorageOperationAdapter,
        scope: CircuitBreakerScope,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = adapter.initialize(scope, context)

    public suspend fun health(
        adapter: CircuitBreakerStorageOperationAdapter,
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<ProviderHealth> = adapter.health(scope)

    public suspend fun readOutbound(
        adapter: CircuitBreakerStorageOperationAdapter,
        scope: CircuitBreakerScope,
        request: OutboundChangeReadRequest,
    ): CircuitBreakerExecutionResult<OutboundChangeReadResult> =
        adapter.readOutboundChanges(scope, request)

    public suspend fun applyInbound(
        adapter: CircuitBreakerStorageOperationAdapter,
        scope: CircuitBreakerScope,
        request: InboundChangeApplyRequest,
    ): CircuitBreakerExecutionResult<Unit> = adapter.applyInboundChanges(scope, request)

    public suspend fun acknowledge(
        adapter: CircuitBreakerStorageOperationAdapter,
        scope: CircuitBreakerScope,
        request: OutboundChangeAcknowledgementRequest,
    ): CircuitBreakerExecutionResult<Unit> =
        adapter.acknowledgeOutboundChanges(scope, request)

    public suspend fun readCheckpoint(
        adapter: CircuitBreakerStorageOperationAdapter,
        scope: CircuitBreakerScope,
        request: CheckpointReadRequest,
    ): CircuitBreakerExecutionResult<SynchronizationCheckpoint?> =
        adapter.readCheckpoint(scope, request)

    public suspend fun writeCheckpoint(
        adapter: CircuitBreakerStorageOperationAdapter,
        scope: CircuitBreakerScope,
        request: CheckpointWriteRequest,
    ): CircuitBreakerExecutionResult<Unit> = adapter.writeCheckpoint(scope, request)

    public fun mutationOperation(): io.dataloom.api.retry.RetryOperation =
        StorageCircuitOperation.APPLY_INBOUND_CHANGES.retryOperation
}
