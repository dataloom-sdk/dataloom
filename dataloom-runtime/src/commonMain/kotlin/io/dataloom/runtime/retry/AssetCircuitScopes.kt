package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope

/**
 * Exact circuit scope selected for every current asset-provider operation
 * (`#97`).
 *
 * No scope is inherited or inferred. [ProtectedAssetOperations] validates
 * every provider- and operation-bearing value before state-store or provider
 * access, mirroring [StorageCircuitScopes]'s own invariant.
 */
public data class AssetCircuitScopes(
    public val initialization: CircuitBreakerScope,
    public val health: CircuitBreakerScope,
    public val close: CircuitBreakerScope,
    public val openUpload: CircuitBreakerScope,
    public val uploadChunk: CircuitBreakerScope,
    public val completeUpload: CircuitBreakerScope,
    public val abortUpload: CircuitBreakerScope,
    public val readManifest: CircuitBreakerScope,
    public val readChunk: CircuitBreakerScope,
)
