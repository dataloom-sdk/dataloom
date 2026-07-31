package io.dataloom.runtime.queue

import io.dataloom.api.circuit.CircuitBreakerScope

/**
 * Explicit circuit scopes for one bounded queue-processing cycle.
 *
 * No scope is inherited or inferred. The processor validates every
 * provider-bearing scope against the protected queue provider and every
 * operation-bearing scope against the exact queue operation before any circuit
 * state or provider access.
 */
public data class QueueProcessingCircuitScopes(
    /** Scope used for atomic queue acquisition. */
    public val acquisition: CircuitBreakerScope,

    /** Scope used for successful-completion transitions. */
    public val completion: CircuitBreakerScope,

    /** Scope used for retry-reschedule transitions. */
    public val reschedule: CircuitBreakerScope,

    /** Scope used for non-retry deferral transitions. */
    public val deferral: CircuitBreakerScope,

    /** Scope used for failure/dead-letter transitions. */
    public val failure: CircuitBreakerScope,

    /** Scope used for explicit cancellation transitions. */
    public val cancellation: CircuitBreakerScope,
)
