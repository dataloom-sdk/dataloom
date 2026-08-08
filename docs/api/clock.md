# DataLoom Clock (DL-017)

[API reference index](./README.md)

> **Status:** Available wall-clock and monotonic-duration contracts, each with
> production JVM/Android and Apple implementations. Production-wide elapsed-time
> *enforcement* (every runtime component actually using
> `DataLoomMonotonicClock` for budgets/timeouts instead of ad hoc measurement)
> remains a separate, broader V1 gap — this page documents the time
> abstractions themselves, not that every consumer has adopted them yet.

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
Use `DataLoomMonotonicClock` (below) instead.

---

## `DataLoomMonotonicClock`

**Type:** `interface`  
**Package:** `io.dataloom.api.time`

```kotlin
public interface DataLoomMonotonicClock {
    public fun mark(): DataLoomMonotonicReading
}
```

The dedicated monotonic-time abstraction referenced above. Use it to measure
elapsed duration between two code points, such as an execution timeout budget.
Do not use it to produce a value suitable for a persisted timestamp; use
`DataLoomClock` for that.

### `DataLoomMonotonicReading`

Immutable value type wrapping non-negative `nanoseconds` since an arbitrary,
implementation-defined origin. Readings are only meaningful relative to other
readings from the *same* `DataLoomMonotonicClock` instance — they do not
represent wall-clock time and are not comparable across clock instances,
processes, or platforms.

```kotlin
public class DataLoomMonotonicReading(public val nanoseconds: Long) {
    public fun elapsedNanosecondsSince(earlier: DataLoomMonotonicReading): Long
}
```

`elapsedNanosecondsSince` fails closed (`IllegalArgumentException`) rather than
returning a misleading negative duration if `earlier` is not actually earlier
than the receiver.

```kotlin
val monotonicClock: DataLoomMonotonicClock = SystemDataLoomMonotonicClock()
val started = monotonicClock.mark()
// ... do work ...
val elapsedNanos = monotonicClock.mark().elapsedNanosecondsSince(started)
```

---

## Production implementations

| Implementation | Interface | Target | Backing |
|---|---|---|---|
| `SystemDataLoomClock` | `DataLoomClock` | JVM (also serves native Android today; see below) | `System.currentTimeMillis()` |
| `SystemDataLoomMonotonicClock` | `DataLoomMonotonicClock` | JVM (also serves native Android today) | `System.nanoTime()`, normalized to a non-negative process-relative origin |
| `AppleDataLoomClock` | `DataLoomClock` | `iosArm64`, `iosSimulatorArm64`, `iosX64` | `clock_gettime(CLOCK_REALTIME, ...)` |
| `AppleDataLoomMonotonicClock` | `DataLoomMonotonicClock` | `iosArm64`, `iosSimulatorArm64`, `iosX64` | `clock_gettime(CLOCK_MONOTONIC, ...)` |

All four live in `dataloom-model`, alongside the contracts they implement, and
have no mutable state — safe to share a single instance across threads and
across a `DataLoom` instance's lifetime.

The JVM implementations currently serve native Android as well, because the
current Android adapter modules consume this module's JVM target directly;
there is no explicit KMP Android target yet (tracked separately as a platform
gap, not a clock gap).

None of these implementations are wired into `RuntimeDependencies` or
`DataLoomBuilder` automatically yet — an application must currently construct
and inject them explicitly. Automatic default wiring, and adopting
`DataLoomMonotonicClock` inside existing retry/timeout components that
currently measure elapsed time ad hoc, remain open follow-up work.

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

## Testing

Test utilities in `dataloom-testing` provide `DataLoomClock` implementations
for deterministic testing (`FixedDataLoomClock`, `MutableDataLoomClock`). See
[Clock and Identifier Test Utilities](../testing/clock-and-identifiers.md).
`dataloom-testing` does not yet provide an equivalent shared
`DataLoomMonotonicClock` fake; test authors currently write a small private
fake per test file. Consolidating that into `dataloom-testing` is open
follow-up work.
