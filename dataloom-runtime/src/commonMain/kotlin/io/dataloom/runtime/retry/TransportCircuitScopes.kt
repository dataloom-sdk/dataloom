package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope

/**
 * Exact circuit scope selected for every current transport-provider operation.
 *
 * No scope is inherited or inferred. [ProtectedTransportOperations] validates
 * every provider- and operation-bearing value before state-store or provider
 * access.
 */
public data class TransportCircuitScopes(
    public val initialization: CircuitBreakerScope,
    public val health: CircuitBreakerScope,
    public val close: CircuitBreakerScope,
    public val pushChanges: CircuitBreakerScope,
    public val pullChanges: CircuitBreakerScope,
)
