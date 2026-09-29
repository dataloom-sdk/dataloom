# ADR-0012: Quarantine credit-back for infrastructure failures

> **Numbering note.** `ADR-0009` (plugin version/dependency gating),
> `ADR-0010` (governance signed policy packs) and `ADR-0011` (conflict metrics
> and retry integration) are taken or reserved by other PRs; this ADR uses
> `0012`. The slug `quarantine-credit-back-for-infrastructure-failures` is the
> stable identifier if numbering changes before merge.

## Status

Accepted (decision D21, taken by the project lead on 2026-09-28); implemented.
Amends decision D18 (loop/non-convergence quarantine), which is documented in
[`docs/api/conflict-resolution-strategies.md`](../api/conflict-resolution-strategies.md).

## Date

2026-09-28

## Context

D18 quarantines an entity after a configurable number of conflict occurrences so
a fail-closed loop (`Defer`, `Fail`, unresolved) cannot re-conflict forever. It
counts every detected conflict, including a *replay*. That is right when the
replay is the loop, and wrong when the replay only exists because applying the
batch failed for an infrastructure reason: a storage or provider error while
preparing, applying, or checkpointing the batch is retried by the existing retry
policy and circuit breaker, and each retry re-detects the same conflict and
counts again. A streak of transient failures at or above the threshold therefore
quarantined a healthy entity that was never in a conflict loop, and the entity
then needed an authorized operator to release it. The defect was found while
writing ADR-0011 and recorded there as an open item.

## Decision

Only occurrences caused by a genuine repeat of the same conflict count toward
quarantine.

1. **Credit back, not defer recording.** Every counted occurrence is returned
   with a `ConflictQuarantineOccurrence` (entity scope plus a per-entity sequence
   number). When an inbound batch fails with a retry-eligible error, the
   occurrences that batch counted are credited back through the new
   `DurableConflictQuarantineLog.creditOccurrence`.
2. **Retry-eligible means what the retry machinery already means.** The test is
   `protectedRetryStopReason(error) == null`: recoverable, not `UNKNOWN`, and not
   in a category protected from automatic retry. Every genuine conflict outcome
   is `CONFLICT`-category and non-recoverable, so `Defer`, `Fail`, an unresolved
   outcome, a decision non-convergence and a quarantine block are never credited.
   A non-retryable infrastructure failure also still counts, as before.
3. **The credit is a bounded compare-and-set with its own bookkeeping.** The
   record gains `occurrenceSequence` (occurrences ever recorded, never
   decreasing), `creditableFromSequence` and `creditedSequences` (at most 64,
   ascending). A credit applies only to an occurrence in the current window that
   is not already credited, so it is idempotent per occurrence, never takes the
   count below zero, never lifts a quarantine, and cannot erase a newer window's
   occurrences after a window expiry or a release. Forgetting the oldest credit
   raises `creditableFromSequence` past it, so a replay of a forgotten credit
   still cannot double-credit. `ConflictQuarantineRecordCodec` moves to format
   version 2; pre-V1 there is no reader for version 1.
4. **Where it runs.** The inbound pull pipeline credits when preparing the batch
   returns a blocking retry-eligible error, when the batch's apply fails, and
   when the batch's checkpoint write fails. Only the failing batch is credited: a
   batch that was applied and checkpointed keeps its occurrences.
5. **Best effort, never masking.** The real error is what the pull returns. If a
   credit cannot be persisted the count stays as recorded, which is the
   conservative direction (it can only bring quarantine sooner, as before D21).
6. **Opt-in preserved.** Without a `ConflictQuarantineTracker` nothing is counted
   and nothing is credited; the fail-closed no-checkpoint-advance guarantee and
   release authorization are untouched.

## Consequences

- The occurrence that reaches the threshold quarantines immediately, before that
  attempt's later outcome is known. `threshold - 1` genuine repeats plus one
  more conflicting attempt quarantine even if that attempt would have failed
  transiently afterwards. That attempt is itself a real conflict, and a
  quarantine block is non-retryable, so it is never credited.
- A batch that fails transiently while writing its checkpoint after a successful
  apply is credited even though the applied conflict is unlikely to re-conflict,
  so the count can be one lower than the number of resolved occurrences, never
  higher.
- A crash between counting and the credit cannot credit and stays conservative.
- `Resolved`, `ResolverNotConfigured` and `ResolverNotFound` gain an optional
  `quarantineOccurrence`; `ConflictQuarantineObservation.Counted` gains
  `occurrence`; `ConflictQuarantineRecord` gains three defaulted fields. Pre-V1
  ABI change, baselines regenerated in both layouts.
- The guidance in ADR-0011 to set `occurrenceThreshold` above the retry policy's
  `maximumAttempts` is no longer needed for correctness.

## Rejected alternatives

- **Record the occurrence only when its outcome is final for the attempt.** The
  threshold check must run before resolver invocation so a quarantined entity is
  never re-resolved, which needs the count first; deferring the write would need
  a per-attempt accumulator threaded through prepare, apply and checkpoint and
  would lose occurrences on a crash. The orchestrator is also used outside the
  pipeline, where "attempt" has no meaning.
- **A single un-keyed decrement.** A retried credit after an ambiguous store
  failure could double-credit, and a late credit could erase a newer window; the
  sequence bookkeeping closes both at the cost of three record fields.
- **Raising the threshold or setting `windowMillis`** as guidance only: leaves a
  flaky store able to quarantine a healthy entity.

## Validation

Table-driven and behavioural tests cover a transient streak above the threshold
(no quarantine, then normal resolution), genuine repeats (quarantine at the
threshold), a mixed sequence, non-retryable and protected-category failures
(still counted), concurrent occurrences and credits (no lost update, never
negative, duplicate credits apply once), window expiry, release, forgotten
credits, restart survival, codec round trips and rejection of version 1, a
failing credit not masking the real error, and an end-to-end `DataLoomBuilder`
run. Reverting the pipeline credit makes eight of those tests fail.

## References

- [`docs/api/conflict-resolution-strategies.md`](../api/conflict-resolution-strategies.md), "Amendment D21"
- ADR-0011 (conflict metrics and retry integration), open item on quarantine crediting
