# Strategy-decision per-attempt outcome history (`#102`)

## Status

**Shipped (2026-08-27).** A bounded slice of `DL-039B`'s durable
strategy-decision diagnostics: `DurableStrategyDecisionOutcomeHistory`, the
append-only counterpart to the existing commit-once
`DurableStrategyDecisionEventLog`. Closes this gate's own previously-named
"per-attempt outcome history" pending item (the log kept only the
first-recorded terminal outcome per decision).

## Why this exists

`DurableStrategyDecisionEventLog` keeps exactly one mutable slot per
`StrategyDecisionId`: the first-recorded terminal outcome. A later,
differing outcome for the same decision — the ordinary shape of "a caller
retried after a transient failure and it later succeeded" — is reported as
`DurableStrategyDecisionRecordOutcome.Conflict` and never persisted. That is
correct for that type's own job (a stable, queryable "what is the canonical
answer" record), but it means the durable record of a retried decision loses
every attempt except the first once a second, differing one arrives.

`DurableStrategyDecisionOutcomeHistory` is the append-only complement:
every attempt — including one that exactly repeats the previous attempt's
outcome — is retained as its own entry, oldest first, up to a configurable
`maxRetainedAttempts` (default `10`). It never rejects an attempt as a
conflict; there is no "canonical" slot to protect here, only a bounded
chronological log.

## Shape

- `StrategyDecisionOutcomeHistoryState(retainedAttempts: List<StrategyDecisionEvent>)`
  — a single scope key per `StrategyDecisionId` holding every currently
  retained attempt in one CAS-written value, the same "bounded list of
  historical records in one CAS-written value" shape
  `ConfigurationHistoryState`/`OperationalEventOutboxState`/`AssetManifestHistoryState`
  already establish, rather than one scope key per attempt (which would lose
  "what attempts exist for this decision" queryability without a separate
  index).
- `DurableStrategyDecisionOutcomeHistory.append(decisionId, event)` — unlike
  `DurableAssetManifestHistory.apply`, there is no monotonicity check: a
  decision can legitimately fail, then fail again, then succeed, in any
  order a caller's retries actually produce, so every attempt is accepted.
  Only the oldest attempt is evicted once the retention bound is exceeded.
- `StrategyDecisionOutcomeHistoryStateCodec` — delegates each retained
  attempt's own encode/decode to the existing `StrategyDecisionEventCodec`
  (joined by `\n`, with a bounded overall length), rather than re-deriving
  the same field layout a second time.
- `DurableStrategyDecisionOutcomeHistory.KeyEncoder` reuses `StrategyDecisionId`
  directly, matching `DurableStrategyDecisionEventLog.KeyEncoder`.

## Wiring

New opt-in `DataLoomBuilder.strategyDecisionOutcomeHistoryConfiguration(DataLoomStrategyDecisionOutcomeHistorySpec)`.
Deliberately mirrors `DataLoomStrategyDecisionOperationalEventOutboxSpec`'s
own "no effect unless `strategyDiagnosticsConfiguration` is also configured"
posture: a `StrategyDecisionEvent` is only ever constructed at all inside
`StrategySynchronizationExecutionCoordinator.recordDecisionEvent` when
`strategyDecisionEventLog` (backing `strategyDiagnosticsConfiguration`) is
non-null, so this spec's history always appends the exact same
already-constructed event the diagnostics log already recorded — never a
second, independently constructed one. Configuring this spec alone, without
also configuring `strategyDiagnosticsConfiguration`, has no effect.

`StrategySynchronizationExecutionCoordinator` appends to the history
immediately after `strategyDecisionEventLog.record(...)`, using the exact
same choke point (`recordDecisionEvent`) every terminal
`StrategySynchronizationExecutionResult` — including early admission
rejections — already funnels through, so every attempt at every decision is
covered by one wrapper, not duplicated per return point.

`DurableStrategyDecisionOutcomeHistory.append` reports every failure mode
(underlying store failure, exhausted contention retries) as a return value
rather than throwing — the same non-throwing contract
`DurableStrategyDecisionEventLog.record` already relies on — so a
durable-recording failure here never changes or hides the real
`StrategySynchronizationExecutionResult` the coordinator returns.

## Room adoption

The sixth real `DurableStateStore` domain adoption in this codebase
(after configuration history, policy decisions, unresolved conflicts,
strategy decisions, and asset manifest history), reusing
`RoomDurableStateStore` with zero new Room DAO/entity code — just
`StrategyDecisionOutcomeHistoryStateCodec` and
`DurableStrategyDecisionOutcomeHistory.KeyEncoder`, exactly like every prior
adoption.

## Deliberately out of scope

- No caller reads this history back during execution — same "diagnostics,
  never replay" posture as `DurableStrategyDecisionEventLog` and
  `DurableAssetManifestHistory`.
- No age-based retention (only count-based) — an asset manifest revision or
  a strategy-decision attempt is a bounded sequence tied to one identity,
  not an open-ended event stream, the same reasoning
  `DurableAssetManifestHistory`'s own KDoc gives for choosing count-only
  retention over `DurableOperationalEventOutbox`'s count/age pair.
- No independent/standalone configuration point that does not require
  `strategyDiagnosticsConfiguration` — deliberately mirrors the existing
  strategy-decision operational-event bridge's own opt-in-layering
  convention rather than inventing a new one.

## References

- `io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory` (`dataloom-api`)
- `io.dataloom.api.strategy.StrategyDecisionOutcomeHistoryStateCodec` (`dataloom-api`)
- `io.dataloom.runtime.facade.DataLoomStrategyDecisionOutcomeHistorySpec` (`dataloom-runtime`)
- `io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator.recordOutcomeHistoryAttempt`
- `docs/status/market-readiness.md`'s `#102` row
