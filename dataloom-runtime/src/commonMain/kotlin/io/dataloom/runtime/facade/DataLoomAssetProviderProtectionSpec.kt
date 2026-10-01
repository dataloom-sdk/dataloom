package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.runtime.retry.AssetCircuitScopes
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier

/**
 * Immutable asset-provider circuit/retry protection specification used by
 * [DataLoomBuilder.assetProviderProtectionConfiguration] (`#97`, mirroring
 * [DataLoomStorageProtectionSpec]).
 *
 * Requires [DataLoomBuilder.assetTransferConfiguration] to also be set; the
 * builder wraps that spec's `provider` in a circuit-protected decorator
 * before constructing [io.dataloom.assets.AssetTransferEngine] over it. The
 * state store is supplied explicitly; the builder never creates an in-memory
 * fallback. Construction performs no provider, store, clock, I/O,
 * identifier, or coroutine activity.
 */
public class DataLoomAssetProviderProtectionSpec(
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,
    public val circuitBreakerStateStore: CircuitBreakerStateStore,
    public val scopes: AssetCircuitScopes,
    public val failureClassifier: CircuitBreakerFailureClassifier =
        DefaultCircuitBreakerFailureClassifier,
) {
    /** Bounded diagnostic representation that excludes the state store and classifier. */
    override fun toString(): String =
        "DataLoomAssetProviderProtectionSpec(scopeCount=9)"
}
