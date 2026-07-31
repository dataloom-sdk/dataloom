package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.CircuitBreakerStorageOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerTransportOperationAdapter
import io.dataloom.runtime.retry.StorageCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.TransportCircuitOperation

/** External JVM/iOS consumer probe for provider circuit adapters. */
public object StorageTransportCircuitExternalConsumerProbe {

    public fun storageAdapter(
        provider: StorageProvider,
        gate: CircuitBreakerExecutionGate,
        classifier: CircuitBreakerFailureClassifier =
            StorageCircuitBreakerFailureClassifier,
    ): CircuitBreakerStorageOperationAdapter = CircuitBreakerStorageOperationAdapter(
        storageProvider = provider,
        executionGate = gate,
        failureClassifier = classifier,
    )

    public fun transportAdapter(
        provider: TransportProvider,
        gate: CircuitBreakerExecutionGate,
        classifier: CircuitBreakerFailureClassifier =
            TransportCircuitBreakerFailureClassifier,
    ): CircuitBreakerTransportOperationAdapter = CircuitBreakerTransportOperationAdapter(
        transportProvider = provider,
        executionGate = gate,
        failureClassifier = classifier,
    )

    public fun storageScope(
        providerId: ProviderId,
        operation: StorageCircuitOperation,
    ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
        providerId = providerId,
        operation = operation.retryOperation,
    )

    public fun transportScope(
        providerId: ProviderId,
        operation: TransportCircuitOperation,
    ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
        providerId = providerId,
        operation = operation.retryOperation,
    )

    public suspend fun initializeStorage(
        adapter: CircuitBreakerStorageOperationAdapter,
        scope: CircuitBreakerScope,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = adapter.initialize(scope, context)

    public suspend fun initializeTransport(
        adapter: CircuitBreakerTransportOperationAdapter,
        scope: CircuitBreakerScope,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = adapter.initialize(scope, context)

    public suspend fun push(
        adapter: CircuitBreakerTransportOperationAdapter,
        scope: CircuitBreakerScope,
        request: PushChangesRequest,
    ): CircuitBreakerExecutionResult<ChangeSetAcknowledgement> =
        adapter.pushChanges(scope, request)

    public suspend fun pull(
        adapter: CircuitBreakerTransportOperationAdapter,
        scope: CircuitBreakerScope,
        request: PullChangesRequest,
    ): CircuitBreakerExecutionResult<PullChangesResult> =
        adapter.pullChanges(scope, request)
}
