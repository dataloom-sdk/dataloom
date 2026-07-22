# DataLoom Synchronization Progress Contracts (DL-016)

This document defines the synchronization progress contracts introduced in
`dataloom-api` by DL-016.

These contracts represent execution phases, progress snapshots, and the
summary statistics attached to every synchronization result. Runtime
progress calculation, event dispatch, provider orchestration, and queue
operations are **not implemented** in this issue.

---

## Overview

DataLoom provides immutable progress data types that describe how far a
synchronization workflow has advanced within a given execution phase.

---

## SynchronizationPhase

`SynchronizationPhase` is a closed `enum class` that describes the current
operation within an active synchronization workflow.

### Phases

| Phase                       | Description                                                         |
|-----------------------------|---------------------------------------------------------------------|
| `VALIDATING`                | Runtime is validating configuration, providers, and request requirements |
| `WAITING_FOR_CONNECTIVITY`  | Execution is paused until connectivity requirements are satisfied   |
| `READING_OUTBOUND`          | Runtime is requesting outbound changes from `StorageProvider`       |
| `PUSHING`                   | Outbound changes are being sent through `TransportProvider`         |
| `ACKNOWLEDGING_OUTBOUND`    | Remote acknowledgement results are being applied through `StorageProvider` |
| `PULLING`                   | Runtime is requesting inbound changes through `TransportProvider`   |
| `APPLYING_INBOUND`          | Inbound changes are being applied through `StorageProvider`         |
| `WRITING_CHECKPOINT`        | Successfully applied inbound checkpoint is being persisted          |
| `RESOLVING_CONFLICTS`       | One or more conflicts are being evaluated or resolved               |
| `WAITING_FOR_RETRY`         | Execution is waiting for a future retry opportunity                 |
| `FINALIZING`                | Runtime is preparing the terminal synchronization result            |

### Boundary with WorkflowLifecycleState

`SynchronizationPhase` and `WorkflowLifecycleState` are distinct types with
different purposes:

| Type                    | Meaning                                             |
|-------------------------|-----------------------------------------------------|
| `WorkflowLifecycleState`| High-level workflow state (QUEUED, RUNNING, …)      |
| `SynchronizationPhase`  | Current operation within an executing workflow      |

A workflow may be in state `RUNNING` while cycling through multiple
`SynchronizationPhase` values.

### Usage constraints

- Do not rely on enum ordinals for persistence or comparison.
- Do not persist enum ordinals.
- Phase transition logic is defined by the future runtime engine and is not
  enforced by this type.

---

## SynchronizationProgressUnit

`SynchronizationProgressUnit` is a closed `enum class` that describes the
unit of measurement used by a `SynchronizationProgress` snapshot.

| Value        | Description                                                         |
|--------------|---------------------------------------------------------------------|
| `EVENTS`     | Synchronization change-event count                                  |
| `BYTES`      | Payload or asset byte count                                         |
| `OPERATIONS` | Logical provider or synchronization operation count                 |
| `STEPS`      | Application or runtime-defined execution-step count                 |

### Notes

- Not every workflow is expected to know its total in advance; a `null`
  total represents indeterminate progress regardless of unit.
- Do not rely on enum ordinals.

---

## SynchronizationProgress

`SynchronizationProgress` is an immutable data class that represents a
progress snapshot at a point in time.

```kotlin
public data class SynchronizationProgress(
    public val phase: SynchronizationPhase,
    public val completed: Long,
    public val total: Long?,
    public val unit: SynchronizationProgressUnit,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
```

### Properties

| Property    | Description                                                         |
|-------------|---------------------------------------------------------------------|
| `phase`     | The current synchronization phase                                   |
| `completed` | Non-negative count of units completed so far                        |
| `total`     | Optional total unit count; `null` indicates indeterminate progress  |
| `unit`      | Unit of measurement for `completed` and `total`                     |
| `metadata`  | Optional context; defaults to `DataLoomMetadata.Empty`              |

### Constraints

- `completed` must be zero or greater.
- `total`, when provided, must be zero or greater.
- When `total` is provided, `completed` must not exceed `total`.
- A `null` total represents indeterminate progress.

### Percentage calculation

Percentage may be calculated by consumers when `total` is non-null and
greater than zero:

```kotlin
val percent = progress.completed.toDouble() / progress.total!!.toDouble() * 100
```

### Construction constraints

- Construction does not perform synchronization.
- Construction does not calculate progress by reading providers.
- Construction does not log.

---

## SynchronizationSummary

`SynchronizationSummary` is an immutable data class produced by the future
DataLoom runtime at the end of a workflow. It is attached to every
`SynchronizationResult`.

```kotlin
public data class SynchronizationSummary(
    public val outboundEventsRead: Long = 0L,
    public val outboundEventsAccepted: Long = 0L,
    public val outboundEventsMarkedForRetry: Long = 0L,
    public val outboundEventsRejected: Long = 0L,
    public val inboundEventsReceived: Long = 0L,
    public val inboundEventsApplied: Long = 0L,
    public val conflictsDetected: Long = 0L,
    public val retryAttempts: Int = 0,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
```

### Constraints

- Every counter must be zero or greater.
- `outboundEventsAccepted`, `outboundEventsMarkedForRetry`, and
  `outboundEventsRejected` must each individually not exceed
  `outboundEventsRead`.
- `inboundEventsApplied` must not exceed `inboundEventsReceived`.
- The sum of accepted, retry, and rejected outbound counts is **not**
  required to equal the read count. Some events may remain unprocessed when
  a workflow terminates early.

### Construction constraints

- Construction does not query providers.
- Construction does not mutate state.
- Construction does not log.
