# Queue Worker Wake-Up and Recovery Flow (DL-032)

## Purpose

This document describes the bounded runtime flow for one complete
queue-worker coordination cycle in `QueueWorkerCoordinator`.

The coordinator sits between platform scheduling triggers and
`DurableQueueExecutionProcessor`. One `run()` call:

1. Optionally recovers expired leases.
2. Drives one bounded processing cycle.
3. Plans at most one scheduler wake-up.
4. Reports a structured terminal result.

---

## Sequence overview

```mermaid
sequenceDiagram
    title Queue worker cycle
    participant Trigger
    participant Coordinator
    participant QueueProvider
    participant Processor
    participant SchedulerProvider

    Trigger->>Coordinator: run request
    Coordinator->>QueueProvider: recover expired leases
    QueueProvider-->>Coordinator: recovery result
    Coordinator->>Processor: process one batch
    Processor-->>Coordinator: processing result
    Coordinator->>Coordinator: build wake-up plan
    Coordinator->>SchedulerProvider: schedule when required
    SchedulerProvider-->>Coordinator: scheduling result
    Coordinator-->>Trigger: structured run result
```

Recovery and processing are bounded to one call. A future wake-up is scheduled
only when the processing summary provides continuation evidence.

Room and in-memory recovery preserve retry history exactly. A recovered entry
returns to `PENDING` when its attempt is null and to `RETRY_WAITING` when
attempt N exists, so process death cannot reset a retry budget.

---

## Sequence 1: Recovery and processing with no work

```text
Caller
  → QueueWorkerCoordinator.run(request)
      │
      ├─ [if recovery enabled]
      │     → QueueProvider.recoverExpiredLeases(recoveryRequest)
      │           ← ProviderOperationResult.Success(recoveredEntries=N)
      │
      ├─ → DurableQueueExecutionProcessor.process(processingRequest)
      │         → QueueProvider.acquire(acquireRequest)
      │               ← ProviderOperationResult.Success(NoEntries)
      │         ← QueueProcessingResult.NoWork
      │
      ├─ Wake-up plan: NoWakeUp
      │     (no continuation evidence, scheduler not called)
      │
      └─ ← QueueWorkerRunResult.ProcessingCompleted(
               recoveryResult = ExpiredLeaseRecoveryResult(N),
               processingResult = NoWork,
               schedulingResult = NotRequired,
           )
```

---

## Sequence 2: Acquisition-limit continuation

Triggered when the acquired entry count equals `QueueAcquireRequest.maxEntries`.

```text
Caller
  → QueueWorkerCoordinator.run(request)
      │
      ├─ → DurableQueueExecutionProcessor.process(processingRequest)
      │         → QueueProvider.acquire(acquireRequest)     [maxEntries=5]
      │               ← ProviderOperationResult.Success(Entries[5 entries])
      │         [execute and persist all 5 entries]
      │         ← QueueProcessingResult.Processed(
      │                summary.acquired=5,
      │                acquisitionLimitReached=true,
      │                earliestRescheduledAt=null,
      │                earliestDeferredAt=null,
      │            )
      │
      ├─ Wake-up plan: Schedule(
      │     reason=ACQUISITION_LIMIT_REACHED,
      │     delay=configuration.continuationDelay,
      │     scheduleId=..., constraints=..., existingSchedulePolicy=...,
      │   )
      │
      ├─ → SchedulerProvider.schedule(ScheduleRequest)
      │         ← ProviderOperationResult.Success(ScheduleReceipt)
      │
      └─ ← QueueWorkerRunResult.ProcessingCompleted(
               processingResult = Processed(...),
               schedulingResult = Scheduled(receipt, plan),
           )
```

---

## Sequence 3: Rescheduled-entry wake-up

Triggered when one or more entries were successfully persisted into a
rescheduled state.

