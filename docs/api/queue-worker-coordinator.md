# Queue Worker Coordinator (DL-032)

[API reference index](./README.md)

> **Status:** Available recovery, bounded-processing, and wake-up foundation.
> Complete retry/circuit policy and platform qualification remain V1 gates.

## Overview

`QueueWorkerCoordinator` is the platform-independent coordinator that
orchestrates one complete queue-worker execution cycle.

One `run()` call performs at most:

- one optional expired-lease recovery call
- one bounded queue-processing call
- one scheduler wake-up call

The coordinator does not loop, poll, or re-acquire the queue within a single
run.

---

## Package

`io.dataloom.runtime.worker`

---

## Public contracts

- `QueueWorkerConfiguration` — immutable scheduling and recovery configuration
- `QueueWorkerRunRequest` — immutable run request carrying processing and
  optional recovery requests
- `QueueWorkerWakeUpReason` — enum describing why a wake-up is required
- `QueueWorkerWakeUpPlan` — sealed plan describing the scheduler wake-up
  decision
- `QueueWorkerSchedulingResult` — sealed result of the scheduling step
- `QueueWorkerRunResult` — sealed result of one coordinator run
- `QueueWorkerCoordinator` — coordinator class

---

## QueueWorkerConfiguration

Immutable configuration provided to the coordinator at construction. No
scheduling, clock read, or queue operation is performed during construction.

| Property | Type | Description |
|---|---|---|
| `scheduleId` | `ScheduleId` | Stable identifier forwarded to every `ScheduleRequest`. |
| `constraints` | `ScheduleConstraints` | Execution constraints forwarded to every `ScheduleRequest`. |
| `existingSchedulePolicy` | `ExistingSchedulePolicy` | Policy when a same-ID schedule already exists. |
| `continuationDelay` | `SchedulingDelay` | Delay used when the acquisition limit was reached. |
| `recoverExpiredLeasesBeforeProcessing` | `Boolean` | When `true`, recovery is called exactly once before processing. |

`continuationDelay` is used **exclusively** when the bounded acquisition
returned the maximum requested number of entries and more immediately available
work may remain. It must not be used as a retry-policy delay, offline-deferral
delay, or entry availability timestamp.

---

## QueueWorkerRunRequest

Immutable request supplied to `QueueWorkerCoordinator.run()`.

| Property | Type | Description |
|---|---|---|
| `processingRequest` | `QueueProcessingRequest` | Forwarded unchanged to `DurableQueueExecutionProcessor.process()`. |
| `recoveryRequest` | `ExpiredLeaseRecoveryRequest?` | Required when recovery is enabled. Must be null when recovery is disabled. |

When `QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing` is `true`,
`recoveryRequest` must be non-null. The coordinator throws
`IllegalArgumentException` when recovery is enabled but the request is absent.

---

## Deterministic flow

```text
QueueWorkerCoordinator.run(QueueWorkerRunRequest)
  │
  ├─ [if recoverExpiredLeasesBeforeProcessing]
  │     QueueProvider.recoverExpiredLeases() — exactly once
  │     │
  │     ├─ Failure → QueueWorkerRunResult.RecoveryFailed
  │     │             (no acquisition, no scheduling)
  │     │
  │     └─ Success → preserve QueueRecoveryResult, continue
  │
  ├─ DurableQueueExecutionProcessor.process() — exactly once
  │
  ├─ Inspect QueueProcessingResult
  │     │
  │     ├─ QueueProviderFailure / QueueContractViolation
  │     │     → QueueWorkerRunResult.ProcessingFailed (no scheduling)
  │     │
  │     ├─ NoWork → build wake-up plan (typically NoWakeUp)
  │     │
  │     └─ Processed → build wake-up plan from continuation evidence
  │
  ├─ Build QueueWorkerWakeUpPlan
  │
  ├─ [if NoWakeUp] → QueueWorkerSchedulingResult.NotRequired
  │
  ├─ [if Schedule and schedulerProvider is null]
  │     → QueueWorkerSchedulingResult.SchedulerNotConfigured
  │
  └─ SchedulerProvider.schedule() — exactly once
        │
        ├─ Success → QueueWorkerSchedulingResult.Scheduled
        └─ Failure → QueueWorkerSchedulingResult.SchedulerFailed
                      (queue state NOT rolled back)
```

---

## Optional expired-lease recovery

When `QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing` is `true`:

- `QueueProvider.recoverExpiredLeases()` is called exactly once.
- Recovery is called **before** queue acquisition.
- A recovery failure (`ProviderOperationResult.Failure`) returns
  `QueueWorkerRunResult.RecoveryFailed` immediately. No acquisition or
  scheduling follows.
- Recovery success proceeds to queue processing even when zero entries were
  recovered.
- The exact `ExpiredLeaseRecoveryResult` is preserved in
  `QueueWorkerRunResult.ProcessingCompleted.recoveryResult`.

When `recoverExpiredLeasesBeforeProcessing` is `false`:

- `QueueProvider.recoverExpiredLeases()` is never called.
- `QueueWorkerRunRequest.recoveryRequest` is not required and may be null.

---

## One bounded queue-processing cycle

The coordinator calls `DurableQueueExecutionProcessor.process()` exactly once.
It does not re-acquire, loop, or poll. All acquisition, validation, execution,
and transition logic is owned by the processor.

---

## Continuation evidence

`QueueProcessingResult.Processed` carries three continuation evidence fields:

| Field | Type | Meaning |
|---|---|---|
| `acquisitionLimitReached` | `Boolean` | Acquired count equalled `QueueAcquireRequest.maxEntries`. |
| `earliestRescheduledAt` | `DataLoomInstant?` | Earliest availability instant from **successfully** persisted reschedule transitions. |
| `earliestDeferredAt` | `DataLoomInstant?` | Earliest availability instant from **successfully** persisted non-retry deferrals. |

