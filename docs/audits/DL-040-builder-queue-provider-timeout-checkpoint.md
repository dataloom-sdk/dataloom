# DL-040 Builder Queue-Provider Timeout Assembly Checkpoint

## Decision

`DataLoomBuilder` now supports an additive queue-provider timeout on
`DataLoomQueueWorkerSpec` and automatically assembles the protected queue-worker
runtime when that timeout is present.

This checkpoint advances FR-RETRY-006. It does not complete the full retry and
circuit-breaker acceptance scope.

## Implemented boundary

`DataLoomQueueWorkerSpec.queueProviderTimeout` is an optional
`SchedulingDelay`.

- `null` preserves the historical direct queue-provider path.
- `SchedulingDelay.ZERO` rejects a protected queue-provider operation before
  delegate invocation.
- a positive value applies the production cooperative provider-timeout boundary.

When configured, `DataLoomBuilder` uses one `TimeoutEnforcingQueueProvider`
instance for:

1. expired-lease recovery;
2. atomic queue acquisition;
3. completion;
4. rescheduling;
5. non-retry deferral;
6. failure/dead-letter transition; and
7. cancellation transition.

The scheduler-provider timeout in `QueueWorkerConfiguration` remains an
independent boundary and is not reused for queue operations.

## Compatibility

The original four-argument `DataLoomQueueWorkerSpec` constructor is retained
explicitly and delegates to `queueProviderTimeout = null`.

Existing applications therefore keep the pre-existing direct provider behavior
unless they select the new constructor.

The implementation adds no Android, Room, SQLite, SQLDelight, JVM-only, or Apple
storage type to the common public surface.

## Durable ambiguity

A timed-out queue mutation may already have committed before cooperative
cancellation is observed. The builder therefore inherits the protected runtime's
fail-closed behavior:

- timed-out mutations are classified `Recoverability.UNKNOWN`;
- no timed-out mutation is replayed automatically;
- acquisition is attempted at most once per processor cycle;
- each transition is invoked at most once;
- later entries are not executed after an unconfirmed transition;
- truthful counters include only confirmed transitions; and
- lease expiry, provider-defined idempotency, and expired-lease recovery remain
  the available reconciliation boundaries.

## Construction restrictions

Creating `DataLoomQueueWorkerSpec` or calling `DataLoomBuilder.build()` performs
no:

- queue-provider operation;
- clock read;
- queue acquisition;
- queue transition;
- scheduler operation;
- coroutine launch;
- identifier generation; or
- background worker start.

## Required evidence

The review branch must prove:

- the legacy constructor exposes `queueProviderTimeout == null`;
- the new constructor preserves the exact configured timeout;
- builder assembly with a zero timeout prevents queue acquisition;
- the failure is reported at `QueueProcessingFailureStage.ACQUISITION` with
  `QUEUE_PROVIDER_TIMEOUT`;
- the legacy builder path still invokes the raw queue provider once;
- exact JVM and Kotlin/Native ABI baselines include both constructors and the
  timeout property;
- external consumers compile for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`; and
- the permanent JVM, Android, and Apple validation lanes pass on one final head.

## Focused evidence completed

The temporary same-repository evidence lane completed successfully on the
review branch. It:

- applied the bounded `DataLoomBuilder` integration and regression tests;
- ran `dataloom-runtime` JVM tests and `iosSimulatorArm64Test`;
- compiled the external consumer for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- generated exact runtime and Apple JVM/Kotlin-Native ABI baselines;
- passed public ABI boundary validation;
- assembled the Apple release XCFramework;
- verified the public timeout property and builder assembly symbols; and
- removed its temporary workflow and patch helper before committing the clean
  evidence head.

The permanent pull-request, Android, and Apple lanes must still pass on the same
final review head before merge.

## Remaining work

- separately governed queue-submission timeout behavior;
- queue circuit permission and outcome recording;
- transport and storage timeout/circuit assembly;
- protocol-specific connection, request, and idle enforcement;
- safe policy-timeout handling for the synchronous `RetryPolicy` contract;
- durable workflow-start propagation across queueing, restart, and relaunch;
- KMP iOS retry/circuit persistence;
- complete retry/circuit events, metrics, structured logs, tracing, redaction,
  and correlation;
- authorized and audited manual retry, reclassification, circuit open, close,
  and reset operations; and
- multi-process, transaction-race, process-death, restart, and Book 2
  `AC-FUNC-004` evidence.