```text
Caller
  → QueueWorkerCoordinator.run(request)
      │
      ├─ → DurableQueueExecutionProcessor.process(processingRequest)
      │         → QueueProvider.acquire(acquireRequest)     [maxEntries=5]
      │               ← ProviderOperationResult.Success(Entries[2 entries])
      │         [handler returns Reschedule for both entries]
      │         → QueueProvider.reschedule(entry1)
      │               ← ProviderOperationResult.Success(Unit)
      │         → QueueProvider.reschedule(entry2)
      │               ← ProviderOperationResult.Success(Unit)
      │         ← QueueProcessingResult.Processed(
      │                summary.acquired=2, summary.rescheduled=2,
      │                acquisitionLimitReached=false,
      │                earliestRescheduledAt=<earliest availableAt>,
      │                earliestDeferredAt=null,
      │            )
      │
      ├─ → DataLoomClock.now()    [read exactly once]
      │         ← DataLoomInstant(nowMs)
      │
      ├─ delay = max(0, earliestRescheduledAt − nowMs)
      │
      ├─ Wake-up plan: Schedule(
      │     reason=RESCHEDULED_ENTRY_AVAILABLE,
      │     delay=SchedulingDelay(delayMs),
      │     scheduleId=..., constraints=..., existingSchedulePolicy=...,
      │   )
      │
      ├─ → SchedulerProvider.schedule(ScheduleRequest)
      │         ← ProviderOperationResult.Success(ScheduleReceipt)
      │
      └─ ← QueueWorkerRunResult.ProcessingCompleted(
               schedulingResult = Scheduled(receipt, plan),
           )
```

---

## Sequence 4: Deferred-entry wake-up

Triggered when one or more entries were durably deferred because an execution
constraint was not met. Deferral does not consume retry history.

```text
Caller
  → QueueWorkerCoordinator.run(request)
      │
      ├─ → DurableQueueExecutionProcessor.process(processingRequest)
      │         → QueueProvider.acquire(acquireRequest)
      │               ← ProviderOperationResult.Success(Entries[1 entry])
      │         [handler returns Deferred]
      │         → QueueProvider.defer(entry)
      │               ← ProviderOperationResult.Success(Unit)
      │         ← QueueProcessingResult.Processed(
      │                summary.acquired=1, summary.deferred=1,
      │                acquisitionLimitReached=false,
      │                earliestRescheduledAt=null,
      │                earliestDeferredAt=<availableAt>,
      │            )
      │
      ├─ → DataLoomClock.now()    [read exactly once]
      │         ← DataLoomInstant(nowMs)
      │
      ├─ delay = max(0, earliestDeferredAt − nowMs)
      │
      ├─ Wake-up plan: Schedule(
      │     reason=DEFERRED_ENTRY_AVAILABLE,
      │     delay=SchedulingDelay(delayMs),
      │     scheduleId=..., constraints=..., existingSchedulePolicy=...,
      │   )
      │
      ├─ → SchedulerProvider.schedule(ScheduleRequest)
      │         ← ProviderOperationResult.Success(ScheduleReceipt)
      │
      └─ ← QueueWorkerRunResult.ProcessingCompleted(
               schedulingResult = Scheduled(receipt, plan),
           )
```

---

## Sequence 5: Retry and deferral in the same cycle

When both kinds of future availability exist, the coordinator schedules once
at the earlier successfully persisted timestamp.

```text
QueueProcessingResult.Processed(
    earliestRescheduledAt=<retryAt>,
    earliestDeferredAt=<deferredAt>,
)
        ↓
earliest = min(retryAt, deferredAt)
        ↓
QueueWorkerWakeUpPlan.Schedule(
    reason=RETRY_AND_DEFERRAL_AVAILABLE,
    delay=max(0, earliest − now),
)
        ↓
SchedulerProvider.schedule(...) exactly once
```

If the acquisition limit was also reached, the reason is `BOTH` and the one
scheduled delay is the minimum of the continuation delay and future-entry
delay.

---

## Sequence 6: Scheduler failure after durable processing

Durable queue state is **not** rolled back. The coordinator returns
`ProcessingCompleted` with `SchedulerFailed` inside `schedulingResult`.

```text
Caller
  → QueueWorkerCoordinator.run(request)
      │
      ├─ → DurableQueueExecutionProcessor.process(processingRequest)
      │         [entries acquired, executed, and transitions persisted]
      │         ← QueueProcessingResult.Processed(acquisitionLimitReached=true)
      │
      ├─ Wake-up plan: Schedule(reason=ACQUISITION_LIMIT_REACHED, ...)
      │
      ├─ → SchedulerProvider.schedule(ScheduleRequest)
      │         ← ProviderOperationResult.Failure(DataLoomError)
      │
      └─ ← QueueWorkerRunResult.ProcessingCompleted(
               processingResult = Processed(...),    [durable state preserved]
               schedulingResult = SchedulerFailed(error, plan),
           )
         ─── queue state NOT rolled back ───
         ─── another host trigger may be required ───
```

