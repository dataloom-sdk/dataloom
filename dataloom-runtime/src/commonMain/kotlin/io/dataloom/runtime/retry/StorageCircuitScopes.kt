package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope

/**
 * Exact circuit scope selected for every current storage-provider operation.
 *
 * No scope is inherited or inferred. [ProtectedStorageOperations] validates
 * every provider- and operation-bearing value before state-store or provider
 * access.
 *
 * [readLocalConflictCandidate] has no default value. A scope silently
 * inferred from another operation (or a generic global scope) would violate
 * this class's own "no scope is inherited or inferred" invariant, so every
 * caller must supply an explicit, correctly-shaped scope for this operation
 * exactly as it already must for the other seven — see
 * [io.dataloom.runtime.execution.protection.ProviderProtectionStorageBridge]
 * for the decorator this closes conflict-detection circuit protection for.
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
    public val readLocalConflictCandidate: CircuitBreakerScope,
)
