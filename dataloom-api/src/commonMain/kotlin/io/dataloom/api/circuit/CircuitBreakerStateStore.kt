package io.dataloom.api.circuit

import io.dataloom.api.provider.ProviderOperationResult

/** Versioned circuit state returned by a persistence implementation. */
public data class CircuitBreakerStateRecord(
    public val state: CircuitBreakerState,
    public val version: Long,
) {
    init {
        require(version >= 0L) { "CircuitBreakerStateRecord version must be zero or greater." }
    }
}

/** Result of loading one circuit scope. */
public sealed interface CircuitBreakerLoadResult {
    public data object Missing : CircuitBreakerLoadResult

    public data class Found(
        public val record: CircuitBreakerStateRecord,
    ) : CircuitBreakerLoadResult
}

/** Atomic compare-and-set request for one circuit scope. */
public data class CircuitBreakerCompareAndSetRequest(
    public val scope: CircuitBreakerScope,
    public val expectedVersion: Long?,
    public val nextState: CircuitBreakerState,
) {
    init {
        require(expectedVersion == null || expectedVersion >= 0L) {
            "CircuitBreakerCompareAndSetRequest expectedVersion must be null or non-negative."
        }
        require(nextState.scope == scope) {
            "CircuitBreakerCompareAndSetRequest scope must match nextState.scope."
        }
    }
}

/** Result of an atomic circuit-state compare-and-set operation. */
public sealed interface CircuitBreakerCompareAndSetResult {
    public data class Updated(
        public val record: CircuitBreakerStateRecord,
    ) : CircuitBreakerCompareAndSetResult

    public data class Conflict(
        public val current: CircuitBreakerStateRecord?,
    ) : CircuitBreakerCompareAndSetResult
}

/**
 * Persistence boundary for durable circuit-breaker state.
 *
 * Implementations must make [compareAndSet] atomic. A null expected version
 * means that no record may already exist. Cancellation must propagate.
 */
public interface CircuitBreakerStateStore {
    public suspend fun load(
        scope: CircuitBreakerScope,
    ): ProviderOperationResult<CircuitBreakerLoadResult>

    public suspend fun compareAndSet(
        request: CircuitBreakerCompareAndSetRequest,
    ): ProviderOperationResult<CircuitBreakerCompareAndSetResult>
}
