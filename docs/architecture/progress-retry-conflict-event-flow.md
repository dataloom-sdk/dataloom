# Progress, Retry, and Conflict Event Flow (DL-030)

This document describes the operational event integration flows introduced in
`dataloom-runtime` by DL-030.

DL-030 extends the DL-029 lifecycle event infrastructure with `ProgressUpdated`,
`RetryScheduled`, and `ConflictDetected` event generation at durable operation
boundaries. It reuses DL-029 event-ID generation, timestamp generation, and
observer dispatch without duplication.

---

## Overview

DL-030 wires three operational event types into the DataLoom execution runtime:

| Event              | Source                                 | Boundary                                     |
|--------------------|----------------------------------------|----------------------------------------------|
| ProgressUpdated    | OutboundPushSynchronizationPipeline    | After durable outbound batch acknowledgement |
| ProgressUpdated    | InboundPullSynchronizationPipeline     | After durable inbound apply and checkpoint   |
| RetryScheduled     | SynchronizationRetryOrchestrator       | After SchedulerProvider.schedule() succeeds  |
| ConflictDetected   | SynchronizationConflictOrchestrator    | After conflict detected, before resolution   |

---

## 1. Outbound progress flow

```text
OutboundPushSynchronizationPipeline.execute(context)
    → emitPhaseChanged(READING_OUTBOUND)
    → StorageProvider.readOutboundChanges()
        → NoChanges → skip progress, continue to Completed
        → Changes(changeSet, hasMore)
    → emitPhaseChanged(PUSHING)
    → TransportProvider.pushChanges(changeSet)
        → Failure → no progress, classify Failed, stop
    → validate acknowledgement
        → invalid → no progress, classify Failed, stop
    → StorageProvider.acknowledgeOutboundChanges()
        → Failure → no progress, classify Failed, stop
    → update summary counters (outboundEventsRead, etc.)
    → construct SynchronizationProgress(phase=PUSHING, completed=cumulative, total=null, unit=EVENTS)
    → runtimeEmitter?.emitProgressUpdated(request, progress)
        → (CancellationException propagates; durable work already complete)
        → (ordinary observer failure is isolated; processing continues)
    → if hasMore → repeat from readOutboundChanges
    → emitCompleted(context, result)
```

Notes:

- Progress is emitted only after the acknowledgement is durably persisted.
- Progress uses cumulative `outboundEventsRead` as the `completed` counter.
- `total` is `null` because the total number of batches is not known in advance.
- Cancellation during progress delivery does not undo the accepted batch.
- Observer failures do not change `Succeeded`, `PartiallySucceeded`, `Failed`,
  or `Skipped` result classification.

---

## 2. Inbound progress flow

```text
InboundPullSynchronizationPipeline.execute(context)
    → emitPhaseChanged(READING_CHECKPOINT)
    → StorageProvider.readCheckpoint()
    → emitPhaseChanged(PULLING)
    → TransportProvider.pullChanges(checkpoint)
        → NoChanges → skip progress, continue to Completed
        → Changes(changeSet, hasMore, nextCheckpoint)
    → emitPhaseChanged(APPLYING_INBOUND)
    → StorageProvider.applyInboundChanges(changeSet)
        → Failure → no progress, classify Failed, stop
    → if nextCheckpoint != null:
        → StorageProvider.writeCheckpoint(nextCheckpoint)
            → Failure → no progress, classify Failed, stop
    → update summary counters (inboundEventsApplied, etc.)
    → construct SynchronizationProgress(phase=APPLYING_INBOUND, completed=cumulative, total=null, unit=EVENTS)
    → runtimeEmitter?.emitProgressUpdated(request, progress)
        → (CancellationException propagates; apply and checkpoint may already be complete)
        → (ordinary observer failure is isolated; processing continues)
    → if hasMore → repeat from pullChanges
    → emitCompleted(context, result)
```

Notes:

- Progress is emitted only after both apply and required checkpoint persistence
  succeed.
- When no checkpoint write is required (`nextCheckpoint == null`), progress
  emits after successful apply.
- The apply-before-checkpoint invariant is unchanged.
- `total` is `null` because the total number of batches is not known in advance.

---

## 3. Successful scheduler-backed retry flow

```text
SynchronizationRetryOrchestrator.evaluateAndSchedule(request)
    → inspect SynchronizationResult variant
        → Succeeded / Skipped / Cancelled → return NOT_REQUIRED (no event)
    → extract canonical errors from Failed or PartiallySucceeded
    → RetryPolicy.evaluate(error) for each error
        → all Stop → return STOPPED (no event)
    → select maximum SchedulingDelay across Retry decisions
    → if schedulerProvider == null → return SCHEDULER_NOT_CONFIGURED (no event)
    → SchedulerProvider.schedule(request)
        → ProviderOperationResult.Failure → return SCHEDULER_FAILED (no event)
        → ProviderOperationResult.Success(receipt)
    → select primary error for event (error whose Retry decision has max delay)
    → eventEmitter?.emitRetryScheduled(syncRequest, attempt, delay, primaryError)
        → (CancellationException propagates; accepted schedule is NOT cancelled)
        → (ordinary observer failure is isolated; SCHEDULED result is returned)
    → return SCHEDULED(receipt)
```

