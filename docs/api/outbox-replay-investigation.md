# Durable outbox "replay": investigated, no code needed

[API reference index](./README.md)

## Status

**Investigated (2026-08-26). Not a bounded slice to build — the narrow
reading of "replay" is already trivially satisfied by
`DurableOperationalEventOutboxProcessor.process`'s existing API, and the
broader reading (replaying an already-acknowledged entry) is impossible by
construction today without a real design change to
`DurableOperationalEventOutbox`'s core acknowledge-deletes semantics.** This
document records why, so a future attempt does not re-derive the same
conclusion from scratch.

`docs/status/market-readiness.md`'s `#96` row's percentage is unchanged by
this investigation. Only the "Still pending" wording is corrected to name
the real remaining gap precisely instead of the single unqualified word
"replay".

## Where "replay" came from

`#96`'s row has named "replay" in its "Still pending" cell since `#355`
shipped `DurableOperationalEventOutboxProcessor`'s first real read-then-consume
loop, and `docs/api/operational-envelope-redaction.md`'s "Remaining V1
boundary" section has separately paired it with "cross-scope enumeration"
since even earlier. Neither place ever defined what "replay" was meant to
add on top of `entries`/`acknowledge`/`process`, so this investigation starts
from first principles: what would "replay" have to mean to be a real,
distinct capability, and does `DurableOperationalEventOutbox` or
`DurableOperationalEventOutboxProcessor` already provide it?

## Two readings, investigated separately

### Reading (a): re-presenting an already-acknowledged entry

This is the reading "replay" most naturally suggests by analogy with
message-broker replay (Kafka consumer-offset rewind, SQS/queue redelivery):
hand a handler an entry it (or another handler) already finished with,
typically for reprocessing after a downstream failure discovered later, or
for audit/backfill.

`DurableOperationalEventOutbox.acknowledge` makes this **impossible by
construction, not merely unimplemented**:

```kotlin
val nextEntries = currentState.entries.filterNot { it.id == id }
```

(`dataloom-api/src/commonMain/kotlin/io/dataloom/api/operational/DurableOperationalEventOutbox.kt`,
`acknowledge`). An acknowledged entry is removed from
`OperationalEventOutboxState.entries` — the *only* place this outbox
persists an envelope — as part of the same atomic compare-and-set write that
performs the acknowledgement. There is no soft-delete flag, no
`acknowledgedAt` timestamp, and no separate history/archive state either in
`OperationalEventOutboxState` or anywhere else in this class. Once
`acknowledge` reports `Acknowledged`, the envelope's bytes are gone from
durable storage; a later `entries(scope)` call cannot see it, and there is
nothing for a hypothetical `replay(scope, id)` call to read back.

`DurableOperationalEventOutbox`'s own "Acknowledgement" KDoc independently
confirms this is deliberate, not an oversight:

> This is deliberately **operator-driven dismissal from view, not
> work-queue completion**. ... `acknowledge` does not add or imply any
> "processing" contract this outbox never had: it is the durable
> counterpart of an operator clearing a diagnostic entry they have already
> reviewed out of a dashboard list.

An operator "un-clearing" a diagnostic entry they already dismissed, so it
can be handed to a handler again, is a materially different guarantee than
anything this class currently promises. Making reading (a) possible would
require a real, separately-scoped design change to
`DurableOperationalEventOutbox` itself — for example, retaining acknowledged
entries in a second, separate persisted list (an "acknowledged/history"
state alongside `entries`) rather than deleting them outright. That is not
a bounded addition on top of the processor; it is a core retention/schema
decision with its own open questions this investigation is explicitly not
authorized to settle unilaterally:

- **How long** would acknowledged entries be retained before they too are
  evicted — a third retention policy alongside the existing count-based and
  age-based ones, or unbounded (which reintroduces the exact unbounded-growth
  problem `maximumRetainedEntries`/`maximumRetainedAge` were built to solve,
  now for a *second* list)?
- **What access/audit control** applies to replaying an acknowledged entry —
  today `acknowledge` has no authorization concept at all (any caller with a
  `DurableOperationalEventOutbox` reference may acknowledge anything), and a
  "replay" capability that can cause an operator-dismissed entry to be acted
  on again is a meaningfully bigger trust surface than read-only `entries`.
- **Does this change `OperationalEventOutboxState`'s persisted schema** — a
  second list is a new field, which is a schema-version-relevant change for
  every existing scope already durably persisted, not a purely additive
  parameter default the way `maximumRetainedEntries`/`maximumRetainedAge`
  were.

