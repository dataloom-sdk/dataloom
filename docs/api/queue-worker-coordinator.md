# Queue Worker Coordinator (DL-032)

[API reference index](./README.md)

> **Status:** Available recovery, bounded-processing, wake-up, and optional
> scheduler-provider timeout foundation. Complete retry/circuit integration and
> platform qualification remain V1 gates.

## Overview

`QueueWorkerCoordinator` is the platform-independent coordinator for one bounded
queue-worker execution cycle.

One `run()` call performs at most:

- one optional expired-lease recovery call;
- one bounded queue-processing call; and
- one scheduler wake-up call.

The coordinator does not loop, poll, or re-acquire the queue within one run.

## Package

`io.dataloom.runtime.worker`

## Public contracts

- `QueueWorkerConfiguration` — immutable scheduling, recovery, and optional
  scheduler-timeout configuration;
- `QueueWorkerRunRequest` — immutable processing and recovery request;
- `QueueWorkerWakeUpReason` — stable reason for a requested wake-up;
- `QueueWorkerWakeUpPlan` — scheduler wake-up decision;
- `QueueWorkerSchedulingResult` — scheduling outcome;
- `QueueWorkerRunResult` — terminal result of one coordinator run; and
- `QueueWorkerCoordinator` — bounded coordinator.

## `QueueWorkerConfiguration`

Construction performs no queue operation, scheduler call, timeout execution,
clock read, identifier generation, or coroutine launch.

| Property | Type | Description |
|---|---|---|
| `scheduleId` | `ScheduleId` | Stable ID forwarded to every `ScheduleRequest`. |
| `constraints` | `ScheduleConstraints` | Forwarded unchanged to the scheduler. |
| `existingSchedulePolicy` | `ExistingSchedulePolicy` | Same-ID schedule policy. |
| `continuationDelay` | `SchedulingDelay` | Delay when the acquisition batch was full. |
| `recoverExpiredLeasesBeforeProcessing` | `Boolean` | Enables one recovery call before processing. |
| `schedulerProviderTimeout` | `SchedulingDelay?` | Optional upper bound for the follow-up scheduler-provider call. |

The original five-argument construction remains source-compatible because
`schedulerProviderTimeout` defaults to `null`.

### Continuation delay

`continuationDelay` is used only when the bounded acquisition returns exactly
`maxEntries`. It is not a retry-policy delay, offline-deferral delay, or entry
availability timestamp.

### Scheduler-provider timeout

When `schedulerProviderTimeout` is non-null, the coordinator structurally wraps
the supplied `SchedulerProvider` with DataLoom's production cooperative
coroutine timeout boundary.

- `null` preserves the historical direct scheduler invocation.
- `0` prevents delegate invocation and produces the canonical recoverable error
  `SCHEDULER_PROVIDER_TIMEOUT`.
- A positive duration cancels a cooperative in-flight scheduler call at expiry.
- Caller cancellation still propagates.
- The configured duration is not reused as a connection, request, idle, policy,
  queue-processing, or complete-workflow timeout.
- Construction only creates immutable wrappers; it does not read the clock or
  invoke the scheduler.

Coroutine cancellation is cooperative. A scheduler implementation that blocks
without suspension or another cancellation checkpoint needs an explicit
platform-specific hard-interruption adapter.

## `QueueWorkerRunRequest`

| Property | Type | Description |
|---|---|---|
| `processingRequest` | `QueueProcessingRequest` | Forwarded unchanged to the queue processor. |
| `recoveryRequest` | `ExpiredLeaseRecoveryRequest?` | Required when recovery is enabled. |

When recovery is enabled and `recoveryRequest` is absent, `run()` throws
`IllegalArgumentException` before any provider operation.

## Deterministic flow

```text
QueueWorkerCoordinator.run(request)
  │
  ├─ [optional] QueueProvider.recoverExpiredLeases() exactly once
  │     ├─ Failure → RecoveryFailed; no acquisition or scheduling
  │     └─ Success → preserve exact recovery result
  │
  ├─ DurableQueueExecutionProcessor.process() exactly once
  │     ├─ Provider/contract failure → ProcessingFailed; no scheduling
  │     └─ NoWork/Processed → build wake-up plan
  │
  ├─ NoWakeUp → NotRequired
  │
  ├─ Schedule with no scheduler → SchedulerNotConfigured
  │
  └─ Schedule with scheduler
        ├─ [optional] provider timeout gate
        ├─ Success → Scheduled
        └─ Failure/timeout → SchedulerFailed
                              durable queue state is not rolled back
```

## Optional expired-lease recovery

When `recoverExpiredLeasesBeforeProcessing` is true:

