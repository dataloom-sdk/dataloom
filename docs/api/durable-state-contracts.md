# Durable state contracts

[API reference index](./README.md)

> **Status:** The contract exists, is proven implementable, and has one real
> domain adoption end to end — configuration snapshot history, including a
> production Room persistence implementation. Other domains (policy
> decisions, and so on) have not adopted it yet.

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

## Generic persistence glue: `DurableStateScopeKeyEncoder` and `DurableStateCodec`

A platform store implementation generally needs to turn `TScope` into a flat
storage key and `TState` into an opaque payload, and back. Rather than have
every domain-specific store reinvent that, two small injected contracts
carry it:

```kotlin
public fun interface DurableStateScopeKeyEncoder<TScope : Any> {
    public fun encode(scope: TScope): String
}

public interface DurableStateCodec<TState : Any> {
    public fun encode(state: TState): String
    public fun decode(payload: String): TState
}
```

`decode` must be the exact inverse of `encode`, and implementations are
expected to fail closed — throw rather than return a best-effort or
partially-decoded value — on a malformed or corrupted payload. A domain
supplies one implementation of each; a platform store implementation is
then reusable across every domain that adopts `DurableStateStore`, instead
of writing new persistence code per domain.

## `RoomDurableStateStore` (`dataloom-queue-room`)

```kotlin
public class RoomDurableStateStore<TScope : Any, TState : Any>(
    database: DataLoomRoomDatabase,
    namespace: String,
    scopeKeyEncoder: DurableStateScopeKeyEncoder<TScope>,
    codec: DurableStateCodec<TState>,
) : DurableStateStore<TScope, TState>
```

Production Android Room implementation of `DurableStateStore`, generic over
any `TScope`/`TState` — the same atomic load/compare-and-set discipline
`RoomCircuitBreakerStateStore` established, backed by one shared
`durable_states` table (`namespace`, `scope_key`, `state_payload`,
`schema_version`, `record_version`; `namespace` + `scope_key` form the
composite primary key). `namespace` is the domain's own stable, unique
label (for example `"configuration-history"`) — multiple domains can share
one `DataLoomRoomDatabase` without their encoded scope keys ever colliding
with each other's rows.

Fails closed the same way `RoomCircuitBreakerStateStore` does: an oversized
encoded payload (bounded at 4 MiB), a codec `encode`/`decode` failure, or a
database exception all return a sanitized `ProviderOperationResult.Failure`
rather than throwing or silently misbehaving; only
`kotlinx.coroutines.CancellationException` propagates unchanged. Record
version exhaustion (`expectedVersion == Long.MAX_VALUE`) is rejected before
ever reaching Room, matching `RoomCircuitBreakerStateStore`.

## Adoption: configuration snapshot history

[`DurableConfigurationHistory`](./configuration-snapshots.md#durableconfigurationhistory)
(`io.dataloom.api.configuration`) is the first real domain adoption of this
contract end to end:

- **`TScope`** is `ConfigurationHistoryScope` — one per application, tenant,
  or configuration domain.
- **`TState`** is `ConfigurationHistoryState` — every currently retained
  `ConfigurationSnapshot` for a scope, oldest first.
- **`ConfigurationHistoryStateCodec`** (`DurableStateCodec<ConfigurationHistoryState>`)
  is the reference text codec: a deterministic, bounded V1 frame where each
  encoded snapshot carries its checksum hex, and `decode` recomputes the
  checksum via `ConfigurationSnapshot.create` and requires it to match — so
  storage-layer corruption that still parses as well-formed fields fails
  closed instead of silently returning the wrong snapshot.
- Wired into `RoomDurableStateStore` above via
  `RoomDurableStateStore(database, "configuration-history", scopeKeyEncoder, ConfigurationHistoryStateCodec(digestCalculator))`.

See [configuration snapshots](./configuration-snapshots.md#durableconfigurationhistory)
for `DurableConfigurationHistory`'s own `apply`/`rollbackToLastKnownGood`
semantics.

## What this does not do yet

- **Policy decisions** (`PolicyDecision`) have no durable persistence yet —
  real, separately-scoped follow-up work. Adopting `DurableStateStore` for
  it means choosing `TScope`/`TState` for that domain, a
  `DurableStateCodec<TState>` implementation, and wiring it into the
  domain's existing resolver/evaluator flow — the same shape
  `DurableConfigurationHistory` above already establishes.
- **An Apple file-backed `DurableStateStore` implementation.** Only the
  Room implementation exists so far;
  `AppleFileCircuitBreakerStateStore` remains the only precedent for what
  an Apple file-backed one would look like.
- **SDK-wide adoption.** `DataLoomConfigurationHistory` (in-memory) is not
  superseded or removed by `DurableConfigurationHistory` — nothing in the
  runtime has been switched over to use the durable variant yet; that is
  further follow-up work, not implied by this contract or its first
  adoption existing.
