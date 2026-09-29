# Fragment: `#102` conflict-through-the-strategy-facade tests and strategy doc refresh

## Proposed "Recently shipped" log entry

| Date | Entry |
|---|---|
| 2026-09-29 | `#102`: closed two of the 2026-09-28 audit's ranked backlog items (`docs/status/dl-039b-strategy-decision-matrix.md`, items 2 and 3). **Tests** (item 2): added `DataLoomBuilderStrategyConflictDetectionTest` (`dataloom-runtime` commonTest) proving that conflict detection configured through `DataLoomBuilder.conflictDetectionConfiguration` reaches a conflict that arises during a built-in strategy's own inbound-pull execution — not just the legacy `synchronize(SynchronizationRequest)` path `DataLoomBuilderConflictDetectionTest` already proved. Two positive tests (remote-first's provider-backed PULL branch, cache-first's missing-cache remote-fetch branch) each build a real `DataLoomBuilder` with a strategy profile and a conflict-detection spec together, run `synchronize(StrategySynchronizationRequest, StrategyProviderBindings)`, and assert the detector was invoked, `conflictsDetected == 1` in the pipeline's `SynchronizationSummary`, and an `UnresolvedConflictRecord` (reason `RESOLVER_NOT_CONFIGURED`) was durably recorded — the same shape the baseline test asserts. A third, negative-control test (remote-first, no `conflictDetectionConfiguration` call) proves the strategy facade does not run detection on its own and does not silently skip it once configured. All three tests were confirmed to fail-as-expected (the two positive tests failed, the negative control still passed) when the wiring that threads `InboundPullConflictDetectionConfiguration` into `DataLoomBuilder.buildStrategyPipelineRegistry` was temporarily nulled out, then passed again once reverted — no production code changed, because the wiring was already correct (`DataLoomConflictDetectionSpec`'s own KDoc already documented "affects every registered inbound-pull pipeline ... uniformly", and both `StrategySynchronizationExecutionCoordinator` and `AcceptedStrategyPlanExecutionCoordinator` already shared the same `strategyPipelineRegistry` built from it). No defect was found in this area. **Docs** (item 3): corrected `docs/strategies/README.md`'s banner and "Current repository" table/section (previously claimed only network-only and remote-first execute; all five concrete strategies now have an executor and adaptive resolves to one), the per-strategy banners and "Current repository" sections of `offline-first.md`, `cache-first.md`, `hybrid.md`, and `adaptive.md` (previously claimed execution was pending for strategies that now execute end to end in `commonTest` and, for at least one branch each, on real Android and/or iOS providers), `remote-first.md`'s banner and "Current implementation" table (previously omitted the D26 fix and the now-proven `DEFER`/fallback durable replay), and `network-only.md`'s acceptance-gate row that said platform qualification was "pending publication of this slice" (iOS proof already exists; only an Android real-provider run remains open). Every correction cites the specific evaluator/executor/test now backing it and is scoped to what `docs/status/dl-039b-strategy-decision-matrix.md` verified in source — no claim goes beyond that audit's own evidence. `docs/status/market-readiness.md` was not touched. |

## Gate row change

Row 1 (`#102` / DL-039B, six-strategy decision matrix): **unchanged percentage.**
This PR adds proof for one previously-unproven path (conflict detection
through the strategy facade for two of six strategies) and corrects
documentation to match code that already existed — it does not implement new
production behavior, close any "blocked on a human/external decision" item, or
complete the full ranked backlog. The lead should judge whether the added
proof (backlog item 2, for remote-first and cache-first) justifies a small
increase; this fragment proposes leaving the percentage unchanged pending that
judgment, consistent with how the prior D26/D27 fragment treated its own
correctness-only change.

## "Still pending" text

Update the "Still pending" list carried from the 2026-09-29 D26/D27 fragment:
item 2 ("Conflict through the strategy facade") is now **done for remote-first
and cache-first** (the two strategies named in the audit's own suggested
scope); it remains open for offline-first and hybrid's pull legs, which this
PR did not touch. Item 3 ("Refresh the stale strategy docs") is now **done**.
Renumbered remaining items from the audit's ranked backlog (list unchanged
otherwise, previously items 3-11, now effectively 3-11 minus the docs item):

1. Conflict through the strategy facade for offline-first and hybrid's pull
   legs (remote-first and cache-first are now covered) — Windows-bounded.
2. Offline-first default-profile (`reconcileWhenOnline = true`)
   admit-then-replay with real reconcile hooks — Android on Windows
   (Robolectric); iOS needs macOS CI.
3. iOS counterpart of `AndroidReferenceConsumerRemoteFirstFallbackQueue...` —
   macOS CI (cross-compilable on Windows).
4. Protected strategy facade beyond network-only/remote-first (cache-first,
   hybrid, offline-first, protected accepted-plan replay per strategy) —
   Windows-bounded.
5. Retry-then-succeed / retry-exhaustion / circuit-open-during-replay proofs
   with real providers — Android on Windows; iOS needs macOS CI.
6. Diagnostics mapping: one test per `StrategySynchronizationExecutionResult`
   kind through `strategyDiagnosticsConfiguration` — Windows-bounded.
7. Adaptive on platforms (durable-branch replay) — Android on Windows; iOS
   needs macOS CI.
8. Real-Room strategy diagnostics instrumented test (replacing mocked-DAO
   coverage) — Android emulator CI.
9. Emulator (managed-device) variants of the strategy replay proofs —
   Android emulator CI.
10. Direct (non-queue) strategy execution on real providers — Android on
    Windows; iOS needs macOS CI.

Blocked-on-human-decision items (unchanged from the audit): connectivity/
cache/provider-health evidence derivation; metered/unmetered/provider-failure
connectivity states and `LIMITED` semantics per strategy (D3); cache-freshness
ownership; adaptive's missing selection factors; offline-first's atomic
local-intent-plus-outbox; process-death proofs on iOS (and the unscoped
Android equivalent); real `BGTaskScheduler`/WorkManager wake; explicit-
unsupported/degraded platform outcome; durable-admission idempotency; CI
evidence for every "PROVEN Android/iOS" cell.
