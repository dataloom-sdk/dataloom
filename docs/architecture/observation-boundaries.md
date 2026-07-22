# Observation Boundaries (DL-016)

This document describes the architectural responsibility boundaries for the
synchronization observation system in DataLoom.

> **Important:** Event dispatch infrastructure, observer registration,
> replay, backpressure, and Flow-based streaming are not implemented in
> DL-016. This document describes the intended architecture and the
> boundaries that govern future implementation.

---

## Overview

DataLoom provides an application-level observation contract that allows host
applications to receive immutable notifications about synchronization
lifecycle activity without controlling it.

---

## Observation Semantics

| Boundary                  | Description                                                   |
|---------------------------|---------------------------------------------------------------|
| Events describe activity  | Events are immutable facts about what happened; they do not control the runtime |
| Callbacks are notifications| `onEvent()` is a notification, not a command                  |
| Event ordering            | Expected to follow runtime emission order                     |
| Cross-thread delivery     | Not defined in DL-016; determined by the future runtime       |
| Replay                    | Not implemented in DL-016                                     |
| Persistent event history  | Not implemented in DL-016                                     |
| Multiple-observer registration | Not implemented in DL-016                               |
| Observer failure isolation | Not implemented in DL-016                                    |
| Backpressure              | Not implemented in DL-016                                     |
| Lifecycle-aware collection | Not implemented in DL-016                                    |
| Flow-based observation    | Deferred to a future issue                                    |

Do not claim guaranteed exactly-once event delivery, which is not defined
in DL-016.

---

## SynchronizationObserver Contract

```kotlin
public interface SynchronizationObserver {
    public val id: SynchronizationObserverId
    public fun onEvent(event: SynchronizationEvent)
}
```

### Implementation guidelines

- Return quickly from `onEvent()`.
- Do not block the calling thread.
- Do not throw from `onEvent()`; the future runtime will isolate observer
  failures so that one failing observer cannot fail synchronization or
  prevent delivery to other observers.
- Do not log payload content automatically from within `onEvent()`.
- Do not mutate DataLoom runtime state directly.
- Do not claim guaranteed exactly-once delivery.

### Thread safety

The calling thread for `onEvent()` is determined by the future runtime and
is not defined in DL-016. Implementations are responsible for their own
thread safety.

---

## Component Responsibilities

| Component                  | Responsibility                                                  |
|----------------------------|-----------------------------------------------------------------|
| `SynchronizationObserver`  | Application-level event observation contract                    |
| `MonitoringProvider`       | Future metrics and telemetry integration (deferred)             |
| `LoggingProvider`          | Future structured logging integration (deferred)               |
| DataLoom Runtime (future)  | Produces and dispatches events; isolates observer failures      |

These have different responsibilities and must not be conflated.

- `SynchronizationObserver` is for application-level observation.
- `MonitoringProvider` is a future metrics and telemetry integration.
- `LoggingProvider` is a future structured logging integration.

The runtime may adapt lifecycle events into metrics or logs later through
separate provider integrations.

---

## Workflow Lifecycle Boundary

```text
WorkflowLifecycleState
→ High-level workflow state: the lifecycle of the workflow as a whole
  (e.g., QUEUED, RUNNING, SUCCEEDED, FAILED)

SynchronizationPhase
→ Current operation within an executing workflow
  (e.g., PUSHING, PULLING, RESOLVING_CONFLICTS)

SynchronizationEvent
→ Immutable fact emitted about execution at a point in time

SynchronizationResult
→ Terminal outcome attached to the Completed event
```

The event model does **not** replace queue state or workflow lifecycle
state.

---

## Queue Boundary

```text
QueueProvider.acquire()
        ↓
Runtime starts workflow
        ↓
Synchronization events (Started, PhaseChanged, ProgressUpdated, …)
        ↓
SynchronizationResult
        ↓
QueueProvider.complete(), reschedule(), or fail()
```

Events and results do **not** mutate queue entries themselves. Queue
transitions are performed by the future runtime after producing a result.

---

## Retry Boundary

```text
RetryPolicy produces RetryDecision
        ↓
Runtime schedules or persists retry work
        ↓
RetryScheduled event reports the resulting decision
```

- The `RetryScheduled` event does not evaluate policy.
- The `RetryScheduled` event does not call `SchedulerProvider`.
- The `RetryScheduled` event does not reschedule a queue entry.

---

## Conflict Boundary

```text
ConflictDetector identifies a conflict
        ↓
Runtime emits ConflictDetected event
        ↓
ConflictResolver produces a decision
        ↓
Runtime applies the decision
```

- Event observers do not resolve conflicts.
- Conflict payloads must not be logged automatically by observers.
- A separate conflict-resolution event may be introduced later when runtime
  semantics are implemented.

---

## Future Flow Observation

A dedicated runtime-observation issue will introduce a Flow-based API after
event delivery semantics are approved:

```kotlin
public fun observe(workflowId: WorkflowId): Flow<SynchronizationEvent>
```

Requirements for the future issue:

- Do not add `kotlinx-coroutines-core` in DL-016.
- Do not expose `Flow` in the current public API.
- Do not implement event replay.
- Do not implement hot or cold stream behavior.
- Do not define buffer or overflow behavior yet.

---

## Security and Privacy

- Event metadata must not contain credentials, encryption keys, or raw
  payload bytes.
- Observer implementations must follow DataLoom security restrictions.
- Sensitive payloads and metadata must not be exposed.
- `toString()` must not expose opaque payload content, credentials, or
  secrets.
