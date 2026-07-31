package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.ProtectedStorageOperations
import io.dataloom.runtime.retry.ProtectedTransportOperations
import io.dataloom.runtime.retry.StorageCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.StorageCircuitProtectionRuntime
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.TransportCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.TransportCircuitProtectionRuntime
import io.dataloom.runtime.retry.TransportCircuitScopes

/** External JVM/iOS consumer probe for scope-bound provider protection assembly. */
public object ProviderCircuitProtectionRuntimeExternalConsumerProbe {

    public fun storage(
        provider: StorageProvider,
        clock: DataLoomClock,
        configuration: CircuitBreakerConfiguration,
        stateStore: CircuitBreakerStateStore,
        scopes: StorageCircuitScopes,
        providerTimeout: SchedulingDelay? = null,
        classifier: CircuitBreakerFailureClassifier =
            StorageCircuitBreakerFailureClassifier,
    ): ProtectedStorageOperations = StorageCircuitProtectionRuntime.create(
        storageProvider = provider,
        clock = clock,
        circuitBreakerConfiguration = configuration,
        circuitBreakerStateStore = stateStore,
        scopes = scopes,
        providerTimeout = providerTimeout,
        failureClassifier = classifier,
    )

    public fun transport(
        provider: TransportProvider,
        clock: DataLoomClock,
        configuration: CircuitBreakerConfiguration,
        stateStore: CircuitBreakerStateStore,
        scopes: TransportCircuitScopes,
        providerTimeout: SchedulingDelay? = null,
        classifier: CircuitBreakerFailureClassifier =
            TransportCircuitBreakerFailureClassifier,
    ): ProtectedTransportOperations = TransportCircuitProtectionRuntime.create(
        transportProvider = provider,
        clock = clock,
        circuitBreakerConfiguration = configuration,
        circuitBreakerStateStore = stateStore,
        scopes = scopes,
        providerTimeout = providerTimeout,
        failureClassifier = classifier,
    )

    public suspend fun initializeStorage(
        operations: ProtectedStorageOperations,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = operations.initialize(context)

    public suspend fun initializeTransport(
        operations: ProtectedTransportOperations,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = operations.initialize(context)
}
