# DL-040 retry-orchestrator scheduler timeout checkpoint

## Scope

This checkpoint records the bounded FR-RETRY-006 implementation that applies the
production coroutine provider-timeout boundary to the direct
`SynchronizationRetryOrchestrator` scheduler call.

It does not declare DL-040 or DataLoom V1 complete.

## Implemented behavior

- Existing orchestrator constructors retain their historical direct scheduler
  path and public constructor signatures.
- `SynchronizationRetryOrchestrator.withSchedulerProviderTimeout` assembles:
  - `TimeoutEnforcingSchedulerProvider`;
  - `RetryTimeoutCoordinator` configured only for `PROVIDER`;
  - `CoroutineRetryTimeoutExecutor`.
- Construction performs no provider invocation, clock read, scheduling,
  identifier generation, or coroutine launch.
- Timeout enforcement occurs only after central retry protection, policy
  evaluation, bounded hints, maximum-delay selection, and optional retry-budget
  evaluation.
- Zero timeout rejects before scheduler invocation.
- Positive timeout cancellation reaches cooperative scheduler cleanup.
- Caller cancellation propagates unchanged.
- Canonical scheduler failures and successful receipts are preserved exactly.
- Scheduler timeout returns `SCHEDULER_FAILED` with
  `SCHEDULER_PROVIDER_TIMEOUT`.
- Timeout or provider failure never exposes advanced retry-budget state.
- `RetryScheduled` remains post-acceptance only.
- Missing scheduler remains `SCHEDULER_NOT_CONFIGURED` without timeout-clock
  access.
- At most one scheduler invocation occurs per orchestration cycle.

## Compatibility

- No existing public constructor was removed or changed.
- The timeout factory is additive.
- Existing unbounded behavior is retained unless the new factory is selected.
- External consumer compilation covers JVM, `iosArm64`,
  `iosSimulatorArm64`, and `iosX64`.
- Exact JVM and Kotlin-Native ABI baselines are required on the final branch
  head.

## Safety boundary

The common coroutine timeout is cooperative. Blocking or CPU-bound scheduler
implementations without cancellation checkpoints need a platform-specific
hard-interruption adapter.

Scheduler acceptance may be ambiguous when an underlying implementation performs
side effects before cancellation but returns no receipt. Implementations must
honor stable `ScheduleId` and `ExistingSchedulePolicy` semantics so a later host
retry does not create uncontrolled duplicate schedules.

## Required validation

Before merge, the same final head must pass:

1. runtime JVM and common tests;
2. Kotlin/Native tests;
3. exact JVM and KLib ABI checks;
4. public ABI boundary validation;
5. external JVM and all supported iOS consumer compilation;
6. Android validation;
7. Apple XCFramework assembly, exported-header audit, and Swift smoke compile.

## Remaining DL-040 work

- queue-provider acquisition and transition timeout/circuit integration;
- transport/storage provider timeout and circuit assembly;
- protocol-specific connection, request, and idle boundaries;
- safe timeout handling for synchronous `RetryPolicy` evaluation;
- durable workflow-start propagation across queueing, restart, and relaunch;
- KMP iOS retry/circuit persistence;
- retry/circuit events, metrics, logs, traces, redaction, and correlation;
- authorized and audited manual retry, reclassification, and circuit
  administration;
- multi-process, high-contention, process-death, and AC-FUNC-004 qualification.
