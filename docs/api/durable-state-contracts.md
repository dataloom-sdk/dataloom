# Durable state contracts

[API reference index](./README.md)

> **Status:** Bounded first slice. The contract exists and is proven
> implementable; no domain has adopted it as its real persistence path yet.

## Status

`#93` requires "durable, transactional, versioned state primitives for
retry/circuit state, unresolved conflicts, event outbox/audit, asset
sessions, and administrative commands" — a *shared* contract these domains
can adopt, not a separately invented persistence shape per domain.

[`CircuitBreakerStateStore`](./circuit-breaker.md) already proved this exact
shape works for one domain: atomic `load`/`compareAndSet`, optimistic
concurrency via a version counter. `DurableStateStore<TScope, TState>`
generalizes it so other domains can adopt the same proven pattern going
forward, instead of inventing a parallel one each time.

`CircuitBreakerStateStore` itself is deliberately left as-is rather than
retrofitted onto this contract — it already has multiple real platform
implementations in production (Room, Apple file-based), and retrofitting it
here would risk destabilizing working, tested persistence for no behavioral
gain. `DurableStateStore` is for domains adopting durable state *now*.

## The contract

```kotlin
public interface DurableStateStore<TScope : Any, TState : Any> {
    public suspend fun load(
        scope: TScope,
    ): ProviderOperationResult<DurableStateLoadResult<TState>>

    public suspend fun compareAndSet(
        request: DurableStateCompareAndSetRequest<TScope, TState>,
    ): ProviderOperationResult<DurableStateCompareAndSetResult<TState>>
}
```

`TScope` identifies *which* piece of state a domain is addressing (a
configuration key, a policy set ID, a conflict ID). `TState` is the domain's
own state shape. Neither type parameter is constrained beyond ordinary value
semantics, so a domain's existing types can be used directly without
wrapping.

A `null` `expectedVersion` on `compareAndSet` means no record may already
exist for that scope — the same "insert if absent" semantics
`CircuitBreakerStateStore` already established.

## Schema versions and migration

`DurableStateRecord.version` and `DurableStateRecord.schemaVersion` are
independent concepts:

- `version` is the optimistic-concurrency generation counter for
  `compareAndSet` — it increases by exactly one on every successful update.
- `schemaVersion` identifies which shape of `TState` a persisted record was
  written as, so a domain can evolve `TState` over time without breaking
  already-persisted records.

`DurableStateMigration<TState>` is an optional upcast function a domain
supplies to the `applyMigration` helper when loading a record written under
an older schema version:

```kotlin
val current = applyMigration(
    record = loaded,
    currentSchemaVersion = 2,
    migration = DurableStateMigration { fromSchemaVersion, state ->
        // upcast `state` from `fromSchemaVersion` to the current shape
    },
)
```

`applyMigration` is a plain helper, not part of `DurableStateStore` itself —
callers apply it explicitly after `load()` so migration timing and failure
handling stay visible at the call site rather than hidden inside a
persistence implementation. A store implementation is not required to call
it automatically.

## What this does not do yet

This slice ships the contract and proves it with a real in-memory
implementation and a full test suite — it does not yet wire any real domain
onto it. The two most-flagged gaps this contract is aimed at closing next:

- **Configuration snapshot history** (`DataLoomConfigurationHistory`) has no
  durable persistence today — history is in-memory only.
- **Policy decisions** (`PolicyDecision`) have no durable persistence
  today either.

Both are real, separately-scoped follow-up work, not implied by this
contract shipping. Adopting `DurableStateStore` for either domain means
choosing `TScope`/`TState` for that domain, writing at least one real
platform store implementation (matching the discipline `CircuitBreakerStateStore`
established — Room and/or Apple file-based, not just an in-memory fake), and
wiring it into the domain's existing resolver/evaluator flow.