Notes:

- `SchedulerProvider.schedule()` is called at most once per evaluation.
- The event carries the exact `SynchronizationRequest`, `RetryAttempt`,
  `SchedulingDelay`, and primary `DataLoomError`.
- Observer failure does not change the `SCHEDULED` result.
- The accepted schedule is never automatically cancelled when cancellation
  occurs during event delivery.

---

## 4. Scheduler failure flow

```text
SynchronizationRetryOrchestrator.evaluateAndSchedule(request)
    → RetryPolicy evaluation requests retry
    → SchedulerProvider.schedule(request)
        → ProviderOperationResult.Failure(error)
    → (no RetryScheduled event)
    → return SCHEDULER_FAILED(error)
```

---

## 5. Conflict detection and resolution flow

```text
SynchronizationConflictOrchestrator.detectAndResolve(request)
    → ConflictDetectorRegistry.lookup(detectorId)
        → not found → return DetectorNotFound (no event)
    → ConflictDetector.detect(detectionRequest)
        → throws → propagate (no event)
        → ConflictDetectionResult.NoConflict → return NoConflict (no event)
        → ConflictDetectionResult.ConflictDetected(conflict)
    → eventEmitter?.emitConflictDetected(syncRequest, conflict)
        → (CancellationException propagates; resolver does not run)
        → (ordinary observer failure is isolated; resolution continues)
    → ConflictResolverRegistry.lookup(resolverId)
        → resolverId == null → return ResolverNotConfigured
        → not found → return ResolverNotFound
    → ConflictResolver.resolve(resolutionRequest)
    → return Resolved(decision)
```

Notes:

- `ConflictDetected` is always emitted before resolver lookup and invocation.
- `resolver-not-configured` and `resolver-not-found` still dispatch the event.
- Cancellation during `ConflictDetected` delivery prevents resolver execution.
- Observer failure does not stop resolver selection or resolution.
- Detector and resolver are each invoked at most once per call.

---

## 6. Cancellation during event delivery

```text
runtimeEmitter.emitConflictDetected(request, conflict)
    → SynchronizationEventDispatcher.dispatch(event)
        → observer.onEvent(event)
            → throws CancellationException
    → CancellationException propagates from dispatch
    → CancellationException propagates from emitConflictDetected
    → (resolver lookup and resolution do not continue)
    → CancellationException propagates from detectAndResolve
```

The same propagation applies to `emitProgressUpdated` and `emitRetryScheduled`.

Cancellation is never converted into:

- `SynchronizationResult.Cancelled`
- `RetryOrchestrationResult`
- `ConflictOrchestrationResult`
- `DataLoomError`

---

## Event ordering

All events from a single synchronization execution follow this order:

```
Started
    → PhaseChanged (first phase)
    → provider operation
    → zero or more ProgressUpdated
    → PhaseChanged (next phase)
    → ...
    → Completed
```

Retry and conflict events occur outside the synchronization execution boundary:

```
# Retry (after SynchronizationResult is produced):
RetryScheduled (after scheduler accepts)

# Conflict (during pipeline execution, within a phase):
ConflictDetected (after detection, before resolution)
```

Requirements:

- No progress before `Started`.
- No progress after `Completed`.
- `Completed` remains the final lifecycle event for one execution.
- `RetryScheduled` only follows a successful `SchedulerProvider.schedule()`.
- `ConflictDetected` precedes resolver invocation.
- Event IDs and timestamps follow actual emission order.
- No global ordering across concurrent executions.

---

## No-emitter behavior

When `lifecycleEventEmitter` is `null`:

- No event-ID generation occurs.
- No clock read occurs.
- No event object is constructed.
- All pipeline and orchestrator behavior is preserved.

When `lifecycleEventEmitter` is a `SynchronizationLifecycleEventEmitter` but
not a `SynchronizationRuntimeEventEmitter`:

- Lifecycle events (Started, PhaseChanged, Completed) are dispatched normally.
- Operational events (ProgressUpdated, RetryScheduled, ConflictDetected) are
  silently skipped.
- No event-ID or clock overhead for operational events.

---

## Queue-backed retry boundary

Queue-backed retry uses `QueueEntryExecutionOutcome.Reschedule`. It does not
use `SchedulerProvider`.

A `RetryScheduled` event for queued retry may be emitted only after the
`QueueProvider` reschedule transition has succeeded and sufficient safe context
is available. When safe context is not available, queue-backed `RetryScheduled`
emission is deferred. This is documented rather than fabricated.

`SchedulerProvider` is never called for queued retry. No duplicate queue entry
is created. No queue payload is decoded solely for event generation.

---

## Related documents

- [Runtime Operational Events (DL-030)](../api/runtime-operational-events.md)
- [Runtime Event Integration Flow (DL-029)](./runtime-event-integration-flow.md)
- [Outbound Push Flow (DL-021)](./outbound-push-flow.md)
- [Inbound Pull Flow (DL-022)](./inbound-pull-flow.md)
- [Retry and Rescheduling Flow (DL-024)](./retry-rescheduling-flow.md)
- [Observer Delivery Flow (DL-028)](./observer-delivery-flow.md)
- [Conflict Detection and Resolution Flow (DL-025)](./conflict-detection-resolution-flow.md)
