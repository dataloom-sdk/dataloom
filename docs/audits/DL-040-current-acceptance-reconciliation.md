# DL-040 Current Acceptance Reconciliation

Date: 2026-08-03

## Verdict

The production retry and circuit implementation now covers FR-RETRY-001
through FR-RETRY-012 in common code, platform persistence, administration,
runtime assembly, and bounded telemetry. DL-040 is still **not acceptance
complete** because the repository does not yet contain executable evidence for
real operating-system process termination/relaunch, true cross-process probe
contention, or the complete AC-FUNC-004 flow through every mandatory consumer
path.

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
| FR-RETRY-004 — attempt and elapsed limits | `RetryBudgetConfiguration` and `RetryBudgetEvaluator` enforce maximum attempts, elapsed time, cumulative delay, and next-delay affordability; durable queue state carries the budget fields. | `RetryBudgetEvaluatorTest`, `RetryBudgetRuntimeIntegrationTest`, `RetryBudgetSchedulerIntegrationTest` | Implemented; real process-loss qualification remains. |
| FR-RETRY-005 — provider/server hints | Typed bounded hints are normalized into retry evaluation and cannot reduce the configured policy delay or exceed the configured cap. | `RetryHintEvaluatorTest`, `RetryHintRuntimeIntegrationTest`, `RetryHintSchedulerIntegrationTest` | Runtime implemented; each protocol/provider adapter must demonstrate its own header normalization. |
| FR-RETRY-006 — timeout separation | Independent connection, request, idle, workflow, provider, and policy boundaries are represented and assembled across transport, storage, queue, scheduler, and coroutine execution paths. | `RetryTimeoutCoordinatorTest`, `CoroutineRetryTimeoutExecutorTest`, transport timeout tests, queue/provider/workflow timeout tests | Implemented in shared/runtime layers; final platform failure-injection matrix remains. |
| FR-RETRY-007 — circuit breaker | Persisted closed/open/half-open state, thresholds/windows, generation checks, exact deadlines, operation gates, and provider/storage/transport/queue adapters are assembled into runtime and builder paths. | `CircuitBreakerCoordinatorTest`, execution-gate and runtime adapter tests, Room and Apple store tests | Implemented; full mandatory-path qualification remains. |
| FR-RETRY-008 — half-open probe | A durable generation-scoped probe lease permits one probe, rejects active competitors, replaces an expired lease at the exact deadline, and prevents stale completion. | `CircuitBreakerProbeLeaseRecoveryTest`, `RetryCircuitFunctionalQualificationTest`, Android and Apple functional qualification tests | Implemented; true cross-process contention remains unproved. |
| FR-RETRY-009 — retry/circuit persistence | Queue retry budget, circuit records, probe ownership, retry administration receipts, and circuit administration receipts are durable in Room and Apple file-backed stores. | Room instrumented/store/migration tests, Apple file-store tests, Android and Apple functional qualification tests | Implemented; OS kill/relaunch evidence remains. |
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
| Android Room | Real database close/reopen plus independent Room connections on a managed device | Application-process kill/relaunch, genuine cross-process contention, and complete native/KMP Android provider flow |
| Apple file store | Real atomic file persistence plus independently recreated stores/coordinators | Test-host termination/relaunch, genuine cross-process contention, and complete KMP iOS provider flow |

The detailed executable scenarios are recorded in:

- `DL-040-ac-func-004-common-qualification.md`;
- `DL-040-ac-func-004-android-room-qualification.md`; and
- `DL-040-ac-func-004-apple-qualification.md`.

## Remaining blockers to close DL-040

1. Add a host-controlled Android process-death/relaunch test that persists and
   verifies attempt count, elapsed budget, next eligible time, open deadline,
   probe generation, and recovery state across termination.
2. Add the equivalent Apple test-host termination/relaunch scenario against
   the same persisted files.
3. Exercise two real processes contending for the same half-open probe lease on
   each deployment topology that supports multi-process workers; explicitly
   document single-process-only topologies instead of simulating contention.
4. Execute the complete AC-FUNC-004 scheduling and protected-provider flow
   through native Android, KMP Android, and KMP iOS consumer assemblies.
5. Keep the permanent common/JVM ABI, Android schema/migration/managed-device,
   and Apple ABI/XCFramework/header/Swift-smoke lanes green on the closure
   commit.

Until all five conditions are met, issue #94 must remain open and no V1-ready
claim may rely on DL-040.
