# DL-040 queue-provider timeout checkpoint

## Scope

This checkpoint records the bounded queue-provider timeout implementation for
FR-RETRY-006 and queue-worker runtime assembly. It does not declare DL-040 or
DataLoom V1 complete.

## Implemented behavior

- Added public `TimeoutEnforcingQueueProvider`.
- Preserved the exact delegate descriptor.
- Protected lifecycle, enqueue, acquisition, lease transitions, cancellation,
  and expired-lease recovery through `RetryTimeoutKind.PROVIDER`.
- Preserved completed successes and canonical provider failures exactly.
- Propagated caller cancellation and unexpected programming failures.
- Added static, redaction-safe `QUEUE_PROVIDER_TIMEOUT` diagnostics.
- Classified read-only health timeout as recoverable.
- Classified every potentially state-changing timeout as unknown because durable
  completion cannot be proven from cancellation alone.
- Performed no automatic mutation replay.
- Added public `QueueWorkerProviderTimeoutRuntime.create(...)`.
- Used the same protected queue-provider instance for recovery, acquisition, and
  all durable transitions.
- Preserved existing direct constructors and their historical behavior.
- Preserved truthful confirmed counters and exact queue failure stages.
- Stopped later entry execution after an unconfirmed transition.
- Performed no clock read, provider call, queue mutation, scheduling, or
  coroutine launch during assembly.

## Correctness rationale

A timeout is not proof of rollback. Room, SQLite, SQLDelight, or another durable
provider may commit before cancellation is delivered. The runtime must therefore
avoid both of these incorrect claims:

1. that the mutation definitely failed; and
2. that it is safe to issue the same mutation again immediately.

`Recoverability.UNKNOWN`, one-call-per-stage behavior, lease guards, and ordinary
recovery preserve that uncertainty without losing the existing at-least-once
model.

## Focused tests

The common tests cover:

- exact descriptor and result preservation;
- zero-timeout no-invocation acquisition;
- read-only health timeout classification;
- cooperative transition cancellation and cleanup;
- exact canonical provider failure preservation;
- caller cancellation propagation;
- recovery timeout before acquisition;
- acquisition-stage timeout with zero confirmed counters;
- completion-transition timeout with truthful acquired/executed/completed
  counters;
- no automatic transition replay; and
- a successful fully bounded queue-worker cycle.

## Required qualification

Before merge, the same final head must pass:

1. runtime JVM and common tests;
2. Kotlin/Native tests;
3. exact JVM and KLib ABI checks;
4. external JVM and all supported iOS consumer compilation;
5. public ABI boundary validation;
6. Android validation and managed-device regression;
7. Apple XCFramework assembly, exported-header audit, and Swift smoke compile.

## Remaining DL-040 work

- automatic `DataLoomBuilder` queue-worker adoption;
- queue-submission timeout policy;
- queue circuit permission and recording assembly;
- transport/storage timeout and circuit integration;
- durable workflow-start propagation;
- policy timeout design for synchronous `RetryPolicy`;
- KMP iOS persistence;
- complete events, metrics, logs, traces, redaction, and audit;
- authorized manual retry/reclassification and circuit administration;
- real multi-process, transaction-race, process-death, restart, and AC-FUNC-004
  evidence.
