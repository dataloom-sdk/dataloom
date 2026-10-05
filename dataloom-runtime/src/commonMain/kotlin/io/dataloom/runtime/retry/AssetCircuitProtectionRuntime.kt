package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.assets.AssetProvider

/**
 * Production assembly for one scope-bound asset-provider circuit boundary
 * (`#97`, mirroring [StorageCircuitProtectionRuntime]).
 */
public object AssetCircuitProtectionRuntime {

    /**
     * Creates an immutable asset operation surface with one shared circuit
     * coordinator, state store, classifier, provider instance, and scope set.
     *
     * Construction performs no provider operation, state-store access, clock
     * read, I/O, identifier generation, or coroutine launch.
     */
    public fun create(
        assetProvider: AssetProvider,
        clock: DataLoomClock,
        circuitBreakerConfiguration: CircuitBreakerConfiguration,
        circuitBreakerStateStore: CircuitBreakerStateStore,
        scopes: AssetCircuitScopes,
        failureClassifier: CircuitBreakerFailureClassifier =
            DefaultCircuitBreakerFailureClassifier,
    ): ProtectedAssetOperations {
        val adapter = CircuitBreakerAssetOperationAdapter(
            assetProvider = assetProvider,
            executionGate = CircuitBreakerExecutionGate(
                CircuitBreakerCoordinator(
                    configuration = circuitBreakerConfiguration,
                    clock = clock,
                    stateStore = circuitBreakerStateStore,
                ),
            ),
            failureClassifier = failureClassifier,
        )
        return ProtectedAssetOperations(
            adapter = adapter,
            scopes = scopes,
        )
    }
}
