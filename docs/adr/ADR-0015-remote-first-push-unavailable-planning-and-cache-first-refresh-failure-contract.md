# ADR-0015: Remote-first PUSH-under-unavailable planning and cache-first refresh-failure contract

## Status

Accepted (decisions D26 and D27, taken by the project lead on 2026-09-28);
implemented.

## Date

2026-09-28

## Context

`docs/status/dl-039b-strategy-decision-matrix.md` (the `#102` audit) found two
real defects in the six-strategy decision engine, both verified against
current source and, for D26, reproduced with a temporary executor-level test.

### D26: remote-first PUSH under unavailable connectivity throws

`BuiltInSynchronizationStrategyEvaluator.evaluateRemoteFirst` took the "typed
local fallback" branch whenever connectivity/transport was unavailable and
`UNAVAILABLE` was in the profile's `fallbackOn` allowlist, for **every**
direction, using `localFallbackOperations(direction)`. For `PUSH` that
produces `[READ_LOCAL]`, needing only `StrategyProviderCapability.STORAGE` —
but `remoteFallbackPlan` deliberately returns no fallback plan for `PUSH`
(`fallbackOn` describes what a remote failure may fall back to reading, and a
local *read* can never stand in for a remote *write*). The coordinator
resolves only `STORAGE`, so `RemoteFirstStrategyExecutor` finds no
`SERVE_LOCAL` operation, falls into `executeProviderBackedPipeline`, and calls
`requireNotNull(providers.transportProvider)` — which throws
`IllegalArgumentException` because transport was never resolved. If a
transport happened to be present anyway, the executor would instead push
despite `UNAVAILABLE` connectivity, silently violating the "attempt remote
first" contract.

### D27: cache-first discards a served cache behind a `Failed` refresh

`CacheFirstStrategyExecutor.mapPipelineResult` mapped a failed synchronous
refresh pipeline straight to `StrategySynchronizationExecutionResult.Failed`,
discarding the cache state that had already been served. The executor's own
test (`staleCacheWithSynchronousRefreshFailurePropagatesTheFailure`) asserted
exactly that, with a comment that cache-first "has no
fallback-on-refresh-failure semantics." `docs/strategies/cache-first.md`'s
failure/fallback table already said the opposite: "Remote transient failure
after stale state was served: Preserve the served result and report refresh
failure/retry state separately." Code, test, and spec disagreed with each
other.

## Decision

### D26

The planner must never produce a plan the executor cannot execute, and
execution must never throw for a reachable connectivity/policy combination.
For a `PUSH` (a write), a local `READ` is never a valid substitute — this was
already true of `remoteFallbackPlan`'s own PUSH exclusion; the evaluator's
unavailable-connectivity branch just never special-cased direction to match.

`evaluateRemoteFirst` now branches on direction before considering
`fallbackOn`/cache state at all, for unavailable connectivity or transport
health:

- **PUSH**: never plans a local-read fallback.
  - **DEFER** to durable continuation
    (`operations = [ENQUEUE_DURABLE_WORK]`, `deferralReason =
    CONNECTIVITY_UNAVAILABLE`) when the profile opted into durable
    continuation via `unknownConnectivityPolicy = DEFER` — reusing the same
    signal remote-first already uses to promise durable behavior for unknown
    connectivity, since a profile author who wants unavailable connectivity
    to be recoverable-without-data-loss has already told us so for the
    unknown case, and unavailable is strictly the more certain case of "not
    now."
  - **REJECT** with a typed, closed-enum reason
    (`StrategyRejectionReason.CONNECTIVITY_UNAVAILABLE`) otherwise — fail
    closed, no data loss, no throw.
- **PULL / BIDIRECTIONAL**: unchanged. A local `SERVE_LOCAL` fallback remains
  valid because pull directions have a real local substitute to serve.

Every currently-proven behavior is unchanged: remote-first `UNKNOWN`/`DEFER`,
remote-first `PULL` `fallbackOn` reading local, and the Android/iOS
reference-consumer proofs (none of them drive `PUSH` +
`UNAVAILABLE` + `fallbackOn`, per the audit's "Untested" note).

As defence in depth, `RemoteFirstStrategyExecutor.execute` now also checks
that the providers a plan actually needs (`transportProvider` always;
`storageProvider` too, for any path other than a non-persisting transport-only
pull) are non-null before dispatching to a pipeline, returning
`Rejected(UNSUPPORTED_PLAN)` instead of calling `requireNotNull` and throwing.
This guards against *any* future planner defect of the same shape, not just
the one D26 fixes.

### D27

The documented contract in `docs/strategies/cache-first.md` is the
specification. `CacheFirstStrategyExecutor` and its test were the ones out of
step, and both are corrected to match the doc: serving cached data is a
success outcome for the caller even when the accompanying synchronous refresh
fails. The failure is never swallowed — it is surfaced as a typed, non-fatal
diagnostic:

- `StrategySynchronizationExecutionResult.ServedFromCache` continues to be
  returned (not `Failed`), with the failed refresh attached as
  `refreshOutput` (`StrategyTransportOutput.ProviderBacked` wrapping the
  underlying `SynchronizationResult.Failed`) — the same field that already
  carried a *successful* refresh's output, now also carrying a failed one.
- When `strategyDiagnosticsConfiguration` is set,
  `StrategySynchronizationExecutionCoordinator` records the failure in the
  durable `StrategyDecisionEvent.outcomeDetail` as
  `"<cacheState>:REFRESH_FAILED:<errorCode>"` instead of the bare cache-state
  name, so the refresh failure is visible in the existing strategy
  diagnostics channel without adding a new one.

**Queue-level disposition is unchanged.** `StrategyQueueExecutionOutcomeMapper`
already special-cased `ServedFromCache`, inspecting `refreshOutput` and
routing a `Failed` refresh through the retry policy exactly like any other
unresolved provider error — that branch's documented behavior (this is a
queue-replay concern, not a caller-facing-result concern) was read and is not
changed by this decision. A queued cache-first refresh that fails still
reschedules or terminates via the retry policy precisely as it did before;
only the *direct, synchronous* caller-facing result changed, from `Failed` to
`ServedFromCache(refreshOutput = <failed>)`.

