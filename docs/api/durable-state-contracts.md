# Durable state contracts

[API reference index](./README.md)

> **Status:** The contract exists, is proven implementable, and has three real
> domain adoptions end to end — configuration snapshot history, policy
> decisions, and unresolved conflicts — all backed by the same production
> Room persistence implementation. One of the three
> ([unresolved conflicts](#adoption-unresolved-conflicts)) also has a real
> runtime caller now (`DurableConflictDetectionCoordinator`); the other two
> remain unwired because nothing yet calls the underlying evaluator/resolver
> they would attach to. Other domains (events, assets, audit) have not
> adopted the contract itself yet.

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

## Adoption: policy decisions

[`DurablePolicyDecisionLog`](./policy-foundation.md#durablepolicydecisionlog)
(`io.dataloom.api.policy`) is the second real domain adoption, and reuses
`RoomDurableStateStore` directly — no new Room DAO/entity/migration code was
written for it:

- **`TScope`** is `PolicyDecisionScope` — one `PolicySetId` evaluated for
  one `ExecutionId` (`ExecutionContext`'s own required canonical identifier
  for one execution, reused as the natural idempotency key).
- **`TState`** is `PolicyDecisionRecord` — the committed `PolicyDecision`
  plus when it was committed.
- Unlike configuration history's monotonic-version model, this domain is
  **commit-once**: `expectedVersion = null` (insert-if-absent) is the only
  compare-and-set shape this adapter ever issues. A later commit for the
  same scope is judged only by whether it agrees with what is already
  there — matching, not versioning, is the whole operation.
- **`PolicyDecisionRecordCodec`** (`DurableStateCodec<PolicyDecisionRecord>`)
  is the reference text codec, following the same hex-encoded,
  bounded-length, fail-closed-on-malformed-payload discipline as
  `ConfigurationHistoryStateCodec`.
- **`PolicyDecisionScope.KeyEncoder`** length-prefixes both fields before
  concatenating (the same scheme `circuitBreakerScopeKey` established) so no
  possible `PolicySetId`/`ExecutionId` content can shift a field boundary
  and collide with a different scope's encoded key.
- Wired the same way configuration history is:
  `RoomDurableStateStore(database, "policy-decisions", PolicyDecisionScope.KeyEncoder, PolicyDecisionRecordCodec())`
  — proving `RoomDurableStateStore`'s genericity for real, not just by
  assertion.

Both `ConfigurationHistoryScope` and `PolicyDecisionScope` now expose a
`KeyEncoder` companion property with their reference `DurableStateCodec`
implementation, so adopting `RoomDurableStateStore` for either domain never
requires writing a scope-key encoder by hand.

See [policy foundation](./policy-foundation.md#durablepolicydecisionlog) for
`DurablePolicyDecisionLog`'s own `commit`/`current` semantics, including why
commit-once rather than versioned, and how idempotent retries are
distinguished from genuine conflicts.

## Adoption: unresolved conflicts

[`DurableUnresolvedConflictLog`](./conflict-orchestration.md#durable-unresolved-conflict-log)
(`io.dataloom.api.conflict`) is the third real domain adoption, and — like
policy decisions — reuses `RoomDurableStateStore` directly with zero new
Room code:

- **`TScope`** is `ConflictId` — reused directly rather than composed into a
  new wrapper type, unlike `ConfigurationHistoryScope`/`PolicyDecisionScope`.
- **`TState`** is `UnresolvedConflictRecord` — a conflict's type, entity,
  which local/remote changes disagreed (as payload-free
  `UnresolvedConflictChangeSummary` values, never the original `ChangeEvent`),
  why no resolver ran, and when it was recorded.
- **Commit-once, narrower scope than policy decisions.** Only the two
  *unresolved* outcomes are covered
  (`ConflictOrchestrationResult.ResolverNotConfigured`/`.ResolverNotFound`)
  — not resolved decisions, and specifically not
  `ConflictResolutionDecision.Merge`'s application-supplied `ChangeEvent`
  payload. Losslessly persisting that payload would be the first exception
  to this codebase's consistent "durable/audit codecs never carry payload
  content" convention, and is a deliberately separate, larger design
  question this slice does not answer.
- **`UnresolvedConflictRecordCodec`** (`DurableStateCodec<UnresolvedConflictRecord>`)
  is the reference text codec, same hex-encoded/bounded-length/fail-closed
  discipline as the other two domains' codecs.
- **`DurableUnresolvedConflictLog.KeyEncoder`** — since `ConflictId` is a
  pre-existing shared identifier this log did not introduce, its reference
  key encoder is attached to the log class itself rather than to `ConflictId`
  (Kotlin cannot add a companion member to an existing class from a
  different file).

See [conflict orchestration](./conflict-orchestration.md#durable-unresolved-conflict-log)
for why this log deliberately does not call
`SynchronizationConflictOrchestrator` (whose own documented boundary already
forbids applying anything "to storage, queues, or any synchronization
pipeline") and for the full unresolved-vs-resolved scoping rationale.

### First real caller: `DurableConflictDetectionCoordinator`

`DurableUnresolvedConflictLog` has a real adopter —
[`DurableConflictDetectionCoordinator`](./conflict-orchestration.md#durable-conflict-detection-coordinator)
(`io.dataloom.runtime.conflict`, module `dataloom-runtime`) wraps
`SynchronizationConflictOrchestrator.detectAndResolve` and durably records
its `ResolverNotConfigured`/`ResolverNotFound` outcomes. This was possible
here — and not yet possible for `DurableConfigurationHistory`/
`DurablePolicyDecisionLog` — because the orchestrator's own `detectAndResolve`
was a genuinely standalone, callable component, unlike `PolicyEvaluator.evaluate`
and `DataLoomConfigurationResolver.resolve`, which (as of writing) have no
real caller anywhere in the codebase to compose with. This coordinator is not
itself called by any `SynchronizationPipeline` yet — see
[conflict orchestration](./conflict-orchestration.md#durable-conflict-detection-coordinator)
for exactly what remains unwired.

## What this does not do yet

- **Wiring `DurableConfigurationHistory.apply` or `DurablePolicyDecisionLog.commit`
  into a real call site.** Both remain available primitives with no real
  caller — genuinely blocked, not just undone, since neither
  `DataLoomConfigurationResolver.resolve` nor `PolicyEvaluator.evaluate` has
  a real caller anywhere in `dataloom-runtime` to compose with yet. Wiring
  either durable adapter in would currently mean inventing that caller too.
- **Wiring `DurableConflictDetectionCoordinator` into a real
  `SynchronizationPipeline`.** The coordinator itself is real and callable
  today; no pipeline calls it yet.
- **Durably persisting resolved `ConflictResolutionDecision`s**, including
  `Merge`'s payload — deliberately out of scope for
  `DurableUnresolvedConflictLog`; a separate, larger design question.
- **Events, assets, and audit** durable state — real, separately-scoped
  follow-up work; not started.
- **An Apple file-backed `DurableStateStore` implementation.** Only the
  Room implementation exists so far;
  `AppleFileCircuitBreakerStateStore` remains the only precedent for what
  an Apple file-backed one would look like.
- **SDK-wide adoption.** `DataLoomConfigurationHistory` (in-memory) is not
  superseded or removed by `DurableConfigurationHistory`, plain
  `PolicyDecision` values are not superseded by `PolicyDecisionRecord`, and
  nothing durably persists unresolved conflicts today outside this log
  itself — nothing in the runtime has been switched over to use any durable
  variant yet; that is further follow-up work, not implied by this contract
  or its adoptions existing.
