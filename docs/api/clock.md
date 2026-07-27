# DataLoom Clock (DL-017)

[API reference index](./README.md)

> **Status:** Available wall-clock contract. A monotonic duration abstraction
> and production-wide elapsed-time enforcement remain V1 gaps.

**Package:** `io.dataloom.api.time`

## Overview

`DataLoomClock` provides a platform-independent abstraction for obtaining the
current wall-clock instant as a `DataLoomInstant`. Runtime components use it
when they need to record a timestamp for a persisted value such as a queue
entry, lease, or synchronization event.

---

## `DataLoomClock`

**Type:** `interface`

```kotlin
public interface DataLoomClock {
    public fun now(): DataLoomInstant
}
```

### `now()`

Returns the current instant from this clock.

- Does not sleep, schedule work, or mutate runtime state.
- Each call may return a different value for real-clock implementations.
- Test implementations may return fixed or controlled values.

---

## `DataLoomInstant`

**Package:** `io.dataloom.api.time`

An immutable, platform-independent representation of an absolute point in time
expressed as milliseconds since the Unix epoch (1970-01-01T00:00:00Z).

| Member | Type | Description |
|---|---|---|
| `epochMilliseconds` | `Long` | Non-negative milliseconds since the Unix epoch. |

### Rules

- `epochMilliseconds` must be zero or greater. Negative values are rejected.
- Construction does not read the system clock.
- Does not represent a duration.
- Does not depend on `java.time` or any third-party date-time library.

---

## Explicit clock injection

`DataLoomClock` must be injected into every component that needs the current
time. It must not be accessed through a global singleton or a companion-object
field. Injecting the clock enables deterministic testing and removes the
component's dependency on the platform clock.

```kotlin
class QueueEnqueuer(
    private val clock: DataLoomClock,
) {
    fun enqueue(request: SynchronizationRequest): QueueEnqueueRequest {
        val now = clock.now()
        return QueueEnqueueRequest(
            request = request,
            enqueueTime = now,
        )
    }
}
```

---

## Wall-clock versus elapsed-time semantics

`DataLoomClock` is a wall-clock abstraction, not a monotonic timer.

| Property | `DataLoomClock` |
|---|---|
| Measures real-world wall-clock time | Yes |
| Guaranteed monotonic | No |
| Suitable for measuring elapsed execution time | No |
| Suitable for persisted timestamps | Yes |

Do not use `DataLoomClock` to measure elapsed time between two code points.
A future dedicated monotonic-time abstraction will be introduced when elapsed
time measurement is required by a specific runtime component.

---

## Model-construction boundary

DataLoom models receive explicit `DataLoomInstant` values as constructor
parameters. Models must not call the clock themselves.

### Correct

```kotlin
// Clock is read once before model construction.
val now = clock.now()
val entry = QueueEntry(enqueuedAt = now, ...)
```

### Incorrect

```kotlin
// Models must not access a clock during construction.
val entry = QueueEntry(enqueuedAt = clock.now(), ...) // call inside constructor — prohibited
```

---

## Expected runtime uses

```
Queue entry enqueue time
Queue entry available time
Queue lease acquisition time
Queue lease expiration time
Synchronization-event occurrence time
Synchronization-result completion time
Expired-lease recovery time
```

---

## Deferred production implementations

Production system-clock implementations are deferred. Options under
consideration for future issues include:

```
Android/JVM system clock
Kotlin Multiplatform clock
Apple clock
Application-supplied clock
```

Test utilities in `dataloom-testing` provide `DataLoomClock` implementations
for deterministic testing. See
[Clock and Identifier Test Utilities](../testing/clock-and-identifiers.md).
