# Test clocks and identifier generators

> **Audience:** Developers writing deterministic time- and ID-sensitive tests
> **Purpose:** Document the `dataloom-testing` clock and generator semantics
> **Status:** Current test-only utilities; not production randomness or clock
> implementations

[← Testing toolkit](testing-toolkit.md) ·
[In-memory providers](in-memory-providers.md) ·
[Local build guide](../development/building.md)

Packages:

- `io.dataloom.testing.time`
- `io.dataloom.testing.identifier`

## Choose a utility

| Utility | Behavior | Mutable | Thread-safe |
|---|---|---:|---:|
| `FixedDataLoomClock` | Always returns one instant | No | Yes |
| `MutableDataLoomClock` | Test controls current instant | Yes | No |
| `SequenceIdentifierGenerator<T>` | Consumes a finite ordered sequence | Yes | No |
| `ConstantIdentifierGenerator<T>` | Always returns one value | No | Yes |

## Fixed clock

Use `FixedDataLoomClock` when the entire test can share one timestamp:

```kotlin
val clock = FixedDataLoomClock(
    instant = DataLoomInstant(epochMilliseconds = 1_000L),
)

assertEquals(DataLoomInstant(1_000L), clock.now())
assertEquals(DataLoomInstant(1_000L), clock.now())
```

The clock never advances automatically and never reads a platform clock.

## Mutable clock

Use `MutableDataLoomClock` to model lease expiry, retry delays, or event
ordering without sleeping:

```kotlin
val clock = MutableDataLoomClock(
    initialInstant = DataLoomInstant(epochMilliseconds = 1_000L),
)

clock.advanceBy(milliseconds = 500L)
assertEquals(DataLoomInstant(1_500L), clock.now())

clock.set(DataLoomInstant(epochMilliseconds = 10_000L))
assertEquals(DataLoomInstant(10_000L), clock.now())
```

`advanceBy` behavior:

| Input | Outcome |
|---|---|
| Zero | Accepted; time does not change |
| Positive | Added to the current epoch milliseconds |
| Negative | `IllegalArgumentException`; state is unchanged |
| `Long` overflow | `IllegalStateException`; state is unchanged |

Coordinate access externally if the same mutable clock is used by concurrent
test code.

## Sequence generator

Use `SequenceIdentifierGenerator<T>` when each creation path needs a known,
distinct value:

```kotlin
val ids = SequenceIdentifierGenerator(
    values = listOf(
        QueueEntryId("entry-001"),
        QueueEntryId("entry-002"),
    ),
)

assertEquals(QueueEntryId("entry-001"), ids.generate())
assertEquals(QueueEntryId("entry-002"), ids.generate())
```

The input must contain at least one value and is defensively copied. Each call
consumes one value. Exhaustion throws `NoSuchElementException`; the sequence
does not repeat or cycle.

## Constant generator

Use `ConstantIdentifierGenerator<T>` only when uniqueness is irrelevant:

```kotlin
val ids = ConstantIdentifierGenerator(
    value = QueueEntryId("entry-fixed"),
)

assertEquals(QueueEntryId("entry-fixed"), ids.generate())
assertEquals(QueueEntryId("entry-fixed"), ids.generate())
```

Do not use a constant generator in a test that is intended to prove unique ID
generation.

## Production boundary

These utilities deliberately remove real time and randomness. Production hosts
must supply appropriate clock and identifier implementations. None of these
classes provides cryptographic randomness, monotonic time, persistence, or
cross-process coordination.

## Related documentation

- [Testing toolkit](testing-toolkit.md)
- [Runtime dependencies](../architecture/runtime-dependencies.md)
