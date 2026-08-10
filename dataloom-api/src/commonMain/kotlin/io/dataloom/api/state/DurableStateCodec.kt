package io.dataloom.api.state

/**
 * Turns a [TScope] into the flat string key a [DurableStateStore] platform
 * implementation persists it under.
 *
 * Kept as a separate injected collaborator, rather than requiring [TScope] to
 * implement some `toKey()` method itself, so a domain's existing scope type
 * never has to know it is being persisted, and so one domain type can be
 * keyed differently by different store implementations if ever needed.
 *
 * Implementations must be deterministic and collision-free: two unequal
 * [TScope] values must never [encode] to the same key, and the same [TScope]
 * value must always [encode] to the same key.
 */
public fun interface DurableStateScopeKeyEncoder<TScope : Any> {
    public fun encode(scope: TScope): String
}

/**
 * Turns a [TState] into an opaque string payload a [DurableStateStore]
 * platform implementation persists, and back.
 *
 * Kept as a separate injected collaborator, for the same reason as
 * [DurableStateScopeKeyEncoder]: a domain's [TState] type never has to know
 * it is being persisted, or in what format.
 *
 * ## Contract
 *
 * [decode] must be the exact inverse of [encode]: `decode(encode(state))`
 * must equal `state` for every [TState] value a domain actually produces.
 * Implementations are expected to fail closed — throwing rather than
 * silently returning a best-effort or partially-decoded value — when
 * [decode] is given a payload that is malformed, truncated, or fails an
 * integrity check the implementation performs, so persistence corruption
 * surfaces as an explicit failure instead of a wrong in-memory state.
 */
public interface DurableStateCodec<TState : Any> {
    public fun encode(state: TState): String
    public fun decode(payload: String): TState
}
