# Durable outbox ordering, retention and replay: decided design

[API reference index](./README.md)

## Status

**Decided and implemented (2026-09-19).** This document began (2026-08-26) as
an investigation that found replay of an already-acknowledged entry
"impossible by construction" without a design decision about retention and
schema. That decision has now been made and implemented in
`DurableOperationalEventOutbox`; the investigation's findings are kept below
as the record of why a decision was needed. The filename is unchanged so
existing links keep working.

Decisions (numbered as in the design request):

- **D2 -- FR-EVENT-003 ordering.** Each outbox entry carries a durable,
  monotonically increasing per-workflow sequence number, assigned at append
  time inside the same compare-and-set that persists the entry. Processing and
  replay present a workflow's entries in sequence order; entries without a
  workflow id use a documented global ordering key. Gaps are allowed;
  duplicates never are.
- **D3 -- retention and replay.** `acknowledge` no longer hard-deletes. It
  records `acknowledgedAt` and keeps the entry as a tombstone for a bounded,
  configurable window/count, so an operator can replay it; retained history is
  pruned deterministically. Pre-V1 there are no external consumers, so the
  persisted schema and codec version changed with no elaborate migration.
- **D4 -- health.** A synchronous read path for outbox and queue-worker state,
  so `dataLoomHealthSnapshot` can aggregate them, was built as slice 2 (see
  [Health snapshot](./health-snapshot.md)); it was not part of the ordering/replay change.

## What was built

### Ordering (D2)

`OperationalEventOutboxEntry(sequence, envelope, acknowledgedAt)` replaces the
bare envelope as the persisted list element. `sequence` is per
`OperationalEventOrderingKey`: `forWorkflow(envelope.workflowId)`, or
`OperationalEventOrderingKey.Global` for an envelope with no workflow id.
Workflow keys are namespaced (`workflow:<id>`) so a workflow named `global`
cannot collide with `Global`.

`append` computes `sequence = highWaterMark(key) + 1` from the very state its
compare-and-set is conditioned on. A concurrent appender that wins the race
changes the record version, the loser's compare-and-set reports `Conflict`,
and the loser reloads, sees the winner's entry, and takes the next sequence.
No in-memory counter exists, so this holds across processes sharing one
`DurableStateStore` and across restarts, using only the existing
compare-and-set contract.

The retained list stays append-ordered and `OperationalEventOutboxState`
validates on construction that sequences strictly increase per key in list
order, so "list order" and "sequence order" are the same thing and a corrupt
persisted payload cannot decode into a reordered stream. `entries`,
`pendingEntries`, `acknowledgedEntries` and the processor all present in that
order. Order *across* workflows is only the shared append order.

**No duplicates, ever -- why high-water marks are persisted.** Deriving the
next sequence from the newest retained entry would reuse a sequence whenever
retention or acknowledged-history pruning had just removed a workflow's newest
entries. The state therefore persists `sequenceHighWaterMarks` (largest
sequence ever assigned per key). That map would otherwise grow with every
workflow ever seen, so it is bounded at 10,000 keys: past that,
`boundSequenceTracking` drops the marks of keys with **no retained entry**,
lowest mark first (ties by key value, deterministic), and raises
`sequenceFloor` to the largest dropped mark. A key without a mark starts at
`sequenceFloor + 1`, so a dropped workflow that reappears can never be assigned
a sequence it already used; the only cost is a larger first gap, which the
contract allows.

Head-of-line blocking is opt-in (added after this design, see
[Operational envelope and redaction](./operational-envelope-redaction.md),
"Head-of-line blocking"). By default the processor presents a workflow's events
in sequence order but each entry's handler outcome is independent, so a later
event can be `Processed` and acknowledged while an earlier one stays pending;
with `OperationalEventOutboxOrderingPolicy.BLOCK_WORKFLOW_ON_UNFINISHED_ENTRY`
an unfinished entry holds back its workflow's successors.

### Retention and replay (D3)

