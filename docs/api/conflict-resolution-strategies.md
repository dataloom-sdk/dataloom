# Conflict resolution strategies

## Status

**Bounded first slice.** This ships the *first* deterministic built-in
`ConflictResolver` — `LastWriteWinsConflictResolver` — real decision
application through the existing `SynchronizationConflictOrchestrator` /
`ConflictResolverRegistry` machinery, and durable persistence of resolved
decisions via a new `DurableResolvedConflictDecisionLog`. It does not ship
additional built-in strategies, conflict audit, loop prevention/quarantine,
precedence rules, a restart/concurrency proof, or AC-FUNC-002 — all of those
remain open, tracked against
[issue #95](https://github.com/dataloom-sdk/dataloom/issues/95) (DL-041).

## `LastWriteWinsConflictResolver`

`LastWriteWinsConflictResolver` (`dataloom-runtime`,
`io.dataloom.runtime.conflict`) implements the existing
`io.dataloom.api.conflict.ConflictResolver` contract — the same contract
`SynchronizationConflictOrchestrator.detectAndResolve` has accepted resolvers
through since DL-025/DL-014. No new resolver contract, no new selection
mechanism: registering and selecting this resolver uses the
`ConflictResolverRegistry` and `ConflictOrchestrationBindings` machinery that
already existed, the same way an application-supplied `ConflictResolver`
always has.

```kotlin
val resolver = LastWriteWinsConflictResolver()
val registry = ConflictResolverRegistry(listOf(resolver))
val bindings = ConflictOrchestrationBindings(
    detectorId = myDetectorId,
    resolverId = resolver.id, // "dataloom.builtin.last-write-wins" by default
)
```

### Honest tiebreak, not evidence-based recency ordering

The conventional "last write wins" strategy resolves in favor of whichever
change happened most recently in wall-clock time. This implementation cannot
do that today, because no reliable recency evidence exists anywhere on the
inputs a `ConflictResolver` is allowed to inspect:

- `io.dataloom.api.change.ChangeEvent` is explicitly documented as never
  generating or carrying "any identifiers or timestamps."
- `io.dataloom.api.change.EntityReference.version`
  (`io.dataloom.api.payload.EntityVersion`) is documented as opaque by
  `ConflictResolver` itself: "DataLoom does not assume numeric ordering,
  timestamps, or ETag semantics."
- `io.dataloom.api.model.SynchronizationRequest`, reachable via
  `ConflictResolutionRequest.synchronizationRequest`, carries no request or
  submission timestamp either.

Inventing a new timestamp field on a widely shared contract like `ChangeEvent`
or `SynchronizationConflict` to make true recency ordering possible is a real,
separate, larger design question, out of scope for this bounded first slice.

Given that gap, `LastWriteWinsConflictResolver` applies the smallest honest
fallback instead: a **deterministic remote-wins tiebreak**. It always returns
`ConflictResolutionDecision.UseRemote` for every conflict, regardless of
`ConflictType` or change content. This is a named, tracked placeholder policy,
not a claim of true "most recently written data wins" semantics. Remote is
chosen over local because it matches this codebase's existing default
synchronization posture (`InboundPullSynchronizationPipeline` already applies
remote changes locally by default); an application that wants the opposite
placeholder can trivially compose its own resolver returning `UseLocal`
unconditionally.

A future slice that adds real recency evidence to the conflict/change
contracts can introduce a genuinely evidence-based resolver alongside this
one without removing it — applications that explicitly want the deterministic
remote-wins placeholder keep it.

## Durable resolved-decision persistence

`DurableResolvedConflictDecisionLog` (`dataloom-api`,
`io.dataloom.api.conflict`) is the sixth real domain adoption of the
`DurableStateStore<TScope, TState>` contract, alongside
`DurableConfigurationHistory`, `DurablePolicyDecisionLog`,
`DurableUnresolvedConflictLog`, `DurableOperationalEventOutbox`, and
`DurableStrategyDecisionEventLog`. It follows the exact shape every prior
adopter established:

- a domain type (`ResolvedConflictDecisionRecord`) persisted per `ConflictId`,
- a `DurableStateCodec` (`ResolvedConflictDecisionRecordCodec` — hex-encoded,
  bounded to 65,536 characters, fail-closed on malformed input),
- a `DurableStateScopeKeyEncoder` (`DurableResolvedConflictDecisionLog.KeyEncoder`),
- reused through `RoomDurableStateStore` with a new `"resolved-conflict-decisions"`
  namespace and **zero new Room DAO/entity code**.

`record` is commit-once, insert-if-absent, with the same bounded
load-evaluate-compare-and-set retry loop `DurableUnresolvedConflictLog` and
`DurablePolicyDecisionLog` use. A retry that reproduces the same facts for the
same `ConflictId` reports `AlreadyRecorded`; a retry with different facts for
the same `ConflictId` reports `Conflict` and never overwrites the original.

### Avoiding the `Merge` payload landmine

`DurableUnresolvedConflictLog`'s own documentation names resolved-decision
persistence as "a separate, larger design question given `Merge`'s payload" —
`ConflictResolutionDecision.Merge` carries an application-supplied
`ChangeEvent`, and this codebase's durable/audit codecs consistently exclude
payload content. `DurableResolvedConflictDecisionLog` does not take on that
exception. `ResolvedConflictDecisionRecord` classifies the decision by
`ResolvedConflictDecisionKind` (`USE_LOCAL`, `USE_REMOTE`, `MERGE`, `DEFER`,
`FAIL`) and, when the kind is `MERGE`, records only the merged change's
structural identity (`changeEventId`, `operation`, `metadata`) via the same
payload-free `UnresolvedConflictChangeSummary` type
`DurableUnresolvedConflictLog` already uses for `localChange`/`remoteChange`.
No variant of `ConflictResolutionDecision` ever has its payload content
durably persisted by this log. A `FAIL` decision similarly records only the
bounded, non-sensitive `DataLoomError.code` value — never the error message.

`LastWriteWinsConflictResolver` never produces a `Merge` or `Fail` decision
today (it always returns `UseRemote`), but `DurableResolvedConflictDecisionLog`
is domain-general: it durably records whatever decision any registered
`ConflictResolver` returns, not just this one.

## Wiring: `DurableConflictDetectionCoordinator`

`DurableConflictDetectionCoordinator` (`dataloom-runtime`,
`io.dataloom.runtime.conflict`) — the existing real caller that already
durably records unresolved outcomes via `DurableUnresolvedConflictLog` — gained
a new optional constructor parameter, `resolvedConflictDecisionLog:
DurableResolvedConflictDecisionLog? = null`. When supplied, a
`ConflictOrchestrationResult.Resolved` outcome from
`SynchronizationConflictOrchestrator.detectAndResolve` is durably recorded
through it. When `null` (the default), behavior is byte-for-byte unchanged
from before this change — no lookup or record attempt is made.

A durable-recording failure never hides the real orchestration result,
matching this coordinator's existing posture for unresolved outcomes: the
caller always receives the real `ConflictOrchestrationResult`, plus whichever
durable record outcome applies via
`DurableConflictDetectionResult.resolvedDecisionRecordOutcome`.

`DataLoomConflictDetectionSpec` (`dataloom-runtime`,
`io.dataloom.runtime.facade`) exposes this as three new optional
constructor parameters — `resolvedConflictDecisionStore`,
`resolvedConflictDecisionLogSchemaVersion`,
`resolvedConflictDecisionLogMaximumStateUpdateAttempts` — mirroring the
existing `unresolvedConflictStore` parameters exactly. `DataLoomBuilder`
constructs `DurableResolvedConflictDecisionLog` from the store when one is
supplied and passes it into the coordinator; when no store is supplied,
resolved-decision recording stays off, matching every other opt-in spec in
this builder.

## What is still out of scope

- Only one built-in resolver ships (`LastWriteWinsConflictResolver`). Other
  deterministic strategies (for example a real evidence-based recency
  resolver, once timestamp evidence exists; a field-level merge resolver) are
  future, separately scoped slices.
- Conflict audit beyond this durable record (for example a queryable audit
  trail, retention policy, or export) is not implemented.
- Loop prevention/quarantine for repeatedly-conflicting entities is not
  implemented.
- Precedence rules across multiple registered resolvers are not implemented —
  exactly one resolver is selected per `ConflictOrchestrationBindings`, as
  before.
- A restart/concurrency proof specific to conflict resolution (analogous to
  the circuit-breaker and durable-queue proofs shipped for `#94`/`#101`) is
  not implemented.
- AC-FUNC-002 is not implemented.
