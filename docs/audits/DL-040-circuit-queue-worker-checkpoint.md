# DL-040 Circuit-aware Queue Worker Checkpoint

## Decision

The circuit-aware queue worker is an additive production-runtime slice that
preserves expired-lease recovery, bounded processing, and scheduling as separate
evidence boundaries.

This checkpoint advances FR-RETRY-007, FR-RETRY-008, FR-RETRY-009, and the queue
integration portion of FR-RETRY-010. It does not complete DL-040 or DataLoom V1.

## Implemented boundary

`CircuitBreakerQueueWorkerCoordinator` performs at most:

1. one optional circuit-protected expired-lease recovery;
2. one circuit-aware bounded queue-processing cycle; and
3. one follow-up scheduler call after normal processing.

`CircuitBreakerQueueWorkerRuntime` assembles one shared
`CircuitBreakerQueueOperationAdapter` for recovery, acquisition, and every
lease-guarded transition.

## Recovery acceptance rule

Processing is allowed only after:

- recovery was disabled; or
- recovery provider success plus an accepted circuit-recording result.

The worker stops before acquisition when recovery is rejected, permission state
cannot be loaded, permission contention is exhausted, the recovery provider
fails, or recovery succeeds but circuit recording is unconfirmed.

This prevents uncertain circuit state from being compounded by a second queue
operation while preserving the exact provider and record evidence.

## Processing acceptance rule

Scheduling is allowed only after a normal circuit-aware processing result:

- `NoWork`; or
- `Processed`.

The worker does not schedule after a pre-execution stop, provider failure,
unconfirmed circuit recording, or acquisition contract violation.

## Scheduler isolation

The follow-up scheduler remains a distinct boundary.

- The existing optional scheduler-provider timeout is preserved.
- Queue circuit scopes are not implicitly applied to the scheduler.
- Scheduler failure cannot roll back confirmed queue transitions.
- Caller cancellation propagates.

A separate explicitly configured scheduler-circuit assembly remains required.

## Construction and scope invariants

- Recovery provider/operation scope is validated during construction.
- Processing scopes are validated by the circuit-aware processor during
  construction.
- No provider, operation, tenant, workflow, or global fallback is inferred.
- Runtime construction performs no store access, provider operation, queue
  mutation, processing, clock read, scheduling, or coroutine launch.

## Required qualification evidence

The review branch must prove:

- recovery disabled causes no circuit/store/provider access;
- missing mandatory recovery request fails before provider access;
- open recovery circuit prevents provider invocation;
- recovery provider failure stops processing and scheduling;
- successful recovery plus unconfirmed recording stops processing;
- accepted recovery allows exactly one processing cycle;
- terminal processing prevents scheduling;
- normal processing schedules at most once from truthful continuation evidence;
- scheduler timeout and failure preserve confirmed processing evidence;
- caller cancellation propagates;
- runtime construction is side-effect free;
- external consumers compile for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI baselines contain the public worker surface;
- Apple XCFramework assembly and public-boundary validation pass; and
- permanent JVM, Android, and Apple checks pass on one clean final head.

## Known limitations and remaining work

- DataLoomBuilder does not yet expose an explicit circuit-aware queue-worker
  configuration.
- Queue-worker scheduling has timeout protection but no assembled scheduler
  circuit policy in this slice.
- Transport and storage operations are not yet assembled through circuit and
  timeout gates.
- Production KMP iOS retry/circuit persistence is absent.
- Manual retry, reclassification, circuit administration, observability, and
  AC-FUNC-004 qualification remain open.
- The complete V1 strategy, conflict, event, asset, plugin, enterprise, platform,
  publication, and release gates remain NO-GO.
