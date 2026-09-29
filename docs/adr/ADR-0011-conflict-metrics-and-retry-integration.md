# ADR-0011: Conflict metrics on the retry/circuit telemetry mechanism, and retry integration by existing machinery

- **Status:** Accepted (decision D20, taken by the project lead on 2026-09-21);
  implemented
- **Date:** 2026-09-21
- **Gate:** [`#95` / DL-041 conflict engine](https://github.com/dataloom-sdk/dataloom/issues/95), slice 3
- **Builds on:** the loop/non-convergence quarantine (D18) and resolver
  selection precedence (D11) slices, and the retry/circuit telemetry
  substrate documented in `docs/api/retry-circuit-telemetry.md`

> This ADR records what slice 3 shipped and why. It does not claim the V1
> conflict requirements are met; see [What is still open](#what-is-still-open).

## Context

The dashboard row for `#95` named "complete metrics and retry integration" as
open. `FR-CONFLICT-012` asked for conflict-rate, resolution-result, and
loop/quarantine metrics; `FR-CONFLICT-005` asked for retry linkage when a
resolved decision cannot be applied.

The repository already has one bounded, exporter-isolated telemetry pipeline
(`BoundedRetryCircuitTelemetry`) whose event and metric-key types are closed
by design. Retry has always been a whole-request decision made by
`SynchronizationRetryEvaluator`/`SynchronizationRetryOrchestrator` on a
terminal result, with durable budgets; the provider circuit bridge wraps each
`StorageProvider` operation.

## Decision

**1. Conflict metrics extend the existing mechanism; no second pipeline.**
`RetryCircuitTelemetrySignal` gains eight `CONFLICT_*` signals;
`RetryCircuitTelemetryEvent` and `RetryCircuitMetricKey` gain three optional
dimensions (`conflictResolverId`, `conflictUnresolvedReason`,
`conflictSelectionTier`). This continues how the same enum already grew from
retry to circuit to administration signals. Emission is by two opt-in decorators
(`ObservedSynchronizationConflictOrchestrator`,
`ObservedConflictAdministrationCoordinator`) that follow the existing
`Observed*` shape: exact delegate result, record afterwards, swallow telemetry
failures, inert when not composed. Like the retry/circuit wrappers they are not
assembled by `DataLoomBuilder`.

**2. The only unbounded-looking label is bounded by construction.** A
`ConflictResolverId` is validated only as non-blank, so it is not a safe label
by itself. It is recorded only for a `Resolved` result, which requires a lookup
in the immutable `ConflictResolverRegistry`, so the set is closed per instance.
Entity, change, conflict, and tenant IDs are never dimensions. A test drives 500
distinct entities and asserts three metric keys.

**3. The selection tier comes from shared code.** `ConflictResolverSelectionTier`
plus `ConflictResolverSelectionPolicy.matchedTier` and
`ConflictOrchestrationBindings.selectedTier` were added, and `select` and
`selectResolverId` now derive from them, so the reported tier cannot drift from
the selection actually made. Selection behavior is unchanged (the existing
selection tests pass unmodified).

**4. Retry integration adds no code; the path is proven covered.** Applying a
resolved decision is the ordinary `applyInboundChanges` call on a reshaped
batch. It is circuit-protected by the existing bridge and retried by the
existing evaluator; conflict-engine blocks are `CONFLICT`-category and are
protected from retry. An inline retry inside the pipeline was rejected: it would
exist for no other storage operation, multiply attempts against the queue's
durable retry budgets, and hold a worker lease during backoff. The behavior is
proven by tests through the real pipeline and through `DataLoomBuilder`
(retry then success, exhaustion without checkpoint advance, non-retryable stop,
open circuit never invoking the provider, recovery after the open duration).

## Consequences

- Hosts get conflict counters with the exporters, snapshot, and structured logs
  they already have; exporters receive new signals and must tolerate them.
- `RetryCircuitTelemetryEvent`, `RetryCircuitMetricKey`, and the signal enum
  change (pre-V1, additive; both ABI baselines regenerated).
- A known interaction is documented rather than changed: quarantine (D18)
  counts a replay after a transient failure, so a retry streak can consume the
  quarantine budget. Guidance: set `occurrenceThreshold` above the retry
  policy's `maximumAttempts`, or use `windowMillis`.

## What is still open

- Crediting quarantine occurrences back when an execution fails with a
  retry-eligible infrastructure error (would amend D18 and add public API).
- Builder-assembled telemetry, gauges, and a latency metric.
- Circuit protection for the queue worker `DataLoomBuilder` builds is the
  general protected-queued adoption gap, not specific to conflicts.
- The single physical transaction across storage, decision log, checkpoint,
  outbox, and audit; AC-FUNC-002; mandatory-platform qualification.
