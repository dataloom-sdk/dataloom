# Fragment: `#102` per-strategy decision matrix audit

Gate touched: `#102` (DL-039B six strategy engine), dashboard row 1. Full
evidence: `docs/status/dl-039b-strategy-decision-matrix.md`.

## (a) Proposed "Recently shipped" row

| 2026-09-28 | `#102`: first per-profile decision-matrix audit (connectivity, cache, retry/circuit, conflict, cancellation, restart) for all six built-in strategies, `docs/status/dl-039b-strategy-decision-matrix.md`, checked against the exact `#102` issue text (read with `gh issue view 102`). It corrects the row's "blocked on `#101` platform parity, not on strategy-engine logic": the audit found real engine-level gaps. Three defects/gaps: remote-first `PUSH` with `UNAVAILABLE` connectivity and a non-empty `fallbackOn` produces a `[READ_LOCAL]` plan whose executor throws `IllegalArgumentException` when it asks for the unresolved transport (reproduced at executor level with a temporary test, coordinator level traced by reading); cache-first returns `Failed` when a synchronous refresh fails after the cache was served, which its own test asserts and `docs/strategies/cache-first.md` contradicts; `LIMITED` connectivity is handled inconsistently across concrete strategies and untested for all of them. Structural findings: `StrategyRuntimeEvidence` is only ever built by callers or tests (no production code derives it from `ConnectivityProvider`, cache, or health), `queueHealth` and `isBackgroundExecutionAvailable` are never read, `SCHEDULE_REFRESH` is planning-only, durable admission is not idempotent per decision, and the real Room/SQLDelight providers only check that synchronized state exists. Also adds `BuiltInSynchronizationStrategyEvaluatorTest.offlineFirstDefersLimitedAndUnknownConnectivityWithDistinctReasons` (offline-first `LIMITED`/`UNKNOWN`/`NOT_EVALUATED` deferral reasons, documented behavior that had no test). Verified from Windows: evaluator suite 26/26 under `jvmTest` and `testAndroidHostTest`; the 8 Robolectric reference-consumer classes (9 tests) pass; iOS test sources cross-compile. Not verified: any CI run, `iosTest`, Room `androidTest`, and the Android-emulator and iOS "PROVEN" cells beyond "the test exists and is wired into CI". The audit ends in a ranked 12-slice backlog and a list of items blocked on decisions or hardware. |

## (b) Gate percentage

`#102`: **unchanged at 82%.** The audit adds tests and evidence but no shipped
capability, and it lowers confidence in the planner-only claim: it found one
executor crash (D1), one spec/behavior contradiction (D2), and confirmed that
several `#102` acceptance items have no proof or no implementation (process
death between admission and transport, atomic local-intent plus outbox
admission, adaptive factors beyond four, strategy-path conflict handling, and
retry/circuit during plan replay on either platform). A downward move is
defensible, but it would penalise the gate for defects the audit only just made
visible, and D1 is a one-line evaluator fix. Suggest revisiting after backlog
slices 1, 2 and 4 land; if D1 stays open, consider 80%.

## (c) "Still pending" text

- Remove: "blocked on `#101` platform parity, not on strategy-engine logic; the
  full connectivity/cache/retry/conflict/cancellation/restart decision matrix
  audited per built-in profile".
- Add: "Decision matrix audited per profile
  (`docs/status/dl-039b-strategy-decision-matrix.md`). Open engine work: remote-first
  PUSH-under-`UNAVAILABLE` plan/executor mismatch (crash), cache-first
  refresh-failure semantics versus spec, `LIMITED`/metered connectivity policy,
  production derivation of `StrategyRuntimeEvidence` from platform providers,
  conflict handling proven through the strategy facade, retry/circuit during
  plan replay on Android and iOS, offline-first default-profile (`RECONCILE`)
  replay on real providers, iOS remote-first fallback replay, protected-facade
  coverage for cache-first/offline-first/hybrid, adaptive on platforms,
  process-death proof for offline-first, and the human decisions listed in the
  matrix doc's section 6."
- Also note for the lead: `docs/strategies/README.md` and four strategy page
  banners still describe execution as pending (backlog slice 3).
