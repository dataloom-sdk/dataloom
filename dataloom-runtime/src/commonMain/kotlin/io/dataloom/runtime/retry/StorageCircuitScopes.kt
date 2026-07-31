package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope

/**
 * Exact circuit scope selected for every current storage-provider operation.
 *
 * No scope is inherited or inferred. [ProtectedStorageOperations] validates
 * every provider- and operation-bearing value before state-store or provider
 * access.
 */
public data class StorageCircuitScopes(
    public val initialization: CircuitBreakerScope,
    public val health: CircuitBreakerScope,
    public val close: CircuitBreakerScope,
    public val readOutboundChanges: CircuitBreakerScope,
    public val applyInboundChanges: CircuitBreakerScope,
    public val acknowledgeOutboundChanges: CircuitBreakerScope,
    public val readCheckpoint: CircuitBreakerScope,
    public val writeCheckpoint: CircuitBreakerScope,
)
