# DL-040 Queue-Submission Timeout Checkpoint

## Decision

Queue submission now has a separately governed provider-timeout boundary for its
single `QueueProvider.enqueue` operation.

This checkpoint advances FR-RETRY-006. It does not complete the full retry and
circuit-breaker acceptance scope.

## Public assembly

The slice introduces:

- `DataLoomQueueSubmissionSpec` for builder-owned submission configuration; and
- `QueueSubmissionProviderTimeoutRuntime` for standalone protected assembly.

`DataLoomQueueSubmissionSpec.queueProviderTimeout` is optional:

- `null` preserves the historical direct enqueue path;
- `SchedulingDelay.ZERO` rejects before provider invocation; and
- a positive value applies cooperative provider-timeout cancellation.

`DataLoomBuilder.queueSubmissionEncoder(...)` remains available and preserves
its historical direct behavior. The additive
`DataLoomBuilder.queueSubmissionConfiguration(...)` path accepts the new spec.
When both setters are used, the last call is the effective immutable builder
configuration.

## Exact operation boundary

Encoding and structural correspondence validation occur before timeout
execution. Only the single `QueueProvider.enqueue` call is protected.

The timeout is not reused for:

- queue-worker recovery, acquisition, or transitions;
- scheduler delivery;
- retry policy;
- transport or storage providers;
- connection, request, or idle protocol timeouts; or
- complete-workflow execution.

## Durable ambiguity

An enqueue operation can commit before cooperative cancellation is observed.
A provider timeout is therefore not proof of rollback.

The resulting behavior is:

- error code `QUEUE_PROVIDER_TIMEOUT`;
- error category `QUEUE`;
- recoverability `UNKNOWN`;
- result `QueueSubmissionResult.QueueProviderFailure`;
- failure stage `QUEUE_PROVIDER_ENQUEUE`;
- exact stable `QueueEntryId` preserved; and
- no automatic replay, replacement identifier, or second enqueue call.

Callers must use stable identifiers and provider-defined idempotency/reconciliation
rules before deciding whether an ambiguous submission should be attempted again.

## Compatibility

- Existing `queueSubmissionEncoder(...)` source remains valid.
- The one-argument `DataLoomQueueSubmissionSpec` constructor selects a null
  timeout.
- Common public surfaces expose no Android, Room, SQLite, SQLDelight, JVM-only,
  or Apple storage type.
- Construction performs no encoding, provider call, clock read, queue mutation,
  timeout execution, coroutine launch, or identifier generation.

## Required evidence

The review branch must prove:

- direct and timeout-enabled specification construction;
- zero timeout invokes the encoder exactly once but never invokes enqueue;
- timeout failure preserves the queue-entry identifier and failure stage;
- mutating timeout recoverability is `UNKNOWN`;
- successful bounded enqueue preserves the exact provider success;
- canonical provider failures remain unchanged;
- caller cancellation propagates;
- encoding rejection bypasses the timeout clock and queue provider;
- encoded-request contract violation bypasses the timeout clock and queue
  provider;
- builder timeout configuration selects protected enqueue behavior;
- the legacy builder method still invokes the direct provider exactly once;
- exact JVM and Kotlin/Native ABI baselines contain the new public surfaces;
- external consumers compile for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`; and
- permanent pull-request, Android, and Apple validation pass on one clean final
  head.

## Focused evidence completed

The temporary same-repository evidence lane completed successfully. It:

- applied the queue-submission builder integration and documentation changes;
- ran runtime JVM tests and `iosSimulatorArm64Test`;
- compiled external consumers for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- generated exact runtime and Apple JVM/Kotlin-Native ABI baselines;
- passed public ABI boundary validation;
- assembled the Apple release XCFramework;
- verified the new submission specification, builder method, and protected
  runtime symbols; and
- removed its temporary workflow and patch helper before committing the clean
  evidence head.

A final audit-hardening test additionally proves that encoder rejection and
encoded-request validation failure return before any timeout clock read or queue
provider call. This prevents future refactoring from accidentally moving the
provider timeout around the complete submission pipeline.

The permanent pull-request, Android, and Apple lanes must pass on the same final
head before merge.

## Remaining work

- queue circuit permission and outcome recording;
- transport and storage timeout/circuit assembly;
- protocol-specific connection, request, and idle enforcement;
- safe policy-timeout handling for the synchronous `RetryPolicy` contract;
- durable workflow-start propagation across queueing, restart, and relaunch;
- KMP iOS retry/circuit persistence;
- retry/circuit events, metrics, structured logs, tracing, redaction, and
  correlation;
- authorized and audited manual retry, reclassification, circuit open, close,
  and reset operations; and
- multi-process, transaction-race, process-death, restart, and Book 2
  `AC-FUNC-004` evidence.