---

## Sequence 7: Recovery failure

Recovery failure stops the cycle. No acquisition or scheduling follows.

```text
Caller
  → QueueWorkerCoordinator.run(request)
      │   [recoverExpiredLeasesBeforeProcessing=true]
      │
      ├─ → QueueProvider.recoverExpiredLeases(recoveryRequest)
      │         ← ProviderOperationResult.Failure(DataLoomError)
      │
      └─ ← QueueWorkerRunResult.RecoveryFailed(error)
         ─── no acquisition ───
         ─── no scheduling ───
```

---

## Sequence 8: Cancellation during scheduler invocation

Durable queue transitions that succeeded before cancellation are not rolled
back. Cancellation propagates normally.

```text
Caller
  → QueueWorkerCoordinator.run(request)
      │
      ├─ → DurableQueueExecutionProcessor.process(processingRequest)
      │         [entries acquired, executed, and transitions persisted]
      │         ← QueueProcessingResult.Processed(acquisitionLimitReached=true)
      │
      ├─ Wake-up plan: Schedule(...)
      │
      ├─ → SchedulerProvider.schedule(ScheduleRequest)
      │         ← throws CancellationException
      │
      └─ ← CancellationException propagates to caller
         ─── queue state NOT rolled back ───
         ─── no result variant is created ───
```

---

## Wake-up delay calculation

```
When only acquisitionLimitReached:
    delay = configuration.continuationDelay

When retry or deferral availability exists:
    earliest = minimum non-null value of:
               earliestRescheduledAt, earliestDeferredAt
    now      = DataLoomClock.now()     [read exactly once]
    delay    = max(0, earliest − now)
    reason   = RESCHEDULED_ENTRY_AVAILABLE,
               DEFERRED_ENTRY_AVAILABLE, or
               RETRY_AND_DEFERRAL_AVAILABLE

When acquisitionLimitReached and future availability both exist:
    continuationMs = configuration.continuationDelay.milliseconds
    now            = DataLoomClock.now()     [read exactly once]
    futureMs       = max(0, earliest − now)
    selectedDelay  = min(continuationMs, futureMs)
    reason         = BOTH
```

The clock is **not** read when:
- no wake-up is required
- only `continuationDelay` is needed

Already-due timestamps produce `SchedulingDelay.ZERO`.

---

## Invariants

| Invariant | Guarantee |
|---|---|
| Recovery calls | At most one per `run()` |
| Acquisition calls | Exactly one per `run()` (via processor) |
| Scheduler calls | At most one per `run()` |
| Queue state rollback | Never |
| CancellationException | Always propagates; never becomes a result variant |
| Clock reads | At most one per `run()` |
| Processing cycles | Exactly one per `run()` |

---

## Continuation evidence source

`QueueProcessingResult.Processed` exposes:

| Field | Source |
|---|---|
| `acquisitionLimitReached` | `acquiredCount >= QueueAcquireRequest.maxEntries` |
| `earliestRescheduledAt` | Minimum `availableAt` from **successfully persisted** reschedule transitions only |
| `earliestDeferredAt` | Minimum `availableAt` from **successfully persisted** deferral transitions only |
| `earliestNextAvailableAt` | Derived minimum non-null retry or deferral timestamp |

Failed reschedule or deferral transitions do not contribute a timestamp.

---

## Boundaries

The coordinator must not:

- Execute a second queue-processing cycle.
- Call synchronization coordinators or pipelines directly.
- Invoke `RetryPolicy` directly.
- Decode queue entry payloads.
- Create or mutate queue entries outside `QueueProvider` contracts.
- Observe connectivity continuously.
- Dispatch new synchronization event variants.
- Own a `CoroutineScope` or select a dispatcher.
- Use `System.currentTimeMillis`, `java.time`, or any platform clock.
- Implement WorkManager, `AlarmManager`, `JobScheduler`, or any platform
  scheduler.
- Implement global worker locks or distributed locks.

---

## Concurrency

- `QueueProvider` owns atomic acquisition.
- `SchedulerProvider` and `ExistingSchedulePolicy` own platform-level
  schedule deduplication.
- Callers are responsible for host-level worker concurrency.
- The coordinator does not guarantee single-instance worker execution.

---

## KMP compatibility

All contracts use Kotlin standard-library and DataLoom API types only.
No Android, JVM-only, or third-party types are exposed in public contracts.
