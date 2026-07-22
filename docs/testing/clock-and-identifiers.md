# Clock and Identifier Test Utilities (DL-017)

**Package:** `io.dataloom.testing.time`, `io.dataloom.testing.identifier`

## Overview

The `dataloom-testing` module provides deterministic, platform-independent
implementations of `DataLoomClock` and `IdentifierGenerator<T>` for use in
unit and integration tests. These utilities remove all platform-clock
dependencies, all randomness, and all I/O from test scenarios.

---

## Fixed clock

### `FixedDataLoomClock`

**Package:** `io.dataloom.testing.time`

A `DataLoomClock` implementation that always returns a single configured
`DataLoomInstant`. The value never changes and is never automatically advanced.

```kotlin
val clock = FixedDataLoomClock(
    instant = DataLoomInstant(epochMilliseconds = 1_000L),
)

check(clock.now() == DataLoomInstant(1_000L))
check(clock.now() == DataLoomInstant(1_000L)) // always the same
```

**Use when:**

- The test only needs a single fixed timestamp.
- Time does not need to change during the test.
- Thread safety is required (the instance is immutable).

---

## Mutable clock

### `MutableDataLoomClock`

**Package:** `io.dataloom.testing.time`

A `DataLoomClock` implementation whose current instant can be updated during
the test. This enables tests to simulate time advancing, lease expiration, and
retry-delay scenarios without platform-clock access.

```kotlin
val clock = MutableDataLoomClock(
    initialInstant = DataLoomInstant(epochMilliseconds = 1_000L),
)

// Returns 1_000
check(clock.now().epochMilliseconds == 1_000L)

// Advance by 500 ms
clock.advanceBy(milliseconds = 500L)
check(clock.now().epochMilliseconds == 1_500L)

// Jump to a specific instant
clock.set(instant = DataLoomInstant(epochMilliseconds = 10_000L))
check(clock.now().epochMilliseconds == 10_000L)
```

### `advanceBy` rules

| Input | Result |
|---|---|
| Zero | Accepted; instant unchanged |
| Positive | Accepted; instant advanced |
| Negative | `IllegalArgumentException` thrown; instant unchanged |
| Overflow | `IllegalStateException` thrown; instant unchanged |

### Concurrency limitations

`MutableDataLoomClock` is mutable. Test code that shares a single instance
across threads must coordinate concurrent access externally. No
production-grade thread safety is implemented or claimed.

---

## Sequence generator

### `SequenceIdentifierGenerator<T>`

**Package:** `io.dataloom.testing.identifier`

An `IdentifierGenerator<T>` that returns values from a finite, pre-supplied
sequence. Values are returned in supplied order. When the sequence is
exhausted, `generate()` throws `NoSuchElementException`.

```kotlin
val generator = SequenceIdentifierGenerator(
    values = listOf(
        QueueEntryId("entry-001"),
        QueueEntryId("entry-002"),
        QueueEntryId("entry-003"),
    ),
)

check(generator.generate() == QueueEntryId("entry-001"))
check(generator.generate() == QueueEntryId("entry-002"))
check(generator.generate() == QueueEntryId("entry-003"))
// Next call throws NoSuchElementException
```

### Rules

- At least one value is required. Empty collections are rejected.
- The source collection is defensively copied at construction time.
- Values are returned in the order they were supplied.
- Each call consumes one value.
- The final value is not silently repeated after exhaustion.
- The sequence does not cycle automatically.

**Use when:**

- The test exercises multiple creation paths and each identifier must be
  distinct.
- The test needs to verify the exact identifiers produced by the component.

---

## Constant generator

### `ConstantIdentifierGenerator<T>`

**Package:** `io.dataloom.testing.identifier`

An `IdentifierGenerator<T>` that always returns the same configured value.

```kotlin
val generator = ConstantIdentifierGenerator(
    value = QueueEntryId("entry-fixed"),
)

check(generator.generate() == QueueEntryId("entry-fixed"))
check(generator.generate() == QueueEntryId("entry-fixed")) // always the same
```

**Use when:**

- The test exercises only one creation path.
- Identifier uniqueness is not under test.
- Thread safety is required (the instance is immutable).

Do not use `ConstantIdentifierGenerator` when the test verifies that
different calls produce different identifiers.

---

## Deterministic test examples

### Enqueue with fixed clock and sequence generator

```kotlin
val clock = FixedDataLoomClock(
    instant = DataLoomInstant(epochMilliseconds = 1_000L),
)
val entryGenerator = SequenceIdentifierGenerator(
    values = listOf(QueueEntryId("entry-001")),
)

val now = clock.now()
val id = entryGenerator.generate()

check(now == DataLoomInstant(1_000L))
check(id == QueueEntryId("entry-001"))
```

### Lease expiry with mutable clock

```kotlin
val clock = MutableDataLoomClock(
    initialInstant = DataLoomInstant(epochMilliseconds = 0L),
)

val acquiredAt = clock.now()
clock.advanceBy(milliseconds = 30_000L)
val checkTime = clock.now()

check(checkTime.epochMilliseconds > acquiredAt.epochMilliseconds)
```

---

## Concurrency limitations

| Utility | Thread safe |
|---|---|
| `FixedDataLoomClock` | Yes (immutable) |
| `MutableDataLoomClock` | No (mutable; coordinate externally) |
| `SequenceIdentifierGenerator` | No (mutable index; coordinate externally) |
| `ConstantIdentifierGenerator` | Yes (immutable) |

All test utilities in this module are intended for deterministic testing only.
Do not use them in production code.
