# DL-040 Circuit-protected Queue-worker Scheduler Checkpoint

## Decision

Queue-worker scheduling may use a separately configured circuit without
collapsing an accepted schedule into a failure when later circuit persistence is
unconfirmed.

This checkpoint advances the scheduling integration portion of FR-RETRY-007,
FR-RETRY-009, and FR-RETRY-010. It does not complete DL-040 or DataLoom V1.

## Invariants

- Queue circuit policy is never reused implicitly for the scheduler.
- Provider-bearing scheduler scopes identify the exact scheduler provider.
- Operation-bearing scopes use `scheduler.schedule`.
- Scheduler timeout is applied before scheduler circuit classification.
- A provider call occurs at most once after permission.
- No-wake-up processing accesses neither scheduler nor scheduler circuit state.
- Caller cancellation and unexpected exceptions propagate.
- Confirmed queue transitions are never rolled back after scheduling outcomes.
- Scheduler acceptance remains visible when post-execution circuit recording is
  unconfirmed.
- The worker never automatically resubmits an accepted schedule in the same
  cycle.

## Public evidence

`QueueWorkerSchedulingResult.CircuitProtected` preserves the complete
`CircuitBreakerExecutionResult<ScheduleReceipt>`, including pre-execution
rejection, provider failure/success, and the exact `CircuitBreakerRecordResult`.

## Required qualification

The review branch must prove:

- exact provider and operation scope validation;
- open-circuit and persistence-failure rejection before provider invocation;
- recoverable scheduler failure opens the selected circuit;
- zero timeout prevents delegate invocation and contributes to circuit health;
- accepted scheduling plus failed circuit recording preserves both facts;
- no-wake-up performs no scheduler/store access;
- builder configuration requires a circuit worker and valid scheduler binding;
- builder assembly is side-effect free;
- existing direct scheduling behavior remains compatible;
- external JVM and all current iOS consumers compile;
- JVM/Kotlin-Native ABI and public boundary checks pass;
- Apple XCFramework assembly succeeds; and
- permanent PR, Android, and Apple validation pass on one clean head.

## Remaining work

Transport/storage integration, protocol-specific timeouts, KMP iOS persistence,
manual retry/reclassification and circuit administration, complete
observability, multi-process/restart/contention tests, and full AC-FUNC-004
evidence remain open.
