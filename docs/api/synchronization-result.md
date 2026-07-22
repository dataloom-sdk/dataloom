# DataLoom Synchronization Result Contracts (DL-016)

This document defines the synchronization result contracts introduced in
`dataloom-api` by DL-016.

These contracts represent the terminal outcomes of synchronization workflow
execution. Runtime lifecycle transitions, queue mutations, retry execution,
and event dispatch are **not implemented** in this issue.

---

## Overview

`SynchronizationResult` is a sealed public interface with one variant per
possible terminal state. The future DataLoom runtime produces and supplies
the appropriate variant at the end of every workflow execution.

Applications receive results through
[`SynchronizationEvent.Completed`](./synchronization-events.md) or a
direct result callback defined in a future integration issue.

---

## Sealed Contract

```kotlin
public sealed interface SynchronizationResult {
    public val request: SynchronizationRequest
    public val completedAt: DataLoomInstant
    public val summary: SynchronizationSummary
    public val metadata: DataLoomMetadata
}
```

All variants expose the four common properties above.

---

## Variants

### Succeeded

The synchronization workflow completed successfully.

> This result does not imply that remote business processing beyond the
> configured transport contract is complete.

```kotlin
public data class Succeeded(
    override val request: SynchronizationRequest,
    override val completedAt: DataLoomInstant,
    override val summary: SynchronizationSummary,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationResult
```

---

### PartiallySucceeded

Some work succeeded and one or more canonical errors remain.

```kotlin
public class PartiallySucceeded(
    override val request: SynchronizationRequest,
    override val completedAt: DataLoomInstant,
    override val summary: SynchronizationSummary,
    errors: List<DataLoomError>,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationResult {
    public val errors: List<DataLoomError>  // read-only, defensive copy
}
```

#### Constraints

- `errors` must contain at least one item; empty collections are rejected.
- The `errors` list is defensively copied at construction.
- The exposed `errors` property is read-only.
- Provider-specific exception types must not be exposed through `errors`.

---

### Failed

The workflow failed with a canonical `DataLoomError`.

```kotlin
public data class Failed(
    override val request: SynchronizationRequest,
    override val completedAt: DataLoomInstant,
    override val summary: SynchronizationSummary,
    public val error: DataLoomError,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationResult
```

Stack traces, credentials, and payload content must not be exposed through
`error.message`.

---

### Cancelled

Execution was cancelled before successful completion.

```kotlin
public data class Cancelled(
    override val request: SynchronizationRequest,
    override val completedAt: DataLoomInstant,
    override val summary: SynchronizationSummary,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationResult
```

> Creating this result does **not** cancel any running work. Cancellation
> is represented as an explicit terminal state by the future runtime or host
> integration.

`Cancelled` is distinct from `Failed` and `Skipped`.

---

### Skipped

Execution did not proceed because of the supplied canonical skip reason.

```kotlin
public data class Skipped(
    override val request: SynchronizationRequest,
    override val completedAt: DataLoomInstant,
    override val summary: SynchronizationSummary,
    public val reason: SynchronizationSkipReason,
    override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : SynchronizationResult
```

#### SynchronizationSkipReason values

| Value                      | Meaning                                                         |
|----------------------------|-----------------------------------------------------------------|
| `NO_CHANGES`               | No eligible synchronization changes were available              |
| `CONSTRAINTS_NOT_SATISFIED`| Required execution constraints were not met                     |
| `POLICY_REJECTED`          | Runtime or application policy prevented execution               |
| `DUPLICATE_REQUEST`        | Equivalent work was already active or accepted                  |

---

## Common Requirements

- Results are immutable.
- Construction does not change workflow state.
- Construction does not write queue records.
- Construction does not cancel work.
- Construction does not log.
- Payload contents are not exposed.
- All results provide value-based equality.

---

## Queue Boundary

The relationship between queue operations and synchronization results:

```text
QueueProvider.acquire()
        ↓
Runtime starts workflow
        ↓
Synchronization events
        ↓
SynchronizationResult
        ↓
QueueProvider.complete(), reschedule(), or fail()
```

Events and results do **not** mutate queue entries themselves. Queue
transitions are performed by the future runtime after receiving a result.

---

## Coroutine Cancellation Boundary

`kotlin.coroutines.CancellationException` must still propagate through
runtime code and must never be swallowed. The `Cancelled` result variant is
created explicitly by the future runtime or host integration after
intercepting a cancellation; it is not converted automatically from a
caught `CancellationException`.