Failed reschedule or deferral transitions do **not** contribute a timestamp.
`acquisitionLimitReached` does not claim the queue is definitely non-empty.

---

## Wake-up planning

The coordinator builds a `QueueWorkerWakeUpPlan` from continuation evidence:

| Condition | Plan | Reason | Delay |
|---|---|---|---|
| Neither | `NoWakeUp` | — | — |
| Limit reached only | `Schedule` | `ACQUISITION_LIMIT_REACHED` | `continuationDelay` |
| Rescheduled only | `Schedule` | `RESCHEDULED_ENTRY_AVAILABLE` | `max(0, earliestRescheduledAt − now)` |
| Deferred only | `Schedule` | `DEFERRED_ENTRY_AVAILABLE` | `max(0, earliestDeferredAt − now)` |
| Retry and deferral | `Schedule` | `RETRY_AND_DEFERRAL_AVAILABLE` | Earlier entry availability |
| Limit plus retry and/or deferral | `Schedule` | `BOTH` | Earlier of continuation and entry availability |

When both conditions exist, the **earlier** of the two candidate delays is
chosen. The maximum delay is never selected. A negative delay is never used;
already-due timestamps produce `SchedulingDelay.ZERO`.

The injected `DataLoomClock` is read **at most once**, only when
retry or deferral availability evidence is present.

---

## Earliest-delay selection (BOTH case)

When `acquisitionLimitReached` and retry or deferral availability are present:

```
continuationDelayMs = configuration.continuationDelay.milliseconds
entryAvailableAt    = min(earliestRescheduledAt, earliestDeferredAt)
entryDelayMs        = max(0, entryAvailableAt − clock.now())
selectedDelay       = min(continuationDelayMs, entryDelayMs)
```

The worker wakes at the earliest time when useful work may be available.

---

## One scheduler call per run

`SchedulerProvider.schedule()` is called **at most once** per `run()`.

---

## Scheduler-not-configured behavior

When `schedulerProvider` is null and a wake-up is required:

- `SchedulerProvider.schedule()` is not called.
- `QueueWorkerSchedulingResult.SchedulerNotConfigured` is returned with the
  exact `QueueWorkerWakeUpPlan.Schedule` preserved.
- Another host trigger may be required to wake the queue worker.

---

## Scheduler failure after durable queue success

When `SchedulerProvider.schedule()` returns `ProviderOperationResult.Failure`
after successful queue processing:

- The exact `DataLoomError` is preserved in
  `QueueWorkerSchedulingResult.SchedulerFailed`.
- The result is still `QueueWorkerRunResult.ProcessingCompleted`, not
  `ProcessingFailed`.
- Durable queue transitions that already succeeded are **not** rolled back.
- Scheduling is not retried within the same run.
- Another host trigger may be required to wake the queue worker.

---

## No queue-state rollback

Durable queue transitions (completion, retry reschedule, non-retry deferral,
failure, cancellation) persist inside `DurableQueueExecutionProcessor`. They
are not reversed if a subsequent scheduler call fails, if the coordinator is
cancelled, or for any other reason.

---

## ExistingSchedulePolicy responsibility

`ExistingSchedulePolicy` semantics (KEEP, REPLACE) are applied by the
`SchedulerProvider` implementation. The coordinator forwards the policy from
`QueueWorkerConfiguration` verbatim.

---

## Queue-provider atomicity boundary

Atomic queue acquisition is performed inside `DurableQueueExecutionProcessor`
via `QueueProvider.acquire()`. The coordinator does not repeat or duplicate
this operation.

---

## Queued synchronization event behavior

`QueueWorkerCoordinator` does not emit new synchronization event variants.
Events emitted by the existing `QueuedSynchronizationExecutionHandler` flow
(DL-029/DL-030) continue normally through the processor. No additional
events are emitted at the coordinator level.

---

## Cancellation after durable processing

`CancellationException` from any provider or the clock propagates normally. It
is never converted into a structured result variant. If cancellation occurs
after durable transitions but before scheduler delivery:

- Durable queue state is not rolled back.
- Another attempt to schedule the wake-up is not made automatically.

---

## No reacquisition loop / no polling

The coordinator performs at most one acquisition cycle per `run()`. It does
not contain a processing loop, does not poll the queue, and does not hold a
coroutine scope between runs.

---

## Concurrency limitations

The coordinator does not implement:

- global worker locks
- distributed locks
- singleton enforcement
- lease heartbeats or renewal
- parallel queue processing

`QueueProvider` owns atomic queue acquisition.
`SchedulerProvider` and `ExistingSchedulePolicy` own platform-level schedule
deduplication.

Callers are responsible for host-level worker concurrency unless providers
guarantee it.

---

## KMP compatibility

All contracts use Kotlin standard-library and DataLoom API types only.
No Android, JVM-only, or platform-specific types are exposed.

---

## Performance restrictions

One coordinator run performs at most:

- one recovery call
- one queue-processing call
- one scheduler call

The coordinator avoids unbounded loops, immediate reacquisition, polling,
busy waiting, payload copying, queue-wide scans, serialization, CoroutineScope
ownership, dispatcher selection, and blocking sleep.

---

## Security restrictions

The coordinator does not expose:

- queue payloads
- synchronization request metadata values
- credentials or authorization headers
- checkpoint tokens or encryption keys
- personal data
- scheduler or provider internal state
- exception messages or stack traces

Safe diagnostics include `ScheduleId`, `QueueConsumerId`, `QueueEntryId`,
`QueueLeaseId`, wake-up reason, selected `SchedulingDelay`, processing summary
counts, `ErrorCode`, and result variant names.