None of these have a single obviously-correct answer, and guessing one to
force a "bounded slice" would risk exactly the kind of unilateral core-
semantics change this task was explicitly told not to make. This reading of
"replay" is a real, legitimate future gap — but it is a design decision, not
an implementation task.

### Reading (b): re-running `process` over currently-retained entries

The narrower reading: "replay" means giving a caller a way to have a handler
see entries again that it (or a prior `process` cycle) already saw but did
not finish with — i.e., entries the handler reported `Skipped` or `Failed`
for.

`DurableOperationalEventOutboxProcessor.process` already provides exactly
this, with no new code:

- `Skipped` and `Failed` both leave the entry retained (`process` never
  acknowledges it) — see `OperationalEventOutboxEntryOutcome`'s own KDoc:
  "The entry is left retained; it is presented again on a later processing
  pass," said of both variants.
- Nothing stops a caller invoking `process(scope, ...)` more than once.
  Every call is a fresh `outbox.entries(scope)` read — genuinely
  independent, not a cursor or subscription with state to reset.
- Ordering guarantees this happens automatically, without the caller doing
  anything special: `entries` always returns oldest-first, unacknowledged
  entries keep their original position (only `acknowledge` or retention
  eviction ever removes an entry — a later `append` only ever adds to the
  end), so an entry a handler reported `Skipped`/`Failed` for remains among
  the *oldest* still-retained entries and is therefore re-presented at or
  near the front of the very next `process` call's batch (`matching.take(maxEntries)`),
  ahead of any entry appended since. A caller wanting to guarantee a specific
  failed entry is retried needs only ensure `maxEntries` is large enough to
  reach it — never a new API.

This means a caller "replaying" entries in this sense is not a distinct
capability at all — it is the processor's existing retry-by-default
behavior, already documented, already tested by the existing regression
suite's ordering guarantees. No code change makes this more true than it
already is.

### A narrower still reading, also investigated: "replay these specific entry IDs"

The task also asked whether a `process` mode scoped only to specific entry
IDs a prior cycle saw and reported `Skipped`/`Failed` for — a `replay(scope,
ids)`-shaped call distinct from a full `process(scope, ...)` — would add
anything reading (b) does not already give.

It would not, for the same ordering reason reading (b) relies on: because
`Skipped`/`Failed` entries are never removed from their original position
and `append` only ever adds newer entries after them, those entries are
already guaranteed to be among the oldest currently-retained, filter-matching
entries — exactly the ones an ordinary `process(scope, maxEntries, filter,
handler)` call reads first. Scoping a call to a specific ID list would only
matter if a caller wanted to skip over *other*, still-retained entries
between the failed ones and now — but nothing in this outbox's existing
"operator-driven dismissal" model, or in any of the five real bridge callers'
usage, calls for that. Adding an ID-scoped replay entry point would be a
second way to express a call `process` (with a sufficient `maxEntries`)
already fully answers — API surface for its own sake, not new capability.

## Conclusion

- **Reading (a)** (replay of already-acknowledged entries) is the one
  legitimately real gap, but it is not a bounded slice on top of the
  processor — it requires `DurableOperationalEventOutbox` to retain rather
  than delete acknowledged entries, a real design decision (retention
  duration, access control, and a schema change) this investigation
  deliberately does not make unilaterally.
- **Reading (b)** (re-presenting currently-retained, never-acknowledged
  `Skipped`/`Failed` entries) is already fully provided by calling
  `DurableOperationalEventOutboxProcessor.process` again — no code needed.
- **The narrower ID-scoped variant** of reading (b) collapses into it, for
  the same ordering reason, and adds no real capability.

No source code changed as a result of this investigation. The corrected
`docs/status/market-readiness.md` `#96` row wording, and the clarifying
`docs/api/operational-envelope-redaction.md` "Replay" note, are the
deliverables.

## References

- `dataloom-api/src/commonMain/kotlin/io/dataloom/api/operational/DurableOperationalEventOutbox.kt` —
  `acknowledge`'s `filterNot`-based deletion and its "Acknowledgement" KDoc.
- `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/operational/DurableOperationalEventOutboxProcessor.kt` —
  `OperationalEventOutboxEntryOutcome.Skipped`/`Failed`'s "presented again on
  a later processing pass" KDoc, and `process`'s ordering behavior.
- [Operational envelope and redaction](./operational-envelope-redaction.md) —
  "Durable outbox" section, "Replay" note.
- [`RetryPolicy`/`StrategyPolicy` migration investigation](./retry-strategy-policy-migration-investigation.md) —
  this session's precedent for a docs-only investigation that finds a named
  "Still pending" item is not what it appears to be.