- recovery is called exactly once and before acquisition;
- failure stops the cycle without acquisition or scheduling;
- zero recovered entries still permits processing; and
- the exact successful recovery result is preserved.

When disabled, recovery is never called.

## One bounded processing cycle

The coordinator calls `DurableQueueExecutionProcessor.process()` exactly once.
Acquisition, entry validation, handler execution, and durable transitions remain
owned by the processor and `QueueProvider`.

## Continuation evidence

`QueueProcessingResult.Processed` exposes:

| Field | Meaning |
|---|---|
| `acquisitionLimitReached` | Acquired count equalled `maxEntries`. |
| `earliestRescheduledAt` | Earliest successfully persisted retry availability. |
| `earliestDeferredAt` | Earliest successfully persisted non-retry deferral availability. |

Failed transitions do not contribute wake-up timestamps. A full acquisition
batch does not prove more work exists; it only justifies one bounded follow-up
request.

## Wake-up planning

| Condition | Reason | Delay |
|---|---|---|
| No continuation evidence | No wake-up | — |
| Acquisition limit only | `ACQUISITION_LIMIT_REACHED` | `continuationDelay` |
| Retry availability only | `RESCHEDULED_ENTRY_AVAILABLE` | `max(0, retryAt - now)` |
| Deferral availability only | `DEFERRED_ENTRY_AVAILABLE` | `max(0, deferredAt - now)` |
| Retry and deferral | `RETRY_AND_DEFERRAL_AVAILABLE` | Earlier availability |
| Limit plus availability | `BOTH` | Earlier candidate delay |

The clock is read at most once for availability-delay calculation. A configured
scheduler timeout does not cause a clock read when no wake-up is required. When
a scheduler call occurs, its provider-timeout coordinator reads the injected
clock once to create bounded timeout evidence.

## Scheduler call semantics

`SchedulerProvider.schedule()` is called at most once per run.

The request preserves:

- `scheduleId`;
- selected delay;
- constraints; and
- `ExistingSchedulePolicy`.

No identifier is generated and no second scheduling attempt occurs.

## Scheduler not configured

When a wake-up is required but no scheduler is supplied,
`SchedulerNotConfigured` preserves the exact `QueueWorkerWakeUpPlan.Schedule`.
No timeout wrapper is created and no clock is read for provider-timeout
execution.

## Scheduler failure or timeout after durable queue success

When scheduling returns a canonical failure or exceeds the configured provider
timeout:

- `QueueWorkerRunResult` remains `ProcessingCompleted`;
- scheduling is `SchedulerFailed`;
- the exact canonical error is preserved;
- a timeout uses `SCHEDULER_PROVIDER_TIMEOUT`;
- successful queue completion, reschedule, deferral, failure, or cancellation is
  not rolled back; and
- the coordinator does not retry scheduling within the same run.

This separation is intentional: the durable transition happened before the
scheduler side effect.

## Cancellation

Caller cancellation from recovery, processing, scheduler invocation, timeout
execution, or clock access propagates normally. It is never converted into a
structured worker result.

If cancellation occurs during scheduling after durable queue processing:

- persisted queue state remains committed;
- the scheduler's cooperative operation is cancelled;
- no automatic second schedule call occurs; and
- another host trigger may be needed.

## Existing schedule policy

`KEEP` and `REPLACE` semantics remain owned by the scheduler provider. The
coordinator forwards the configured policy unchanged.

## Concurrency boundary

The coordinator does not implement global or distributed locks, lease renewal,
parallel processing, or singleton enforcement. Atomic acquisition remains a
`QueueProvider` responsibility. Platform schedule deduplication remains a
`SchedulerProvider` responsibility.

## KMP and performance boundaries

The public configuration and coordinator remain Kotlin Multiplatform contracts.
No Android, JVM-only, or platform scheduler type is exposed.

A run performs at most one recovery call, one processing call, and one scheduler
call. The coordinator owns no scope or dispatcher and performs no polling, busy
waiting, blocking sleep, queue-wide scan, serialization, or payload copying.

## Security boundary

Timeout diagnostics are bounded and contain no queue payload, metadata value,
credential, header, checkpoint token, encryption key, personal data, exception
message, or scheduler internals.

Safe evidence includes identifiers, wake-up reason, bounded delay, processing
counts, stable error code, and result variant.

## Remaining V1 work

This slice does not complete the retry/circuit subsystem. Remaining work
includes:

- timeout and circuit assembly for queue acquisition and transitions;
- transport, storage, connection, request, idle, policy, and workflow timeout
  enforcement;
- durable workflow-start propagation across queueing and restart;
- platform-specific hard-interruption behavior where cooperative cancellation is
  insufficient;
- complete retry/circuit events and observability; and
- native Android, KMP Android, and KMP iOS end-to-end qualification.