## Consequences

- No public API shape changed. `ServedFromCache.refreshOutput` already
  accepted any `StrategyTransportOutput`, including one wrapping a failed
  `SynchronizationResult`; no new field, enum value, or type was introduced.
  `StrategyDecisionEvent.outcomeDetail` is already a free-form `String?`.
- `RemoteFirstStrategyProfile.unknownConnectivityPolicy` now has a second
  reading: it governs the PUSH-under-unavailable-connectivity branch as well
  as the already-documented unknown-connectivity branch. This is additive
  behavior on an existing field with no ABI impact.
- A caller relying on the old (bugged) behavior "unavailable-connectivity
  remote-first PUSH with `fallbackOn` silently reads local" no longer works —
  but that path only ever executed if a transport provider happened to still
  be resolved despite `RemoteFirstStrategyExecutor` never using it, which the
  evaluator's own plan (`requiredCapabilities = {STORAGE}`) never actually
  required; in practice every real caller hit the `IllegalArgumentException`
  instead. There is no production caller to migrate.
- A caller relying on the old cache-first behavior "a failed synchronous
  refresh after a served cache always returns `Failed`" now receives
  `ServedFromCache` instead and must inspect `refreshOutput` to detect the
  refresh failure. Pre-V1, this is an acceptable, documented-contract-driven
  correction (see the playbook's "pre-V1 posture": breaking behavior changes
  that align code with an already-published contract are acceptable without
  a compatibility shim).

## Rejected alternatives

- **D26: always reject PUSH under unavailable connectivity, no defer path.**
  Rejected because it would silently regress a profile that already opted
  into durable continuation for the structurally identical unknown-
  connectivity case — such a profile expects recoverable, not lossy, failure
  handling under "not now."
- **D26: give remote-first PUSH a real local-substitute operation (e.g.
  queue the write payload for later replay).** Rejected as out of scope: this
  would mean DataLoom owning an atomic local-intent-plus-outbox primitive for
  remote-first, which offline-first does not even own today (see the audit's
  finding 7) and is its own product decision, not a bug fix.
- **D27: add a new sealed outcome type distinguishing "served, refresh
  failed" from "served, refresh succeeded."** Rejected as unnecessary API
  surface: `refreshOutput`'s existing type already expresses both without a
  new variant, and the smallest safe change was preferred.
- **D27: change `docs/strategies/cache-first.md` to match the code instead of
  the reverse.** Rejected — no ADR or other doc endorses "hide the served
  result behind a failure," and the existing doc's language ("Preserve the
  served result...") reads as an intentional, specific design contract, not
  an oversight.

## Validation and release gates

- `BuiltInSynchronizationStrategyEvaluatorTest` gained a table-driven test
  (`remoteFirstUnavailableRemoteMatrixNeverPlansALocalReadForAWrite`) sweeping
  direction × (connectivity `UNAVAILABLE` / transport health `UNAVAILABLE`) ×
  `fallbackOn` (empty / `{UNAVAILABLE}`) × cache state (all five) ×
  `unknownConnectivityPolicy` (`ATTEMPT_REMOTE` / `DEFER`), asserting the
  executability invariant (an `EXECUTE` plan either serves local state or
  requires `TRANSPORT`) and that `PUSH` never plans `SERVE_LOCAL`.
- `RemoteFirstStrategyExecutorTest` gained two tests proving an
  unresolved-transport or unresolved-storage plan returns
  `Rejected(UNSUPPORTED_PLAN)` instead of throwing.
- `DataLoomBuilderDirectStrategyExecutionTest` gained end-to-end tests through
  the real coordinator/executor proving the PUSH-under-unavailable-plus-
  fallback case is rejected (not thrown) and, separately, durably deferred
  when the profile opts in.
- `CacheFirstStrategyExecutorTest` was changed to assert `ServedFromCache`
  with a failed `refreshOutput` for both the stale-refresh and
  fresh-refresh-on-hit shapes.
- `StrategyQueueExecutionOutcomeMapperTest` gained a test proving a
  `ServedFromCache` result with a failed `refreshOutput` still routes through
  the retry policy exactly as an unresolved failure would.
- `DataLoomBuilderStrategyDiagnosticsTest` gained a test proving the durable
  decision event's `outcomeDetail` encodes the refresh failure.
- Every new/changed test was confirmed to fail against the pre-fix code
  (production changes reverted locally, tests observed failing, then
  restored) before this ADR's decisions were considered proven.

## References

- `docs/status/dl-039b-strategy-decision-matrix.md` — the audit that found
  both defects (sections 3, "D1" and "D2"; the audit's own D-numbers predate
  this ADR's D26/D27 numbering, which follows the lead's cross-slice decision
  log).
- `docs/strategies/remote-first.md`, `docs/strategies/cache-first.md` — the
  strategy contracts this decision aligns the engine with.
- `docs/api/cache-first-strategy-execution.md` — updated to describe the
  corrected `ServedFromCache`-with-failed-refresh contract.
- [Strategy decision index](./README.md)
