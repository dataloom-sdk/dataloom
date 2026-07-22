# DataLoom Synchronization Events Contracts (DL-016)

This document defines the synchronization lifecycle event contracts
introduced in `dataloom-api` by DL-016.

These contracts represent immutable facts about synchronization execution.
Event dispatch infrastructure, observer registration, replay, backpressure,
and Flow-based streaming are **not implemented** in this issue.

---

## Overview

`SynchronizationEvent` is a sealed public interface with one variant per
observable lifecycle moment. Events are produced and emitted by the future
DataLoom runtime; applications receive them through a
`SynchronizationObserver`.

---

## Conceptual Event Ordering

Events are expected to follow runtime emission order:

```text
SynchronizationRequest
        ↓
Started event
        ↓
PhaseChanged events (one or more)
        ↓
ProgressUpdated events (zero or more)
        ↓
RetryScheduled | ConflictDetected (zero or more, when applicable)
        ↓
Completed event (always last)
```

`Started` is emitted before any operational phases begin. `Completed` is
always the terminal event for a given synchronization request.

---

## Common Properties

Every event variant exposes:

```kotlin
public val id: SynchronizationEventId
public val request: SynchronizationRequest
public val occurredAt: DataLoomInstant
public val metadata: DataLoomMetadata
```

| Property      | Description                                                       |
|---------------|-------------------------------------------------------------------|
| `id`          | Unique event identifier; supplied by the runtime, not generated automatically |
| `request`     | The synchronization request this event belongs to                 |
| `occurredAt`  | The instant at which this event occurred; not read automatically  |
| `metadata`    | Optional context; defaults to `DataLoomMetadata.Empty`            |

---

## Identifiers

### SynchronizationEventId

Wraps a non-blank `String` that uniquely identifies a single emitted event.

- Blank and whitespace-only values are rejected.
- Exact input is preserved without normalization.
- `toString()` returns the wrapped value.
- Ownership: future runtime or host integration.

### SynchronizationObserverId

Wraps a non-blank `String` that identifies a `SynchronizationObserver`
implementation.

- Blank and whitespace-only values are rejected.
- Exact input is preserved without normalization.
- `toString()` returns the wrapped value.
- Ownership: host application or integration.

---

## Event Variants

### Started

Represents runtime acceptance of the synchronization request. It does not
guarantee that provider operations have started.

```kotlin
public data class Started(
    override val id: SynchronizationEventId,
    override val request: SynchronizationRequest,
    override val occurredAt: DataLoomInstant,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationEvent
```

---

### PhaseChanged

Emitted when the runtime moves from one `SynchronizationPhase` to another.

```kotlin
public data class PhaseChanged(
    override val id: SynchronizationEventId,
    override val request: SynchronizationRequest,
    override val occurredAt: DataLoomInstant,
    public val phase: SynchronizationPhase,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationEvent
```

Creating this event does not perform the phase transition.

---

### ProgressUpdated

Emitted when the runtime has a meaningful progress update to report.

```kotlin
public data class ProgressUpdated(
    override val id: SynchronizationEventId,
    override val request: SynchronizationRequest,
    override val occurredAt: DataLoomInstant,
    public val progress: SynchronizationProgress,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationEvent
```

Creating this event does not accumulate progress.

---

### RetryScheduled

Reports a retry decision already made by the future runtime.

```kotlin
public data class RetryScheduled(
    override val id: SynchronizationEventId,
    override val request: SynchronizationRequest,
    override val occurredAt: DataLoomInstant,
    public val attempt: RetryAttempt,
    public val delay: SchedulingDelay,
    public val error: DataLoomError,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationEvent
```

#### Retry boundary

```text
RetryPolicy produces RetryDecision
        ↓
Runtime schedules or persists retry work
        ↓
RetryScheduled reports the resulting decision
```

Creating this event:
- Does **not** schedule work.
- Does **not** delay execution.
- Does **not** call `SchedulerProvider`.
- Does **not** reschedule a queue entry.

---

### ConflictDetected

Reports a conflict identified by the runtime.

```kotlin
public data class ConflictDetected(
    override val id: SynchronizationEventId,
    override val request: SynchronizationRequest,
    override val occurredAt: DataLoomInstant,
    public val conflict: SynchronizationConflict,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationEvent
```

#### Conflict boundary

```text
ConflictDetector identifies a conflict
        ↓
Runtime emits ConflictDetected
        ↓
ConflictResolver produces a decision
        ↓
Runtime applies the decision
```

Creating this event:
- Does **not** run `ConflictDetector`.
- Does **not** run `ConflictResolver`.
- Conflict payloads must not be logged automatically by observers.

---

### Completed

The terminal event for a synchronization workflow. Carries the final
`SynchronizationResult`.

```kotlin
public data class Completed(
    override val id: SynchronizationEventId,
    override val request: SynchronizationRequest,
    override val occurredAt: DataLoomInstant,
    public val result: SynchronizationResult,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationEvent
```

#### Constraints

- `request` must equal `result.request`.
- `occurredAt` must not be earlier than `result.completedAt`.

Creating this event:
- Does **not** complete queue work.
- Does **not** publish itself.

---

## Common Event Requirements

- Events are immutable.
- Event IDs are not generated automatically.
- Event timestamps are not read automatically.
- Events do not log themselves.
- `toString()` does not expose payload bytes, credentials, or sensitive
  metadata.

---

## SynchronizationObserver

`SynchronizationObserver` is the application-level contract for receiving
events:

```kotlin
public interface SynchronizationObserver {
    public val id: SynchronizationObserverId
    public fun onEvent(event: SynchronizationEvent)
}
```

See [`observation-boundaries.md`](../architecture/observation-boundaries.md)
for detailed observer semantics and boundaries.

---

## Future Kotlin Flow Observation

A dedicated runtime-observation issue will introduce a Flow-based API after
event delivery semantics are approved:

```kotlin
public fun observe(workflowId: WorkflowId): Flow<SynchronizationEvent>
```

Do **not** add `kotlinx-coroutines-core` or expose `Flow` types in DL-016.
Buffer, overflow, and backpressure behavior are deferred.

---

## Workflow Lifecycle Boundary

```text
WorkflowLifecycleState  → High-level workflow state
SynchronizationPhase    → Current operation within an executing workflow
SynchronizationEvent    → Immutable fact emitted about execution
SynchronizationResult   → Terminal outcome
```

The event model does **not** replace queue state or workflow lifecycle
state.

---

## Security and Privacy

- Event metadata must not contain credentials, encryption keys, or raw
  payload bytes.
- Result metadata must not contain personal data.
- Errors must not expose secrets.
- `toString()` must not expose opaque payload content.
- Observer implementations must follow DataLoom security restrictions.