`acknowledge` stamps `acknowledgedAt` (from the outbox's `clock`) in one
compare-and-set write and keeps the entry. New outcomes and calls:

| Call | Behavior |
| --- | --- |
| `acknowledge(scope, id)` | `Acknowledged(envelope, acknowledgedAt)`; `AlreadyAcknowledged` if the tombstone is retained (no write, original timestamp kept); `NotFound` if never appended, evicted, or pruned. |
| `acknowledgedEntries(scope)` | The retained tombstones, in sequence order -- the operator's replay candidates. |
| `pendingEntries(scope)` | Like `entries`, with each entry's sequence. |
| `replay(scope, id)` | `Replayed(entry)` clears the marker so the entry is pending again at its **original position and sequence**; `AlreadyPending`; `NotFound`. |

A duplicate `append` of an acknowledged id reports `AlreadyAppended` with the
original sequence, because tombstones count for the duplicate-id check -- a
producer retry never resurrects a processed entry.

Two populations, bounded separately:

- **Pending** entries keep the existing policies, now applied to pending
  entries only (a tombstone neither counts against nor is evicted by
  `maximumRetainedEntries` / `maximumRetainedAge`). With neither set they
  accumulate without bound, as before.
- **Acknowledged history** is bounded by default:
  `maximumRetainedAcknowledgedEntries` (default 1,000; `0` = discard on
  acknowledgement, no replay window) and optional `acknowledgedRetentionAge`
  measured from `acknowledgedAt`. Pruning is deterministic and runs in the
  compare-and-set of every `append` and `acknowledge`: age first, then, while
  over the count cap, the earliest `acknowledgedAt` first (ties by list
  position). Pruning a tombstone never frees its sequence.

Known interaction: a replayed entry is an ordinary pending entry at an old
position, so on a capped or age-bounded outbox it is among the first eviction
candidates at the next `append`. Process it before appending more. This is
documented rather than special-cased, to keep pending eviction one simple rule.

Access control: `replay` has no authorization concept, exactly like
`acknowledge`. A caller exposing it to operators must gate it. The outbox is a
storage primitive; authorization belongs in the layer that decides who may
call it.

Why replay is an outbox operation and not a processor mode: it changes durable
state (reopens an entry) and needs the tombstone list, both of which the outbox
owns. Once reopened, the entry flows through the existing `process` loop
unchanged -- no second consumption path.

### Persisted schema

`OperationalEventOutboxState` is now `entries: List<OperationalEventOutboxEntry>`,
`sequenceHighWaterMarks: Map<OperationalEventOrderingKey, Long>`,
`sequenceFloor: Long`. `DurableOperationalEventOutbox.CURRENT_SCHEMA_VERSION`
is `2` (the five `DataLoom*OperationalEventOutboxSpec` defaults reference it).
`OperationalEventOutboxStateCodec` writes payload format `2` and still decodes
format `1` (bare envelopes): sequences are assigned per key in list order and
everything is pending -- exactly the state a format-1 outbox represented, since
its acknowledgement deleted. Nothing else is migrated.

### Other API changes

- `DurableOperationalEventOutbox` now requires a `clock: DataLoomClock`
  (second constructor parameter). It was previously optional and only used by
  age retention; acknowledgement timestamps make it always necessary.
  `DataLoomBuilder` passes `deps.clock`. It is read during `append` only when
  an age policy is configured.
- `Appended` and `AlreadyAppended` carry the entry's `sequence`.
- `DurableOperationalEventOutboxProcessor` counts `AlreadyAcknowledged` in
  `acknowledgeRaced` alongside `NotFound`.

## Not done (next slices)

- ~~D4 health aggregation~~ -- done in slice 2: outbox counts/oldest-pending age
  reach `dataLoomHealthSnapshot` through a pushed cache
  (`DurableOperationalEventOutbox.stateObserver` + `OperationalEventOutboxHealthTracker`)
  with an explicit as-of time and staleness marker, not a store read; see
  [Health snapshot](./health-snapshot.md).
- ~~Head-of-line blocking~~ -- done (opt-in processor policy). Still open:
  batch/by-workflow replay and replay authorization, if a real consumer needs them.
- Subscription delivery and cross-scope enumeration (unchanged from before).

## The original investigation (2026-08-26), kept for the record

`#96`'s "Still pending" cell had named "replay" since the processor's first
read-then-consume loop shipped, without defining it. Two readings were
investigated:

- **(a) Re-presenting an already-acknowledged entry.** At the time impossible
  by construction: `acknowledge` used `filterNot` to remove the entry from
  `OperationalEventOutboxState.entries`, the only place an envelope was
  persisted, in the same compare-and-set as the acknowledgement. Making it
  possible needed a retention/schema decision with three open questions --
  retention duration, access control, and the persisted-schema change. D3
  answered them: bounded configurable retention (count + age), no built-in
  access control (caller-gated), and a schema bump with format-1 read
  support.
- **(b) Re-running `process` over never-acknowledged `Skipped`/`Failed`
  entries.** Already provided by calling `process` again, since those entries
  stay pending in their original position; this is unchanged, and now also
  covered by an explicit ordered test.
- **A narrower ID-scoped `replay(scope, ids)` on the processor** was rejected
  as a second way to express what `process` with a sufficient `maxEntries`
  already does. The `replay` added here is a different operation -- it reopens
  an *acknowledged* entry -- and lives on the outbox.

## References

- `dataloom-api/src/commonMain/kotlin/io/dataloom/api/operational/DurableOperationalEventOutbox.kt`,
  `OperationalEventOutboxEntry.kt`, `OperationalEventOutboxStateCodec.kt`
- `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/operational/DurableOperationalEventOutboxProcessor.kt`
- `dataloom-api/src/commonTest/kotlin/io/dataloom/api/operational/DurableOperationalEventOutboxOrderingAndReplayTest.kt`
- [Operational envelope and redaction](./operational-envelope-redaction.md) --
  "Durable outbox" section.
- [Health snapshot](./health-snapshot.md) -- the D4 follow-up context.
