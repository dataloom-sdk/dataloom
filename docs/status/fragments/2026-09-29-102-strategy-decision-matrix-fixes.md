# Fragment: `#102` strategy-decision-matrix defect fixes (D26/D27)

## Proposed "Recently shipped" log entry

| Date | Entry |
|---|---|
| 2026-09-29 | `#102`: fixed the two real defects the 2026-09-28 audit found (`docs/status/dl-039b-strategy-decision-matrix.md`), recorded as ADR-0015 (D26, D27). D26: remote-first `PUSH` with connectivity `UNAVAILABLE` (or transport health `UNAVAILABLE`) and a non-empty `fallbackOn` used to plan `EXECUTE [READ_LOCAL]` requiring only `STORAGE`, which the executor could not run — it called `requireNotNull` on the unresolved transport and threw `IllegalArgumentException`. The evaluator now never plans a local-read fallback for `PUSH`: it defers to durable continuation when the profile's `unknownConnectivityPolicy = DEFER`, otherwise rejects with `CONNECTIVITY_UNAVAILABLE`. `RemoteFirstStrategyExecutor` also gained a defence-in-depth guard returning a typed `Rejected(UNSUPPORTED_PLAN)` instead of throwing for any plan whose required transport/storage provider is unresolved. D27: cache-first's synchronous refresh, run after cache state was already served, mapped a failed refresh straight to `Failed`, discarding the served cache and contradicting `docs/strategies/cache-first.md`'s documented contract ("Preserve the served result and report refresh failure/retry state separately"); the executor's own test asserted the wrong behavior. Both the executor and its test were corrected to match the doc: a failed synchronous refresh after a served cache still returns `ServedFromCache`, with the failure visible via `refreshOutput` and, when diagnostics are configured, via `StrategyDecisionEvent.outcomeDetail` (`"<cacheState>:REFRESH_FAILED:<code>"`). `StrategyQueueExecutionOutcomeMapper`'s documented queue-replay disposition for `ServedFromCache` (already routing a failed `refreshOutput` through the retry policy) was read and left unchanged. Also fixed: `docs/api/cache-first-strategy-execution.md` was stale (missing `durableQueueEntryId` in its `ServedFromCache` snippet, and silent about the refresh-failure contract) and is now corrected. No public API/ABI changed (verified with `checkKotlinAbi` in both the normal and `DATALOOM_ANDROID_BUILD=true` configurations — both pass unchanged against `main`). Eight new/changed tests (table-driven evaluator sweep, two executor-level guard tests, two coordinator-level end-to-end tests, two cache-first executor tests, one queue-mapper test, one diagnostics test) were each confirmed to fail against the pre-fix code (production changes reverted locally, failures observed, then restored) before being counted as proof. Verified on Windows: `:dataloom-runtime:jvmTest` (full module, after merging `main` at `65a90de`), `DATALOOM_ANDROID_BUILD=true :dataloom-runtime:testAndroidHostTest` (strategy/facade/queue subset), `checkKotlinAbi` both configurations, iOS main+test cross-compiled for `iosArm64`/`iosSimulatorArm64`/`iosX64`. Not verified: any Actions run, `iosTest`, Android emulator/managed-device tests, and the Android-emulator/iOS "PROVEN" cells the audit already flagged as unverifiable from Windows. |

## Gate row change

Row 1 (`#102` / DL-039B, six-strategy decision matrix): **unchanged percentage.**
This PR fixes two defects the audit found and does not close any of the
audit's "blocked on a human/external decision" items (connectivity-evidence
derivation, `LIMITED` semantics, cache-freshness ownership, adaptive factors,
offline-first atomic local intent, process-death proofs, real scheduler wake,
durable-admission idempotency) or complete any of its still-open bounded
slices (conflict-through-the-strategy-facade tests, doc banner refresh for
the other strategies, protected-facade coverage beyond network-only/remote-
first, retry-exhaustion/circuit-open replay proofs, diagnostics-mapping
tests, adaptive-on-platform tests, real-Room diagnostics, emulator variants,
direct non-queue platform execution). The audit's own percentage estimate
(~82%) already accounted for these two defects as known gaps to close; fixing
them moves the row from "~82% with two known real defects" toward "~82% with
the known defects closed and the ranked backlog otherwise unchanged" — a
correctness fix, not new coverage. The lead should judge whether closing D26/
D27 alone justifies a small increase; this fragment proposes leaving it
unchanged pending that judgment, since the bulk of the ranked backlog (items
2–12 in the audit) remains untouched.

## "Still pending" text

Add (or keep, if already present via the audit fragment): the audit's ranked
backlog items 2–12 remain open, in order:

1. Conflict through the strategy facade (remote-first, cache-first,
   offline-first, hybrid pull legs) — Windows-bounded.
2. Refresh the stale strategy docs (README banner, per-strategy banners,
   network-only acceptance table) — Windows-bounded, doc-only.
3. Offline-first default-profile (`reconcileWhenOnline = true`)
   admit-then-replay with real reconcile hooks — Android on Windows
   (Robolectric); iOS needs macOS CI.
4. iOS counterpart of `AndroidReferenceConsumerRemoteFirstFallbackQueue...` —
   macOS CI (cross-compilable on Windows).
5. Protected strategy facade beyond network-only/remote-first (cache-first,
   hybrid, offline-first, protected accepted-plan replay per strategy) —
   Windows-bounded.
6. Retry-then-succeed / retry-exhaustion / circuit-open-during-replay proofs
   with real providers — Android on Windows; iOS needs macOS CI.
7. Diagnostics mapping: one test per `StrategySynchronizationExecutionResult`
   kind through `strategyDiagnosticsConfiguration` — Windows-bounded.
8. Adaptive on platforms (durable-branch replay) — Android on Windows; iOS
   needs macOS CI.
9. Real-Room strategy diagnostics instrumented test (replacing mocked-DAO
   coverage) — Android emulator CI.
10. Emulator (managed-device) variants of the strategy replay proofs —
    Android emulator CI.
11. Direct (non-queue) strategy execution on real providers — Android on
    Windows; iOS needs macOS CI.

Blocked-on-human-decision items (unchanged from the audit, not renumbered
into the bounded list above): connectivity/cache/provider-health evidence
derivation; metered/unmetered/provider-failure connectivity states and
`LIMITED` semantics per strategy (the audit's D3, distinct from this PR's
D26/D27); cache-freshness ownership; adaptive's missing selection factors;
offline-first's atomic local-intent-plus-outbox; process-death proofs on iOS
(and the unscoped Android equivalent); real `BGTaskScheduler`/WorkManager
wake; explicit-unsupported/degraded platform outcome; durable-admission
idempotency; CI evidence for every "PROVEN Android/iOS" cell.
