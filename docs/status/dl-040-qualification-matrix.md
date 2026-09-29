# DL-040 (`#94`) qualification matrix, re-derived from the current repository

**Audience:** the release lead and anyone deciding whether `#94`'s dashboard
status and percentage still describe reality.
**Supports:** the decision to leave, or move off, `QUALIFICATION BLOCKED`.
**Describes:** current code and CI at `main` = `acc4a02` (2026-09-28), plus the
GitHub Actions history read on the same day. Status labels follow
[`docs/documentation-style.md`](../documentation-style.md): nothing here is a
"qualified" or "complete" claim.

This page is an audit. It changes no production code, no workflow, no ABI
baseline and no dashboard text. Every source, test and CI citation below was
opened and read while writing it; every CI count comes from `gh run list` /
`gh run view` output captured on 2026-09-28 (method in
[Appendix A](#appendix-a-how-the-ci-numbers-were-obtained)).

## 1. Verdict

1. **Nothing external blocks `#94`.** No acceptance criterion in the issue text
   requires physical hardware, a paid Apple account, or an unsolved toolchain
   problem. The last "structural" blockers named in the dashboard row are both
   gone: the KMP Android target was unblocked on 2026-09-19, and the Apple
   Simulator process-kill and cross-process contention mechanisms have run on
   macOS CI. `QUALIFICATION BLOCKED` describes a state that no longer exists.
   By the dashboard's own definition (`IN PROGRESS` "includes substantial
   implementations that still have unqualified release behavior") the accurate
   label is `IN PROGRESS`.
2. **The gate is still not closable**, and the reasons are engineering and
   documentation slices, all doable without hardware (section 5):
   - the composed queue-worker to retry-reschedule to circuit loop has never run
     over a real platform queue store plus a real platform circuit store on any
     platform (fake and in-memory providers only);
   - the process-kill proofs check that raw persisted state survives, but no
     test drives the real circuit gate after the relaunch (rejection, probe at
     the deadline, recovery), and `availableAt` (the "next time") is not
     asserted after a kill on Android;
   - `FR-RETRY-005` provider hints are normalized by the Ktor transport only;
     the Retrofit, GraphQL and gRPC transports never produce a
     `RetryDelayHint`;
   - Android has no cross-process contention test for queue lease acquisition,
     and the "document the single-process topologies instead" alternative the
     reconciliation audit allowed was never written;
   - criterion 8 ("public docs describe shipped behavior") is currently false:
     fourteen API pages, the `DL-040` reconciliation audit and three qualification
     checkpoints still list proven work as open (section 6).
3. **Percentage.** Nothing in this audit is new code, so the 76% is not raised.
   Nothing found shows the 76% is inflated either. See the fragment
   [`fragments/2026-09-28-94-qualification-audit.md`](fragments/2026-09-28-94-qualification-audit.md).

## 2. Acceptance text and scorecard

The authoritative text is the body of issue `#94`
(`gh issue view 94 --repo dataloom-sdk/dataloom`); `Book 2 AC-FUNC-004` itself is
not checked into this repository, so the only in-repo statement of it is the
sentence in issue `#94` ("backoff + jitter, circuit opens, attempts are rejected,
half-open probe occurs, and normal operation recovers") and the same sentence
quoted in the header comment of
`runtime-android-reference-consumer/src/androidTest/kotlin/io/dataloom/consumer/android/AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest.kt`.
`FR-RETRY-001` to `012` are listed in the issue body; the per-requirement
implementation mapping is
[`docs/audits/DL-040-current-acceptance-reconciliation.md`](../audits/DL-040-current-acceptance-reconciliation.md)
(dated 2026-08-03, stale in its platform verdicts; see section 6).

Legend used throughout this page:

| Class | Meaning |
|---|---|
| **PROVEN** | Executed in CI on a real Android emulator (Gradle Managed Device `pixel2Api35`) or a real iOS Simulator (Kotlin/Native `iosSimulatorArm64Test` or a launched Simulator app). The cell says whether the run was in-process or cross-process. |
| **UNIT** | Proven only in JVM/host unit tests, or with fake, mocked or in-memory stores or providers (including `testAndroidHostTest`, which runs on a host JVM). |
| **UNPROVEN** | No test found. Absence was checked by search, stated in the cell. |
| **BLOCKED** | Cannot be done today; the cell names the blocker. |
| `†` | KMP Android. The same Android modules and tests run; see the note under the matrix. |

| # | Issue criterion | State | Basis |
|---|---|---|---|
| 1 | `FR-RETRY-001` to `012` each mapped to implementation and tests | **Met in substance, mapping doc stale** | Every requirement has code and tests (section 4.4). The mapping table in the reconciliation audit predates the Android and Apple proofs. `FR-RETRY-005` adapter coverage is Ktor-only. |
| 2 | `AC-FUNC-004` passes | **Partial** | PROVEN through the composed provider flow on native Android and KMP iOS (`†` KMP Android). Retry delay is computed by calling the evaluator by hand between calls, and the durable queue/scheduler path is not in the loop. |
| 3 | Restart tests prove attempts, elapsed window, next time, circuit state survive process loss | **Partial** | PROVEN for persisted state on Android and Apple Simulator (both structures). Not covered: post-relaunch behavior through the real gate; `availableAt` asserted after kill (Android); state produced by the real orchestrator rather than hand-fed values. |
| 4 | Property/boundary tests: overflow, zero/negative/max, deterministic jitter, hints, timeout separation | **Met (boundary), not property-based** | Boundary and overflow tests exist (`StandardRetryPolicyTest` alone references `MAX_VALUE` 19 times). A search for `checkAll`, `forAll`, `Arb.`, `kotest` finds no property-based framework anywhere in the repository. |
| 5 | Concurrency tests prove only allowed attempts/probes execute | **Partial** | Probe: PROVEN cross-process on Android and Apple Simulator. Attempt/lease: Android in-process only; Apple cross-process job exists but is `continue-on-error` and intermittent. |
| 6 | Manual retry and reclassification authorized, idempotent, audited | **Met** | Coordinator and façade tests (UNIT) plus Room instrumented and Apple `iosTest` executor tests (PROVEN, in-process) plus the operational-event bridge tests (UNIT). |
| 7 | Metrics/logs/traces bounded-cardinality and redacted | **Met (UNIT)** | `BoundedRetryCircuitTelemetryTest` (six tests). No platform run needed for the claim as worded. |
| 8 | Public docs describe shipped behavior; nothing labeled deferred | **Not met** | Section 6 lists the stale pages. |

## 3. CI evidence, read from the logs

### 3.1 What counts as evidence

- `.github/workflows/android-validation.yml` has **no** `continue-on-error`.
  Its step "Run managed-device tests" runs
  `:dataloom-queue-room:pixel2Api35DebugAndroidTest`,
  `:dataloom-storage-room:pixel2Api35DebugAndroidTest` and
  `:runtime-android-reference-consumer:pixel2Api35DebugAndroidTest`
  (Pixel 2, API 35, AOSP, x86_64, KVM on `ubuntu-latest`).
- `.github/workflows/apple-validation.yml` has `continue-on-error: true` on
  exactly three jobs: `apple-conflict-log-contention-proof`,
  `apple-retry-budget-lease-contention-proof` and
  `apple-conflict-log-process-termination-proof` (verified by grep; the other
  jobs have none). Only the lease job is a `#94` proof.
- The repository has **no branch protection** on `main`:
  `gh api repos/dataloom-sdk/dataloom/branches/main/protection` returns 404 and
  the only ruleset (`protect-main`) has `enforcement: disabled`. "Required
  check" in the workflow comments therefore means only "a red job makes the
  workflow run red", not "a merge is blocked".
- A job-level `continue-on-error` job reports `conclusion: failure` in the jobs
  API (the lease job does, ten times below) while the run stays green, so this
  page counts **job conclusions**, not run badges. A `success` conclusion on a
  job whose final step is an `exit 1` assertion means every assertion passed.

### 3.2 Job-level history

Window: the 80 most recent completed apple-validation runs (2026-09-15 to
2026-09-28), `cancelled` runs excluded. Counts are job conclusions.

| Job (apple-validation.yml) | Non-cancelled | success | failure | `continue-on-error` |
|---|---:|---:|---:|---|
| `apple-validate` "Validate Apple targets, XCFramework, and Swift smoke test" (Gradle `build`: every `iosSimulatorArm64Test`) | 61 | 57 | 4 | no |
| `apple-process-termination-proof` (circuit breaker) | 61 | 61 | 0 | no |
| `apple-retry-budget-process-termination-proof` | 57 | 57 | 0 | no |
| `apple-process-contention-proof` (circuit-breaker probe) | 58 | 56 | 2 | no |
| `apple-retry-budget-lease-contention-proof`, all | 46 | 36 | 10 | **yes** |
| ... one Simulator device (before `dcccf28`, 2026-09-22) | 38 | 31 | 7 | yes |
| ... two Simulator devices (from `dcccf28`) | 8 | 5 | 3 | yes |

Android: `android-validation.yml`, last 100 runs (2026-09-12 to 2026-09-28):
78 success, 7 failure, 15 cancelled. The seven failures were not individually
diagnosed except the newest, `acc4a02` on `main`
(job `108917129562`): `:dataloom-runtime:checkKotlinAbi` failed, an ABI-baseline
problem unrelated to any `#94` test (PR `#432` fixes it). Because that failure
is in the step before the managed-device step, `main` at `acc4a02` has **no**
emulator run; the last emulator run on `main` is `59ee74f`.

### 3.3 Logs actually read

| Claim | Run / job | What the log shows |
|---|---|---|
| Android emulator ran every `#94` androidTest | `36397768873` job `108848139666` (`main` `59ee74f`, success) | `Starting 38 tests` / `Finished 38 tests on pixel2Api35` for `dataloom-queue-room` (38 `@Test` methods in its `androidTest` tree), `8 tests` for `dataloom-storage-room`, `3 tests` for `runtime-android-reference-consumer`, all `0 failed`. |
| Native Android consumer now uses the KMP `android` variant of the runtime | same job, managed-device step | The step's task graph contains `:dataloom-runtime:bundleAndroidMainClassesToRuntimeJar` and `:dataloom-runtime:compileAndroidMain` and no `:dataloom-runtime:jvmJar` or `compileKotlinJvm` (while `dataloom-model` and `dataloom-api` still show `jvmJar`, probably pulled by `dataloom-plugin`, which has no Android target). I did not inspect the test APK's classpath. |
| Apple Simulator Kotlin/Native tests ran | `36397768993` job `108848140793` (`main` `59ee74f`) | Tasks `:dataloom-runtime:iosSimulatorArm64Test`, `:runtime-ios-reference-consumer:iosSimulatorArm64Test`, `:dataloom-scheduler-bgtask:iosSimulatorArm64Test` and 16 others executed (not `SKIPPED`, not `NO-SOURCE`). Three (`dataloom-apple`, `dataloom-storage-file`, `runtime-external-consumer`) show `SKIPPED`. Individual test names are not in the log. |
| Circuit-breaker kill/relaunch is genuine | same run, job `108848140651` | `OK: circuit-breaker state survived a genuine 'xcrun simctl terminate' + relaunch`, pid before 10458, after 10848. |
| Retry-budget kill/relaunch is genuine | same run, job `108848140824` | Same message, pid 11134 to 11335. |
| Probe contention is genuine | same run, job `108848140858` | Two distinct pids (31905, 32554); app A `ALLOWED generation=1`, app B `REJECTED PROBE_IN_FLIGHT`. |
| Lease race genuine, passing | `36396500439` job `108844032964`; `36396777352` job `108844938264`; `36397748083` job `108848077400` | Exactly one `ACQUIRED retryAttempt=2 windowStartedAt=1100 lastEvaluatedAt=1200 cumulativeDelay=600` and one `NO_ELIGIBLE_ENTRY`, then the production state file was cat'd. |
| Lease race genuine on two devices, earlier | `35676388475` job `106583699637`; `35679237314` job `106592346101` | Same outcomes. |
| Lease job failing, cause | `36397768993` job `108848140867`; `36394570649` job `108837818471`; `36393989900` job `108835974336` | Winner `ACQUIRED`, the other app `TIMED_OUT_WAITING_FOR_GO_SIGNAL`. See 3.4. |

### 3.4 The known flakes, quantified

- **Slow first launch on the second Simulator.** In the lease job, the
  `xcrun simctl launch` for app B on the second device took, in the eight
  two-device runs: 12 s, 16 s, 8 s, 5 s, 117 s (all green) and 420 s, 1549 s,
  1405 s (all red; app A had already given up waiting). The dashboard's "2 to 26
  minutes" is consistent with these.
- **`#429` (20-minute go-signal window)** is on `main` (`712e3b4`). Only one
  completed run has used it (`9738f0e`, PR run `36397748083`, green), and its
  app B launch took only 117 s, so the window itself has not yet been exercised
  by a slow launch. A 1549 s launch would still exceed 1200 s. PR `#430`
  (`lead/lease-launch-order`, open) launches the cold app first; its Apple runs
  were still queued at capture time.
- **Silent first-launched app on one device.** Six of the seven one-device lease
  failures were `one or both result files never appeared within 30s`; the
  seventh (`106004500333`) was a Gradle plugin-resolution flake. The **required**
  circuit-breaker contention job (still one device) has failed twice in 58
  runs: 2026-09-19 as `CLOCK_REGRESSION` (job `105852811404`, now accepted by the
  assertion) and 2026-09-22 on `main` `dcccf28` with the same silent-app symptom
  (job `106592346412`). The two-device structure has not been applied to it.
- **`CLOCK_REGRESSION` instead of `PROBE_IN_FLIGHT`** for the loser is accepted
  in both `AndroidCircuitBreakerProbeContentionInstrumentedTest` and the Apple
  contention job (workflow line
  `if [ "$LOSER_REASON" != "PROBE_IN_FLIGHT" ] && [ "$LOSER_REASON" != "CLOCK_REGRESSION" ]`).

### 3.5 What the emulator and Simulator do and do not prove

- Android runs on one emulator image: API 35, AOSP, x86_64. "Kill" is
  `ActivityManager.killBackgroundProcesses` on a second process hosted by a test
  `ContentProvider` (`ProcessTerminationTestSupport.killAndAwaitProcessDeath`).
- Apple "kill" is `xcrun simctl terminate` of a Simulator app, which is a macOS
  process, not an iOS device process. The Apple two-process race depends on the
  Simulator not sandboxing apps against the host filesystem
  (`docs/apple/cross-process-contention-investigation.md`); the same paragraph
  records that a real-device version needs App Groups and therefore a paid
  Apple Developer Program account.

## 4. Qualification matrix

Columns: **Android** = native Android (Room stores, `dataloom-android`
wiring); **KMP Android** = `†`; **KMP iOS** = Apple file stores on the
Simulator. Each cell gives the class, the exact file and the CI job that runs it.
Job shorthands: **A** = `android-validation.yml` managed-device step (job
`108848139666` is the reference run); **S** = `apple-validation.yml`
`apple-validate` (Gradle `build`); **PT-CB** and **PT-RB** = the two Apple
termination jobs; **PC-CB** = `apple-process-contention-proof`; **PC-LEASE** =
`apple-retry-budget-lease-contention-proof`; **J** = `pr-validation.yml`
(`gradlew build` on JVM) plus `S`/`A` for the same common tests.

**`†` note on KMP Android.** `dataloom-model`, `dataloom-provider-api`, `dataloom-plugin-api`,
`dataloom-config`, `dataloom-api`, `dataloom-core` and `dataloom-runtime` now
have an env-gated (`DATALOOM_ANDROID_BUILD=true`) `android` KMP target (the
last two arrived with roll-out slice 2, `#425`). Since then
`dataloom-runtime:testAndroidHostTest` runs the shared common tests against that
variant in job A, and the managed-device task graph shows the consumer packaging
the runtime's `android` variant (3.3). The Room stores, `dataloom-android`
providers and every instrumented test are the same Android modules whether the
consumer is called "native" or "KMP". **There is no KMP-shaped consumer** (a
module whose `commonMain` calls DataLoom and compiles for an `android` target),
`runtime-android-reference-consumer` is a plain `com.android.library`, and the
header comment of the AC-FUNC-004 Android test still says the KMP Android
target is blocked, so no KMP-Android-specific test can exist. So `†` means "PROVEN as far as the Android evidence goes,
on the KMP variant since `59ee74f`"; whether that satisfies the mandatory "KMP
Android consumer path" is a definition the lead has to make (backlog item 7).

### 4.1 Durable structure A: circuit-breaker state

| Requirement | Android | KMP Android | KMP iOS |
|---|---|---|---|
| Store contract: compare-and-set, conflict, malformed row fails closed, reopen | **PROVEN** `dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/RoomCircuitBreakerStateStore.kt` by `dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/RoomCircuitBreakerStateStoreInstrumentedTest.kt` (job A, in-process). The sibling `src/test/.../RoomCircuitBreakerStateStoreTest.kt` mocks the DAO: UNIT only. | `†` | **PROVEN** `dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/retry/AppleFileCircuitBreakerStateStore.kt` by `dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/retry/AppleFileCircuitBreakerStateStoreTest.kt` (job S, in-process, real files). |
| Threshold, open, reject before deadline, exact-deadline probe, stale probe, lease expiry, time regression (`FR-RETRY-007/008`) | **UNIT** `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/CircuitBreakerCoordinator.kt` by `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/retry/CircuitBreakerCoordinatorTest.kt` (9 tests) and `CircuitBreakerProbeLeaseRecoveryTest.kt` (6), in-memory store, fake clock (jobs J, A via `testAndroidHostTest`) | **UNIT** same tests, `testAndroidHostTest` (job A) | **UNIT** same tests via `iosSimulatorArm64Test` (job S) |
| `AC-FUNC-004` through the real store, raw gate (`CircuitBreakerExecutionGate`) | **PROVEN** `RoomRetryCircuitFunctionalQualificationInstrumentedTest.kt` (job A; DB close/reopen and a second independent connection, in-process) | `†` | **PROVEN** `dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/retry/AppleFileRetryCircuitFunctionalQualificationTest.kt` (job S, in-process) |
| `AC-FUNC-004` through the composed `DataLoomBuilder` provider flow (`DataLoom.protectedSynchronization`) | **PROVEN** `runtime-android-reference-consumer/src/androidTest/kotlin/io/dataloom/consumer/android/AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest.kt` (job A; real `RoomCircuitBreakerStateStore`, fake clock, fault-injecting transport; jitter values 40 ms / 75 ms asserted). Backoff delay comes from calling `SynchronizationRetryEvaluator` by hand between calls, not from the queue. | `†` | **PROVEN** `runtime-ios-reference-consumer/src/iosTest/kotlin/io/dataloom/consumer/ios/IosReferenceConsumerRetryCircuitQualificationTest.kt` (job S; real `AppleFileCircuitBreakerStateStore`, same numbers) |
| Same flow with the durable queue and worker in the loop | **UNPROVEN** (section 4.3, row C2) | `†` UNPROVEN | **UNPROVEN** |
| Process kill and relaunch: open deadline, consecutive failures, probe generation, phase survive | **PROVEN** `AndroidProcessTerminationCircuitBreakerInstrumentedTest.kt` with `CircuitBreakerProcessTerminationContentProvider.kt` in `:circuitproof` (job A). Drives the full `CircuitBreakerExecutionGate` twice with a fake clock; kill via `killBackgroundProcesses`; asserts different pid before/after. | `†` | **PROVEN** `apple-process-termination-proof/src/iosMain/kotlin/io/dataloom/processterminationproof/AppleCircuitBreakerProcessTerminationProof.kt` driven by job **PT-CB** (61 green, 0 red). Scope reduction: writes hand-built `CircuitBreakerState` records straight into `AppleFileCircuitBreakerStateStore`, not through the gate (its own KDoc says so). The relaunched process reads via a fresh store. |
| After the relaunch, the real gate rejects before the deadline, grants one probe at the deadline, and recovers | **UNPROVEN** (only in-process reopen, above) | `†` UNPROVEN | **UNPROVEN** |
| Cross-process half-open probe contention: exactly one winner | **PROVEN** `AndroidCircuitBreakerProbeContentionInstrumentedTest.kt` with `CircuitBreakerProbeContentionContentProviderA/B` (two distinct classes, two `android:process` values; job A). Loser reason `PROBE_IN_FLIGHT` or `CLOCK_REGRESSION`. | `†` | **PROVEN** `apple-process-contention-proof/src/iosMain/kotlin/io/dataloom/processcontentionproof/AppleCircuitBreakerProbeContentionProof.kt`, job **PC-CB** (56 green, 2 red, see 3.4). Simulator-only mechanism. |
| Manual circuit administration authorized, idempotent, audited | **PROVEN** `RoomCircuitAdministrationExecutorInstrumentedTest.kt` (job A, in-process) plus UNIT `CircuitAdministrationCoordinatorTest.kt` | `†` | **PROVEN** `dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/retry/AppleFileCircuitAdministrationExecutorTest.kt` (job S, in-process) |

### 4.2 Durable structure B: retry-budget state (`QueueEntry.retryAttempt`, `QueueEntry.retryBudgetState`)

| Requirement | Android | KMP Android | KMP iOS |
|---|---|---|---|
| Evaluator: attempts, elapsed, cumulative delay, next-delay affordability, hints, overflow (`FR-RETRY-004/005`) | **UNIT** `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/RetryBudgetEvaluator.kt` by `RetryBudgetEvaluatorTest.kt` (7), `RetryBudgetRuntimeIntegrationTest.kt`, `RetryHintEvaluatorTest.kt` (6) | **UNIT** (`testAndroidHostTest`) | **UNIT** (`iosSimulatorArm64Test`) |
| Store persists reschedule and defer, preserves budget across recovery | **PROVEN** `dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/RoomQueueProvider.kt` by `RoomQueueProviderInstrumentedTest.kt` (`reschedulePersistsCanonicalErrorAndRecoveryPreservesRetryState`, `deferralAfterRetryPreservesAttemptAndRetryWaitingState`; job A, in-process) | `†` | **PROVEN** `dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleFileQueueProvider.kt` by `dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/queue/AppleFileQueueProviderRetryTest.kt` (job S, in-process) |
| Process kill and relaunch: attempt number, window start, last evaluated, cumulative delay survive | **PROVEN** `AndroidProcessTerminationRetryBudgetInstrumentedTest.kt` with `RetryBudgetProcessTerminationContentProvider.kt` in `:retrybudgetproof` (job A; real `RoomQueueProvider` enqueue/acquire/reschedule/acquire/defer). Asserts four fields and a new pid. **`availableAt` is not asserted** (post-kill acquire at exactly `availableAt` succeeds, which does not pin equality). Values are hand-fed, not produced by the orchestrator. | `†` | **PROVEN** `AppleRetryBudgetProcessTerminationProof.kt` (same directory as above) driven by job **PT-RB** (57 green, 0 red): the queue state file is diffed byte-for-byte before the kill and after the relaunch, which covers `availableAt` implicitly. The relaunched app only checks the file exists; it does not read through the queue provider. |
| Cross-process lease race on one retry-waiting entry: one `ACQUIRED`, one `NO_ELIGIBLE_ENTRY` | **UNPROVEN**: no cross-process queue-acquire test exists (search of `dataloom-queue-room/src/androidTest` for contention finds only in-process `concurrentConsumersDoNotAcquireTheSameEntry` in `RoomQueueProviderInstrumentedTest.kt`, job A, PROVEN in-process). Whether Android queue workers can be multi-process is undocumented. | `†` UNPROVEN | **PROVEN, not yet dependable**: `AppleRetryBudgetLeaseContentionProof.kt` via **PC-LEASE**, `continue-on-error: true`, 36 green / 10 red overall, 5 / 3 on two devices (3.2, 3.4). |
| Manual retry and reclassification authorized, idempotent, audited (`FR-RETRY-011/012`) | **PROVEN** `RoomRetryAdministrationExecutorInstrumentedTest.kt` (job A, in-process); UNIT `RetryAdministrationCoordinatorTest.kt` (7); UNIT bridge `RetryCircuitAdministrationOperationalEventBridgeTest.kt` | `†` | **PROVEN** `dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/retry/AppleFileRetryAdministrationExecutorTest.kt` (job S, in-process) |

### 4.3 Durable structure C: retry scheduling and the queue path

| # | Requirement | Android | KMP Android | KMP iOS |
|---|---|---|---|---|
| C1 | Orchestrator, worker, scheduler-failure and timeout logic (`FR-RETRY-006`; "scheduler failure must not corrupt durable state") | **UNIT** `SynchronizationRetryOrchestratorTest.kt`, `RetryBudgetSchedulerIntegrationTest.kt`, `CircuitBreakerQueueWorkerSchedulerCircuitTest.kt`, `DataLoomBuilderCircuitQueueWorkerTest.kt` (recording queue and scheduler providers) | **UNIT** | **UNIT** |
| C2 | Composed queue worker, failing provider, `reschedule` with a budget produced by the real evaluator, next `acquire` honoring `availableAt`, circuit-open rejection, probe, recovery, over the **real** queue store and **real** circuit store | **UNPROVEN**. `DurableQueueExecutionProcessor.kt` calls `queueProvider.reschedule(... retryBudgetState = outcome.retryBudgetState)` and `DataLoomBuilder.kt` builds the `SynchronizationRetryEvaluator`, so the path exists. A search for `RETRY_WAITING`, `reschedule` and `RetryBudget` across both reference-consumer modules finds only the manual-evaluator test in 4.1. | `†` UNPROVEN | **UNPROVEN** (same search) |
| C3 | A real OS scheduler wake-up at the retry `availableAt` | **UNIT** `dataloom-scheduler-workmanager/src/test/.../WorkManagerSchedulerProviderTest.kt`; the consumer only uses `WorkManagerTestInitHelper` | `†` | **BLOCKED** for a real `BGTaskScheduler` tick: no Simulator or device mechanism exists in this repository (`docs/apple/process-termination-proof.md`, "What remains open"). Handler logic: **UNIT** `dataloom-scheduler-bgtask/src/iosTest/.../DataLoomBackgroundTaskHandlerTest.kt` (job S). This is `#101`'s gap; `#94`'s criteria do not name it. |

### 4.4 Cross-cutting requirement mapping (`FR-RETRY-001` to `012`)

All twelve have production code and passing tests; the class column reflects
the strongest evidence. Files are under `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/`
unless noted, tests under `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/retry/`.

| FR | Code | Tests | Class and gaps |
|---|---|---|---|
| 001 classification | `SynchronizationRetryEvaluator.kt` (ordered protection) | `SynchronizationRetryEvaluatorTest`, `RetryProtectionIntegrationTest` | UNIT |
| 002 strategies | `StandardRetryPolicy.kt` | `StandardRetryPolicyTest.kt` (16 tests) | UNIT |
| 003 jitter | `RetryJitterStrategy.kt`, `StandardRetryPolicy.kt` | `StandardRetryJitterTest.kt` (14) | UNIT; also asserted through the two provider-flow tests above (PROVEN) |
| 004 limits | `RetryBudgetEvaluator.kt` | `RetryBudgetEvaluatorTest.kt`, scheduler and runtime integration tests | UNIT; persistence PROVEN (4.2) |
| 005 hints | `RetryHintEvaluator.kt` | `RetryHintEvaluatorTest.kt` | UNIT. Adapter side: only `dataloom-transport-ktor/.../KtorTransportProvider.kt` parses `Retry-After` (test in `KtorTransportProviderTest.kt`). A repository-wide search for `RetryDelayHint` outside the runtime, model and API modules finds only Ktor and one consumer probe; `dataloom-transport-retrofit`, `dataloom-transport-graphql` and `dataloom-transport-grpc` never produce a hint. |
| 006 timeout separation | `RetryTimeoutCoordinator.kt` and the transport/queue/workflow timeout assemblies | `RetryTimeoutCoordinatorTest.kt` (6), `CoroutineRetryTimeoutExecutorTest.kt`, per-provider timeout tests | UNIT. No platform failure-injection matrix. |
| 007 circuit | `CircuitBreakerCoordinator.kt`, `CircuitBreakerExecutionGate.kt` | see 4.1 | Mixed, see 4.1 |
| 008 half-open probe | same | `CircuitBreakerProbeLeaseRecoveryTest.kt` | UNIT in-process; PROVEN cross-process (4.1) |
| 009 persistence | Room and Apple stores | see 4.1 and 4.2 | PROVEN for state; see 4.3 for the loop |
| 010 observability | `dataloom-runtime/.../observation/retry/` | `BoundedRetryCircuitTelemetryTest.kt` (6) | UNIT |
| 011 manual retry | `RetryAdministrationCoordinator.kt` and the platform executors | see 4.2 | PROVEN in-process |
| 012 reclassification | same | same | PROVEN in-process |

## 5. What still blocks, what needs hardware, what can still be done

### 5.1 Genuinely blocking a move off the current status

None of these is external. In order of how directly they gate the label:

1. **Criterion 8, stale public documentation** (section 6). The issue says no V1
   feature may remain labeled deferred; today proven work is labeled open.
2. **Criterion 2 residual:** the composed durable retry loop (row C2) and the
   KMP Android definition.
3. **Criterion 3 residual:** post-relaunch behavior through the real gate,
   `availableAt` after kill on Android, values from the real orchestrator.
4. **Criterion 5 residual:** Android cross-process lease contention (or the
   documented single-process statement), and the Apple lease job still being
   non-required and intermittent.
5. **`FR-RETRY-005` adapters** other than Ktor.

### 5.2 Only missing physical-device evidence

**None is required by the text of `#94`.** Hardware would only raise fidelity:
a real iPhone kill (jetsam and background suspension semantics, not
`simctl terminate`), a real Android device (OEM task killers, Doze, `am force-stop`
versus `killBackgroundProcesses`), and real-device cross-process contention on
iOS (needs App Groups and a paid Apple Developer Program account, per
`docs/apple/cross-process-contention-investigation.md`). The project-level
physical-device requirement lives under `#101` and the release gate `#102`, not
here.

### 5.3 Engineering slices that can still be done, ranked

"Windows suffices" means the code can be written, compiled and, where marked,
unit-tested on the Windows host. Anything that needs a run on an emulator or
Simulator still needs CI, which the author cannot observe from Windows.

| Rank | Slice | Files (new or touched) | Proof | Windows |
|---:|---|---|---|---|
| 1 | Reconcile the docs to what is proven (criterion 8); write the "single-process topologies" statement the reconciliation audit item 3 allows instead of contention tests | `docs/audits/DL-040-current-acceptance-reconciliation.md`, the three `docs/audits/DL-040-ac-func-004-*-qualification.md`, the API pages listed in 6, `docs/apple/process-termination-proof.md` and `process-contention-proof.md` "What remains open"; the header comment of the two AC-FUNC-004 provider-flow tests | Review only | Yes, fully |
| 2 | Real-store retry loop (C2): drive `DataLoom` with a circuit-aware queue worker over `RoomQueueProvider` plus `RoomCircuitBreakerStateStore` (Android) and `AppleFileQueueProvider` plus `AppleFileCircuitBreakerStateStore` (iOS), failing transport, fake clock: assert persisted `retryAttempt`/`retryBudgetState`, `availableAt` honored by the next `acquire`, circuit-open rejection before the transport, probe, recovery | New androidTest in `runtime-android-reference-consumer` (or `dataloom-queue-room`), new iosTest in `runtime-ios-reference-consumer`; no production change expected | Job A and job S | Author and compile (`compileDebugAndroidTestKotlin`, `compileTestKotlinIosSimulatorArm64` with `-Pdataloom.appleKlibCrossCompile=true`); run in CI. Use `runTest` only because the clock is fake. |
| 3 | Post-relaunch behavior and `availableAt` in the kill proofs: after the relaunch the real gate must reject before `openUntil`, grant one probe at the deadline and recover; add `availableAt` equality to the Android retry-budget test | Android: `CircuitBreakerProcessTerminationContentProvider.kt`, `CircuitBreakerProcessTerminationContract.kt`, the two `AndroidProcessTermination*InstrumentedTest.kt`. Apple: `AppleCircuitBreakerProcessTerminationProof.kt` (its KDoc names wiring the coordinator as a legitimate follow-up) and the Swift app; this changes an ABI-tracked proof module, so `updateKotlinAbi` twice per the playbook | Jobs A, PT-CB, PT-RB | Author and compile; run in CI (macOS for Apple) |
| 4 | `FR-RETRY-005` adapters: parse `Retry-After` (429/503) in the Retrofit and Apollo (GraphQL) transports and the gRPC pushback trailer, or record a decision to scope hints to Ktor | `dataloom-transport-retrofit/.../RetrofitTransportProvider.kt`, `dataloom-transport-graphql/.../ApolloErrorMapper.kt`, `dataloom-transport-grpc/.../GrpcStatusMapper.kt`, each module's tests | JVM unit tests | Yes, fully (all three modules are in the default build) |
| 5 | Android cross-process queue-lease contention test mirroring `AndroidCircuitBreakerProbeContentionInstrumentedTest`, or the documented single-process alternative (ties to rank 1) | New `RetryBudgetLeaseContention*ContentProvider*.kt` and test in `dataloom-queue-room/src/androidTest/`, manifest entries | Job A | Author and compile; run in CI |
| 6 | Make the Apple lease job dependable, then drop `continue-on-error`: merge PR `#430`, collect consecutive greens, and give the required circuit-breaker contention job the same two-device layout | `.github/workflows/apple-validation.yml` (out of scope for this audit) | macOS CI only | Review only |
| 7 | Decide what "KMP Android consumer" means; if a KMP-shaped consumer is required, add one (belongs with the reference-apps row more than with `#94`) | new module, decision by the lead | Job A | Author and compile |

## 6. Stale documentation found

Each of these still describes as open something section 4 shows proven, or
still says the KMP Android target is blocked:

- `docs/audits/DL-040-current-acceptance-reconciliation.md` (dated 2026-08-03):
  verdict says "every path on Apple remains open" and lists Apple kill/relaunch
  and cross-process contention as blockers 2 and 3.
- `docs/audits/DL-040-ac-func-004-android-room-qualification.md`,
  `-apple-qualification.md`, `-common-qualification.md`: "Remaining acceptance
  work" sections written before the kill, contention and provider-flow proofs.
- `docs/apple/process-termination-proof.md` "What remains open" (says retry-budget
  contention is "still fully open" and the contention proof unconfirmed) and
  `docs/apple/process-contention-proof.md` ("Deliberately out of scope": retry-budget
  contention not attempted).
- API pages whose "remaining" lists still name multi-process, process-death or
  `AC-FUNC-004` qualification as open: `builder-circuit-queue-worker.md`,
  `builder-provider-protection.md`, `circuit-administration.md`,
  `circuit-breaker.md`, `circuit-queue-processing.md`, `circuit-queue-submission.md`,
  `circuit-queue-worker-scheduler.md`, `circuit-queue-worker.md`,
  `provider-circuit-protection-runtime.md`, `provider-protected-pipeline.md`,
  `provider-protected-strategy-execution.md`, `queue-circuit-operation-adapter.md`,
  `retry-administration.md`, `storage-transport-circuit-adapters.md`
  (all under `docs/api/`).
- The header comment of `AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest.kt`
  ("an explicit KMP-aware Android target is confirmed blocked") and the matching
  paragraph in `IosReferenceConsumerRetryCircuitQualificationTest.kt` (which also
  names a test class, `AndroidReferenceConsumerRetryCircuitQualificationRobolectricTest`,
  that does not exist; the Android test is the instrumented one).
- `docs/status/market-readiness.md`, row `| 3 |`: the row has one cell fewer than
  the table header (7 pipe-separated fields where every other row has 8), so
  there is no "Still pending" cell at all; the pending clause is appended to the
  tail of "Finished on `main`". The "Est. completion" rule (derive from Finished
  versus Still pending) cannot be applied to this row as written. The lead owns
  that file; this page only records the finding.

## Appendix A: how the CI numbers were obtained

All read-only, 2026-09-28, from a Windows host with the `gh` CLI:

- `gh run list --workflow=apple-validation.yml --limit 200 --json databaseId,event,headBranch,headSha,conclusion,status,createdAt`
  then `gh run view <id> --json jobs` for the 80 newest completed runs
  (639 job records, 2026-09-15 06:29Z onward), grouped by job name and
  conclusion, `cancelled` excluded. The one-device / two-device split for the
  lease job uses commit `dcccf28` (the first commit whose
  `apple-validation.yml` contains `DEVICE_ID_2`; `git show dd3147d:` shows zero
  matches). The run for `dd3147d` (`35676407594`) is counted one-device even
  though it started 20 seconds after the two-device PR run.
- `gh run view --job=<id> --log` for the jobs named in 3.3 and 3.4; log lines were
  searched for the assertion output, `ERROR:` lines, `simctl launch` timestamps
  and Gradle task names.
- `gh run list --workflow=android-validation.yml --limit 100`.

## Appendix B: what this audit could not verify

- Individual test names and pass counts inside the Simulator `iosSimulatorArm64Test`
  tasks (the Gradle log lists tasks, not tests); I relied on the task having run
  and the sources containing the named tests.
- That the managed-device test APK's classpath contains the runtime's `android`
  variant (inferred from the task graph only).
- The cause of six of the seven older Android CI failures.
- Anything on a physical device.
- Whether the lease job passes reliably with `#429` plus `#430` in place; the
  first post-`#430` macOS runs were still queued.
- Book 2's own wording of `AC-FUNC-004` (not in the repository).
