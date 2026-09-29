# DL-040 Current Acceptance Reconciliation

Date: 2026-08-03

> **Reconciled 2026-09-28.** This page records the 2026-08-03 state. Text
> marked **Superseded 2026-09-28** was accurate when written and has since been
> overtaken by merged work; it is kept for history and each note names the
> evidence. Statements without a note were re-checked against the repository on
> 2026-09-28 and are unchanged. See
> [Update 2026-09-28](#update-2026-09-28-what-has-been-proven-since-and-what-is-still-open)
> for the full list. The per-platform matrix that motivated this update is the
> `#94` qualification audit (PR `#438`, `docs/status/dl-040-qualification-matrix.md`
> once merged); every citation below was re-read in the repository rather than
> copied from it. DL-040 is still **not acceptance complete**.

## Verdict

The production retry and circuit implementation now covers FR-RETRY-001
through FR-RETRY-012 in common code, platform persistence, administration,
runtime assembly, and bounded telemetry. DL-040 is still **not acceptance
complete** because the repository does not yet contain executable evidence for
real operating-system process termination/relaunch on every mandatory path
(Android now has it for both the circuit-breaker store, via
`AndroidProcessTerminationCircuitBreakerInstrumentedTest`, and the separate
retry-budget durable structure, via
`AndroidProcessTerminationRetryBudgetInstrumentedTest`; every path on Apple
remains open), true cross-process probe contention, or the complete
AC-FUNC-004 flow through every mandatory consumer path.

**Superseded 2026-09-28 (parts of the paragraph above).** Each of the three
named gaps now has executable evidence, with the limits stated here:

- *Apple process termination/relaunch:* the Apple Simulator jobs
  `apple-process-termination-proof` (circuit-breaker state, `#395`, merged
  2026-09-15) and `apple-retry-budget-process-termination-proof` (retry-budget
  state, `#397`, merged 2026-09-16) run `xcrun simctl terminate`, relaunch, and
  check that the pid changed and the persisted state file is byte-identical
  (`.github/workflows/apple-validation.yml`; neither job has
  `continue-on-error`). Limits: the circuit-breaker proof writes hand-built
  `CircuitBreakerState` records straight into `AppleFileCircuitBreakerStateStore`
  and does not drive `CircuitBreakerExecutionGate`
  (`AppleCircuitBreakerProcessTerminationProof` KDoc); the relaunched app
  checks that the retry-budget file exists rather than reading it through the
  queue provider; Simulator termination is a macOS process kill, not an iOS
  device kill.
- *Cross-process probe contention:* Android
  `AndroidCircuitBreakerProbeContentionInstrumentedTest` (`#346`, 2026-08-24;
  two provider classes in two `android:process` values, job
  `android-validation.yml`, which has no `continue-on-error`) and Apple job
  `apple-process-contention-proof` (`#399`, merged 2026-09-16; its
  `continue-on-error` was removed by `#400` on 2026-09-17). The losing racer
  may report `PROBE_IN_FLIGHT` or `CLOCK_REGRESSION` (both are accepted by the
  Android test since `#416` and by the Apple job's assertion script). The Apple job is intermittently red on a Simulator launch flake
  (see the update section).
- *Provider-flow AC-FUNC-004:* `AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest`
  (native Android, real `RoomCircuitBreakerStateStore`) and
  `IosReferenceConsumerRetryCircuitQualificationTest` (KMP iOS, real
  `AppleFileCircuitBreakerStateStore`), both `#369`, 2026-08-26. Retry delay in
  those tests comes from calling `SynchronizationRetryEvaluator` by hand
  between calls, and no durable queue worker is in the loop. There is no
  KMP-Android consumer (see the update section).

Still true after 2026-09-28: DL-040 is not acceptance complete (see the
"Still open" list below).

This reconciliation supersedes the retry/circuit verdict in
`DL-AUDIT-005-current-v1-conformance.md`, which predates the subsequent DL-040
implementation checkpoints. It does not supersede that audit for any other V1
domain.

## FR-RETRY-001–012 source and test mapping

| Requirement | Current implementation | Representative executable evidence | Verdict |
| --- | --- | --- | --- |
| FR-RETRY-001 — failure classification | `SynchronizationRetryEvaluator`, ordered retry protection, and provider-specific circuit classifiers centrally exclude ineligible failures before policy or protected execution. | `SynchronizationRetryEvaluatorTest`, `RetryProtectionIntegrationTest`, provider classifier tests | Implemented; final consumer-path qualification remains. |
| FR-RETRY-002 — retry strategies | `StandardRetryPolicy` provides immediate, fixed, linear, and exponential backoff while retaining the public `RetryPolicy` extension point. | `StandardRetryPolicyTest`, `StandardRetryPolicyRuntimeIntegrationTest` | Implemented. |
| FR-RETRY-003 — jitter | None, full, and equal jitter use an injected random source, bounded selection, overflow clamping, and deterministic sampling. | `StandardRetryJitterTest`, `RetryCircuitFunctionalQualificationTest` | Implemented. |
| FR-RETRY-004 — attempt and elapsed limits | `RetryBudgetConfiguration` and `RetryBudgetEvaluator` enforce maximum attempts, elapsed time, cumulative delay, and next-delay affordability; durable queue state carries the budget fields. | `RetryBudgetEvaluatorTest`, `RetryBudgetRuntimeIntegrationTest`, `RetryBudgetSchedulerIntegrationTest`, `AndroidProcessTerminationRetryBudgetInstrumentedTest` | Implemented; real process-loss qualification proved on Android, remains open on Apple. |
| FR-RETRY-005 — provider/server hints | Typed bounded hints are normalized into retry evaluation and cannot reduce the configured policy delay or exceed the configured cap. | `RetryHintEvaluatorTest`, `RetryHintRuntimeIntegrationTest`, `RetryHintSchedulerIntegrationTest` | Runtime implemented; each protocol/provider adapter must demonstrate its own header normalization. Still true 2026-09-28: only `dataloom-transport-ktor`'s `KtorTransportProvider` parses `Retry-After` into a `RetryDelayHint` (`KtorTransportProviderTest`). `dataloom-transport-retrofit`, `dataloom-transport-graphql`, and `dataloom-transport-grpc` map timeouts/errors but never produce a `RetryDelayHint` (confirmed by reading each provider and searching for `RetryDelayHint` outside the runtime/model/API modules). |
| FR-RETRY-006 — timeout separation | Independent connection, request, idle, workflow, provider, and policy boundaries are represented and assembled across transport, storage, queue, scheduler, and coroutine execution paths. | `RetryTimeoutCoordinatorTest`, `CoroutineRetryTimeoutExecutorTest`, transport timeout tests, queue/provider/workflow timeout tests | Implemented in shared/runtime layers; final platform failure-injection matrix remains. |
| FR-RETRY-007 — circuit breaker | Persisted closed/open/half-open state, thresholds/windows, generation checks, exact deadlines, operation gates, and provider/storage/transport/queue adapters are assembled into runtime and builder paths. | `CircuitBreakerCoordinatorTest`, execution-gate and runtime adapter tests, Room and Apple store tests, `AndroidProcessTerminationCircuitBreakerInstrumentedTest` | Implemented; full mandatory-path qualification remains. |
| FR-RETRY-008 — half-open probe | A durable generation-scoped probe lease permits one probe, rejects active competitors, replaces an expired lease at the exact deadline, and prevents stale completion. | `CircuitBreakerProbeLeaseRecoveryTest`, `RetryCircuitFunctionalQualificationTest`, Android and Apple functional qualification tests, `AndroidCircuitBreakerProbeContentionInstrumentedTest`, Apple job `apple-process-contention-proof` | Implemented; **superseded 2026-09-28** -- cross-process contention for the circuit's half-open probe is now proven on both Android (`#346`) and Apple Simulator (`#399`/`#400`, no `continue-on-error`), with the loser allowed to report `PROBE_IN_FLIGHT` or `CLOCK_REGRESSION`. Still open: the equivalent contention proof for a *queue lease* on a `RETRY_WAITING` entry (a different lock than the circuit probe) has no Android test at all, and its Apple counterpart (`apple-retry-budget-lease-contention-proof`) is `continue-on-error: true` with 36 green / 10 red job conclusions since 2026-09-15. |
| FR-RETRY-009 — retry/circuit persistence | Queue retry budget, circuit records, probe ownership, retry administration receipts, and circuit administration receipts are durable in Room and Apple file-backed stores. | Room instrumented/store/migration tests, Apple file-store tests, Android and Apple functional qualification tests, `AndroidProcessTerminationCircuitBreakerInstrumentedTest`, `AndroidProcessTerminationRetryBudgetInstrumentedTest` | Implemented; OS kill/relaunch evidence now covers both Android durable structures (circuit-breaker state and retry-budget state); every path on Apple remains open. |
| FR-RETRY-010 — observability | Schema-versioned signals feed bounded per-exporter queues with drop-latest overflow, time-budgeted failure isolation, fixed-cardinality metrics, structured logs/traces, correlation propagation, and redacted health snapshots. | `BoundedRetryCircuitTelemetryTest` and external consumer compilation | Implemented for retry/circuit scope. |
| FR-RETRY-011 — manual retry | Authorized, idempotent, audited retry commands preserve immutable failure/attempt history through common façade and atomic Room/Apple executors. | `RetryAdministrationCoordinatorTest`, façade tests, Room/Apple executor tests | Implemented; platform process-loss fault injection remains. |
| FR-RETRY-012 — non-retryable protection/reclassification | Automatic retry protection blocks non-retryable categories; an explicit authorized, idempotent, audited reclassification command is available through the production façade and platform executors. | Retry protection tests, administration coordinator/facade tests, Room/Apple executor tests | Implemented; platform process-loss fault injection remains. |

## AC-FUNC-004 evidence

The common reference flow now proves deterministic exponential full jitter,
two eligible failures opening the circuit, pre-invocation open rejection,
exact-deadline half-open entry, durable single-probe ownership, competing-probe
rejection, successful recovery, and subsequent closed-state execution.

Platform qualification extends that evidence as follows:

| Path | Durable boundary exercised | What is still absent |
| --- | --- | --- |
| Common runtime | Recreated coordinator and shared durable-state contract | OS process lifecycle and platform store behavior |
| Android Room | Real database close/reopen plus independent Room connections on a managed device; real application-process kill/relaunch of both the circuit-breaker store and the separate retry-budget durable structure (attempt count, retry window, cumulative delay); real cross-process circuit-probe contention (`#346`); the composed provider flow through `DataLoomBuilder`/`RoomCircuitBreakerStateStore` (`#369`) | No cross-process contention test for a `RETRY_WAITING` queue lease; no KMP-Android-specific test exists (see the update section); the composed flow drives retry delay by calling the evaluator by hand, not through a durable queue worker |
| Apple file store | Real atomic file persistence plus independently recreated stores/coordinators; real Simulator process kill/relaunch for both durable structures (`#395`, `#397`); real cross-process circuit-probe contention (`#399`/`#400`); the composed provider flow through `DataLoomBuilder`/`AppleFileCircuitBreakerStateStore` (`#369`) | The kill/relaunch proofs write state directly into the store rather than through the coordinator/gate, and do not re-drive the gate after relaunch; the retry-budget lease-contention job is `continue-on-error` and intermittent (see the update section); the composed flow has the same durable-queue-worker gap as Android |

The detailed executable scenarios are recorded in:

- `DL-040-ac-func-004-common-qualification.md`;
- `DL-040-ac-func-004-android-room-qualification.md`; and
- `DL-040-ac-func-004-apple-qualification.md`.

Each of those three checkpoint documents' own "Remaining acceptance work"
sections is now itself only partly current; see the update section below and
each document's own added note.

## Remaining blockers to close DL-040 (2026-08-03 list)

1. Add a host-controlled Android process-death/relaunch test that persists and
   verifies attempt count, elapsed budget, next eligible time, open deadline,
   probe generation, and recovery state across termination. Circuit-breaker
   state (open deadline, consecutive failures, probe generation, recovery
   state) is covered by `AndroidProcessTerminationCircuitBreakerInstrumentedTest`,
   and retry-budget state (attempt count, retry window, cumulative delay) is
   covered by `AndroidProcessTerminationRetryBudgetInstrumentedTest` -- both
   kill and relaunch a genuine second Android process via
   `ActivityManager.killBackgroundProcesses` rather than simulating a restart
   with a same-process database close/reopen; see
   `DL-040-ac-func-004-android-room-qualification.md`. This item is closed for
   Android; the equivalent Apple test-host termination/relaunch scenario
   (item 2 below) remains open.

   **Resolved 2026-09-28.** Item 2's Apple scenario now also has evidence: see
   the note directly below.
2. Add the equivalent Apple test-host termination/relaunch scenario against
   the same persisted files.

   **Resolved 2026-09-28, with a scope reduction.** `apple-process-termination-proof`
   (`#395`, circuit-breaker state) and `apple-retry-budget-process-termination-proof`
   (`#397`, retry-budget state) do exactly this on real `macos-15` CI: `xcrun
   simctl terminate` a launched Simulator app, relaunch it, and diff the
   persisted file byte-for-byte. Both jobs have run green (61/61 and 57/57 of
   the last 61/57 non-cancelled runs respectively, no `continue-on-error`).
   Neither test re-drives `CircuitBreakerExecutionGate`/`CircuitBreakerCoordinator`
   after the relaunch -- the circuit-breaker proof writes hand-built state
   directly into the store (its own KDoc names this reduction), and the
   retry-budget relaunch only checks the state file exists rather than reading
   it through `AppleFileQueueProvider`. "Terminate and relaunch the test host"
   is done; "prove the real gate behaves correctly afterward" is not (see the
   update section, item 2).
3. Exercise two real processes contending for the same half-open probe lease on
   each deployment topology that supports multi-process workers; explicitly
   document single-process-only topologies instead of simulating contention.

   **Resolved 2026-09-28 for the circuit's half-open probe specifically.**
   `AndroidCircuitBreakerProbeContentionInstrumentedTest` (`#346`) and Apple
   job `apple-process-contention-proof` (`#399`, `continue-on-error` removed by
   `#400`) both drive two genuinely separate OS/Simulator processes to race for
   one probe lease and assert exactly one winner. Still open: this item's own
   "or explicitly document single-process-only topologies instead" alternative
   was never written for the *queue*-lease lock (a different lock guarding
   `RETRY_WAITING` entries) -- no Android test exists for it, and its Apple
   proof (`apple-retry-budget-lease-contention-proof`) is `continue-on-error`
   with 10 job-conclusion failures in 46 since 2026-09-15 (see the update
   section, item 4).
4. Execute the complete AC-FUNC-004 scheduling and protected-provider flow
   through native Android, KMP Android, and KMP iOS consumer assemblies.

   **Partially resolved 2026-09-28.** `AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest`
   and `IosReferenceConsumerRetryCircuitQualificationTest` (both `#369`) drive
   the real composed `DataLoomBuilder` provider flow -- backoff/jitter, circuit
   open, rejection, half-open probe, recovery -- against the real Room and
   Apple stores on native Android and KMP iOS. Two residuals: the retry delay
   in both tests comes from calling `SynchronizationRetryEvaluator` by hand
   between calls rather than from a durable queue worker rescheduling the
   entry (item 3 in the update section); and "KMP Android" has no dedicated
   consumer module (`runtime-android-reference-consumer` is a plain
   `com.android.library`) -- since `#425` (2026-09-28) the native Android
   consumer packages the runtime's env-gated `android` KMP target, so the
   existing Android proof now runs against that variant, but whether that
   satisfies "the mandatory KMP Android consumer path" is a definition the
   release lead still needs to make (see the update section, item 6).
5. Keep the permanent common/JVM ABI, Android schema/migration/managed-device,
   and Apple ABI/XCFramework/header/Swift-smoke lanes green on the closure
   commit.

Until all five conditions are met, issue #94 must remain open and no V1-ready
claim may rely on DL-040. **As of 2026-09-28, none of the five is fully met**:
items 2 and 3 are met for their originally named scope but a related lock
(queue-lease contention) was found to need the same treatment; item 4 is met
for native Android and KMP iOS but not for the durable-queue-worker path or a
defined KMP Android consumer; item 1 was already closed for Android in
2026-08-18 and is unaffected by this update.

## Update 2026-09-28: what has been proven since, and what is still open

This update was written for the `#94` documentation-accuracy reconciliation
(`docs/status/dl-040-qualification-matrix.md`, the `#94` qualification audit,
PR `#438`). Every citation was re-read directly in the repository or in the
GitHub Actions job history on 2026-09-28/29, not copied from the audit.

**Proven since 2026-08-03** (each item also noted inline above):

1. Apple process-kill/relaunch for circuit-breaker state (`#395`, 2026-09-15)
   and retry-budget state (`#397`, 2026-09-16) -- both required (no
   `continue-on-error`) jobs in `.github/workflows/apple-validation.yml`.
2. Cross-process contention for the circuit's half-open probe on Android
   (`#346`, 2026-08-24) and Apple Simulator (`#399`/`#400`, 2026-09-16/17).
3. The composed `DataLoomBuilder` provider flow proving backoff, circuit open,
   rejection, half-open probe, and recovery on native Android
   (`AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest`) and
   KMP iOS (`IosReferenceConsumerRetryCircuitQualificationTest`), both `#369`,
   2026-08-26.
4. The KMP Android target itself was unblocked for `dataloom-core` and
   `dataloom-runtime` on 2026-09-28 (`#425`); the native Android proofs above
   now run against that `android` variant (`dataloom-runtime:testAndroidHostTest`
   in `android-validation.yml`).

**Still genuinely open** (none of these is blocked on hardware or an external
dependency; all are engineering/documentation slices):

1. No test runs the composed queue-worker -> retry-reschedule -> circuit loop
   over a real platform queue store plus a real platform circuit store, on
   either platform. `DurableQueueExecutionProcessor.reschedule` and
   `DataLoomBuilder`'s evaluator wiring exist, but a search of both reference
   consumer modules for `RETRY_WAITING`/`reschedule`/`RetryBudget` finds only
   the hand-fed evaluator calls in the provider-flow tests named above.
2. The process-kill/relaunch proofs check that raw persisted state survives; no
   test drives the real `CircuitBreakerExecutionGate` after a relaunch to
   confirm it rejects before the deadline, grants exactly one probe at the
   deadline, and recovers. On Android, `availableAt` (the retry-budget "next
   eligible time") is not asserted equal after a kill
   (`AndroidProcessTerminationRetryBudgetInstrumentedTest` asserts attempt
   number, window start, last-evaluated instant, and cumulative delay, but not
   `availableAt`); the Apple retry-budget proof covers it implicitly by
   diffing the whole state file byte-for-byte.
3. `FR-RETRY-005` provider hints are normalized by the Ktor transport only (see
   the FR-RETRY-005 table row above); Retrofit, GraphQL, and gRPC never
   produce a `RetryDelayHint`.
4. No Android test exists for cross-process contention on a queue lease (a
   `RETRY_WAITING` entry's `acquire`, guarded by a different lock than the
   circuit's probe lease); `dataloom-queue-room/src/androidTest` has only the
   in-process `concurrentConsumersDoNotAcquireTheSameEntry`
   (`RoomQueueProviderInstrumentedTest`). The Apple analog,
   `apple-retry-budget-lease-contention-proof`, exists but is
   `continue-on-error: true`, with 36 green / 10 red job conclusions in the 80
   most recent `apple-validation.yml` runs (5 green / 3 red since the job
   moved to two Simulator devices in `#423`, 2026-09-22); the 3 reds were
   7-, 23-, and 26-minute cold second-device app launches of the
   non-seeding app, not a correctness failure of the lease logic itself.
5. Criterion 8 of issue `#94` ("public docs describe shipped behavior") is
   addressed by this same reconciliation pass -- see the fourteen `docs/api/`
   pages, the three `DL-040-ac-func-004-*-qualification.md` checkpoints, and
   `docs/apple/process-termination-proof.md`/`process-contention-proof.md`,
   each updated alongside this document.
6. What "the mandatory KMP Android consumer path" means is undefined. The
   env-gated `android` KMP target now exists on `dataloom-core`/`dataloom-runtime`
   (`#425`) and the native Android reference consumer packages that variant,
   but no module has a `commonMain` that calls DataLoom and compiles for an
   `android` target the way the iOS reference consumer does for `iosMain`.
   This is a release-lead decision, not an engineering gap this document can
   close on its own.

None of the six still-open items is external -- no criterion in issue `#94`
requires physical hardware, a paid Apple account, or an unsolved toolchain
problem. That is a reason to reconsider the `QUALIFICATION BLOCKED` label (see
the `#94` fragment,
[`docs/status/fragments/2026-09-28-94-docs-reconcile.md`](../status/fragments/2026-09-28-94-docs-reconcile.md)),
not a reason to consider DL-040 closer to complete: the 76% estimate is
unchanged by this documentation-only pass.
