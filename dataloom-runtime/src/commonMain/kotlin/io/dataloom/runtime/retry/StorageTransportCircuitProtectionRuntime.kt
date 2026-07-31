package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.TransportProvider

/** Production assembly for one scope-bound storage timeout/circuit boundary. */
public object StorageCircuitProtectionRuntime {

    /**
     * Creates an immutable storage operation surface with one shared circuit
     * coordinator, state store, classifier, provider instance, and scope set.
     *
     * When [providerTimeout] is present, timeout protection is applied before
     * circuit classification. Construction performs no provider operation,
     * state-store access, clock read, timeout execution, I/O, identifier
     * generation, or coroutine launch.
     */
    public fun create(
        storageProvider: StorageProvider,
        clock: DataLoomClock,
        circuitBreakerConfiguration: CircuitBreakerConfiguration,
        circuitBreakerStateStore: CircuitBreakerStateStore,
        scopes: StorageCircuitScopes,
        providerTimeout: SchedulingDelay? = null,
        failureClassifier: CircuitBreakerFailureClassifier =
            StorageCircuitBreakerFailureClassifier,
    ): ProtectedStorageOperations {
        val protectedProvider = if (providerTimeout == null) {
            storageProvider
        } else {
            StorageProviderTimeoutRuntime.create(
                storageProvider = storageProvider,
                clock = clock,
                providerTimeout = providerTimeout,
            )
        }
        val adapter = CircuitBreakerStorageOperationAdapter(
            storageProvider = protectedProvider,
            executionGate = CircuitBreakerExecutionGate(
                CircuitBreakerCoordinator(
                    configuration = circuitBreakerConfiguration,
                    clock = clock,
                    stateStore = circuitBreakerStateStore,
                ),
            ),
            failureClassifier = failureClassifier,
        )
        return ProtectedStorageOperations(
            adapter = adapter,
            scopes = scopes,
        )
    }
}

/** Production assembly for one scope-bound transport timeout/circuit boundary. */
public object TransportCircuitProtectionRuntime {

    /**
     * Creates an immutable transport operation surface with one shared circuit
     * coordinator, state store, classifier, provider instance, and scope set.
     *
     * When [providerTimeout] is present, timeout protection is applied before
     * circuit classification. Construction performs no provider operation,
     * state-store access, clock read, timeout execution, I/O, identifier
     * generation, or coroutine launch.
     */
    public fun create(
        transportProvider: TransportProvider,
        clock: DataLoomClock,
        circuitBreakerConfiguration: CircuitBreakerConfiguration,
        circuitBreakerStateStore: CircuitBreakerStateStore,
        scopes: TransportCircuitScopes,
        providerTimeout: SchedulingDelay? = null,
        failureClassifier: CircuitBreakerFailureClassifier =
            TransportCircuitBreakerFailureClassifier,
    ): ProtectedTransportOperations {
        val protectedProvider = if (providerTimeout == null) {
            transportProvider
        } else {
            TransportProviderTimeoutRuntime.create(
                transportProvider = transportProvider,
                clock = clock,
                providerTimeout = providerTimeout,
            )
        }
        val adapter = CircuitBreakerTransportOperationAdapter(
            transportProvider = protectedProvider,
            executionGate = CircuitBreakerExecutionGate(
                CircuitBreakerCoordinator(
                    configuration = circuitBreakerConfiguration,
                    clock = clock,
                    stateStore = circuitBreakerStateStore,
                ),
            ),
            failureClassifier = failureClassifier,
        )
        return ProtectedTransportOperations(
            adapter = adapter,
            scopes = scopes,
        )
    }
}
