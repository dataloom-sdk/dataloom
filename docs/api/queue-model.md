# DataLoom Queue Models (DL-015)

This document describes the immutable public contracts for durable queue entry
lifecycle management introduced in DL-015.

---

## Overview

The DataLoom durable queue tracks synchronization work through a defined
lifecycle. Each queue entry carries its synchronization request, state,
timing, lease, and error history.

These contracts are platform-independent and safe for use in Kotlin
Multiplatform common code.

---

## DataLoomInstant

**Package:** `io.dataloom.api.time`  
**Type:** `DataLoomInstant` (class with value-based equality)

An immutable, platform-independent representation of an absolute point in time
expressed as milliseconds since the Unix epoch (1970-01-01T00:00:00Z).

### Members

| Member | Type | Description |
|---|---|---|
| `epochMilliseconds` | `Long` | Non-negative milliseconds since the Unix epoch. |

### Rules

- `epochMilliseconds` must be zero or greater. Negative values are rejected.
- Construction does not read the system clock.
- Does not represent a duration.
- Does not depend on `java.time` or any third-party date-time library.
- Equality compares `epochMilliseconds` by value.

---

## Queue Identifiers

**Package:** `io.dataloom.api.identifier`

Three immutable value types identify queue participants:

| Type | Ownership |
|---|---|
| `QueueEntryId` | DataLoom runtime or host integration |
| `QueueLeaseId` | DataLoom runtime |
| `QueueConsumerId` | Runtime worker or platform integration |

### Rules (all three)

- Wraps a non-blank `String`.
- Blank and whitespace-only values are rejected.
- Valid input is preserved exactly as supplied.
- No normalization or automatic generation is applied.
- `toString()` returns the underlying value.

---

## RetryAttempt

**Package:** `io.dataloom.api.retry`  
**Type:** `RetryAttempt` (class with value-based equality)

An immutable counter representing the number of processing attempts for a
queue entry. Introduced to support `QueueEntry` and `QueueRescheduleRequest`.

### Members

| Member | Type | Description |
|---|---|---|
| `number` | `Int` | Attempt number, starting at 1 for the first retry. |

### Rules

- `number` must be greater than zero. Zero and negative values are rejected.
- Construction does not read the clock, sleep, or schedule work.
- The DataLoom runtime supplies the value after evaluating retry policy.

---

## QueueEntryState

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueEntryState` (enum class)

Closed set of lifecycle states for a `QueueEntry`.

| State | Description |
|---|---|
| `PENDING` | Eligible for future acquisition when availability requirements are met. |
| `LEASED` | Exclusively acquired by a consumer for processing. |
| `RETRY_WAITING` | Waiting until its next eligible execution instant after a failure. |
| `COMPLETED` | Synchronization work completed successfully. Terminal state. |
| `FAILED` | Processing failed and the entry is not scheduled for retry. Terminal state. |
| `CANCELLED` | Cancelled before successful completion. Terminal state. |
| `DEAD_LETTER` | Retained for inspection after exhausting normal processing. Terminal state. |

### Rules

- Enum ordinals are not a compatibility contract and must not be persisted.
- Use the enum name for persistence and serialization.
- Transitions are not implemented inside the enum.
- Terminal entries are not automatically removed.
- Retention policy is deferred.

---

## QueueLease

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueLease` (data class)

Immutable exclusive lease held by a consumer over a `QueueEntry`.

### Members

| Member | Type | Required | Description |
|---|---|---|---|
| `id` | `QueueLeaseId` | Yes | Unique lease identifier. |
| `consumerId` | `QueueConsumerId` | Yes | Consumer holding this lease. |
| `acquiredAt` | `DataLoomInstant` | Yes | Instant at which the lease was acquired. |
| `expiresAt` | `DataLoomInstant` | Yes | Instant at which the lease expires. |

### Rules

- All properties are required.
- `expiresAt` must be strictly later than `acquiredAt`.
- Equal and earlier expiration values are rejected.
- Construction does not access the clock or generate identifiers.
- Lease renewal is deferred to a future issue.

---

## QueueEntry

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueEntry` (data class)

Immutable model for a single durable synchronization queue entry.

### Members

| Member | Type | Required | Default | Description |
|---|---|---|---|---|
| `id` | `QueueEntryId` | Yes | — | Unique entry identifier. |
| `synchronizationRequest` | `SynchronizationRequest` | Yes | — | Synchronization intent. |
| `state` | `QueueEntryState` | Yes | — | Current lifecycle state. |
| `enqueuedAt` | `DataLoomInstant` | Yes | — | Instant at which the entry was enqueued. |
| `availableAt` | `DataLoomInstant` | Yes | — | Instant at which the entry becomes eligible for acquisition. |
| `retryAttempt` | `RetryAttempt?` | No | `null` | Retry attempt counter. |
| `lease` | `QueueLease?` | No | `null` | Active exclusive lease. |
| `lastError` | `DataLoomError?` | No | `null` | Last canonical processing error. |
| `metadata` | `DataLoomMetadata` | No | `Empty` | Contextual attributes. |

### State invariants (enforced at construction)

| Invariant | Description |
|---|---|
| `LEASED` requires non-null `lease` | An actively leased entry must have a lease. |
| Non-`LEASED` states require null `lease` | All other states must have no lease. |
| `RETRY_WAITING` requires non-null `retryAttempt` | Retry-waiting entries must have an attempt count. |
| `PENDING` must have null `retryAttempt` | Newly enqueued entries have no retry history. |
| `availableAt >= enqueuedAt` | Availability instant cannot precede enqueue instant. |

### Rules

- Construction does not enqueue the entry, read the clock, schedule execution,
  evaluate retry policy, or perform synchronization.
- `FAILED` and `DEAD_LETTER` may carry a `lastError`.
- `RETRY_WAITING` typically carries the error that caused retry scheduling.

---

## QueueEnqueueRequest

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueEnqueueRequest` (data class)

Immutable request to persist a new queue entry.

### Members

| Member | Type | Required | Description |
|---|---|---|---|
| `entry` | `QueueEntry` | Yes | Entry to enqueue. Must be in `PENDING` state. |

### Rules

- Entry must be in `PENDING` state.
- Entry must not contain a lease.
- Entry must not contain a retry attempt.
- Construction does not persist the entry.
- Duplicate-entry handling belongs to the `QueueProvider` implementation.
- A provider must return a canonical error rather than silently replacing an
  existing entry unless future policy explicitly permits replacement.

---

## QueueAcquireRequest

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueAcquireRequest` (data class)

Immutable request to atomically acquire eligible queue entries.

### Members

| Member | Type | Required | Default | Description |
|---|---|---|---|---|
| `consumerId` | `QueueConsumerId` | Yes | — | Consumer performing acquisition. |
| `leaseId` | `QueueLeaseId` | Yes | — | Lease identifier for this batch. |
| `acquiredAt` | `DataLoomInstant` | Yes | — | Acquisition instant. |
| `leaseExpiresAt` | `DataLoomInstant` | Yes | — | Lease expiration instant. |
| `maxEntries` | `Int` | Yes | — | Maximum entries to acquire. |
| `metadata` | `DataLoomMetadata` | No | `Empty` | Contextual attributes. |

### Rules

- `leaseExpiresAt` must be strictly later than `acquiredAt`.
- `maxEntries` must be greater than zero.
- The same `leaseId` may be shared across all entries in one atomic batch.
- Construction does not acquire work.

---

## QueueAcquireResult

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueAcquireResult` (sealed interface)

Sealed result of a queue acquisition operation.

### Variants

#### `NoEntries`

`data object NoEntries : QueueAcquireResult`

No currently eligible queue entries were available.

#### `Entries`

`class Entries(val lease: QueueLease, entries: List<QueueEntry>) : QueueAcquireResult`

One or more entries were atomically acquired and leased.

| Member | Type | Description |
|---|---|---|
| `lease` | `QueueLease` | Exclusive lease assigned to all acquired entries. |
| `entries` | `List<QueueEntry>` | Immutable defensive copy of acquired entries. |

### Rules

- `Entries.entries` must be non-empty. Empty collections are rejected.
- Every returned entry must be in `LEASED` state.
- Every returned entry's `lease` must equal the result `lease`.
- The source collection is defensively copied.
- No mutable collection is exposed.
- Entries preserve provider-returned order.

---

## QueueCompletionRequest

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueCompletionRequest` (data class)

Immutable request to mark a leased entry as successfully completed.

### Members

| Member | Type | Required | Default | Description |
|---|---|---|---|---|
| `entryId` | `QueueEntryId` | Yes | — | Entry to complete. |
| `leaseId` | `QueueLeaseId` | Yes | — | Active lease identifier. |
| `completedAt` | `DataLoomInstant` | Yes | — | Completion instant. |
| `metadata` | `DataLoomMetadata` | No | `Empty` | Contextual attributes. |

### Rules

- Construction does not update storage.
- The provider must verify that `leaseId` matches the current active lease.
- Stale or mismatched lease identifiers must fail canonically.

---

## QueueRescheduleRequest

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueRescheduleRequest` (data class)

Immutable request to reschedule a failed entry for a future retry attempt.

### Members

| Member | Type | Required | Default | Description |
|---|---|---|---|---|
| `entryId` | `QueueEntryId` | Yes | — | Entry to reschedule. |
| `leaseId` | `QueueLeaseId` | Yes | — | Active lease identifier. |
| `retryAttempt` | `RetryAttempt` | Yes | — | Attempt counter from retry policy evaluation. |
| `availableAt` | `DataLoomInstant` | Yes | — | Next eligibility instant from retry policy. |
| `error` | `DataLoomError` | Yes | — | Error that caused the retry. |
| `metadata` | `DataLoomMetadata` | No | `Empty` | Contextual attributes. |

### Rules

- Construction does not schedule or delay execution.
- The runtime supplies `retryAttempt` and `availableAt` after evaluating retry
  policy externally.
- The provider must verify the active lease and must not evaluate retry policy.
- A successful operation transitions the entry to `RETRY_WAITING` and clears
  the active lease.

---

## QueueFailureDisposition

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueFailureDisposition` (enum class)

Closed set of failure disposition choices.

| Value | Resulting state | Description |
|---|---|---|
| `FAILED` | `QueueEntryState.FAILED` | Permanent failure; will not be retried automatically. |
| `DEAD_LETTER` | `QueueEntryState.DEAD_LETTER` | Retained for operator inspection, replay, or deletion. |

### Rules

- Enum ordinals are not a compatibility contract and must not be persisted.
- The runtime or host policy supplies the disposition.
- The provider does not choose the disposition automatically.

---

## QueueFailureRequest

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueFailureRequest` (data class)

Immutable request to mark a leased entry as permanently failed or dead-lettered.

### Members

| Member | Type | Required | Default | Description |
|---|---|---|---|---|
| `entryId` | `QueueEntryId` | Yes | — | Entry to fail. |
| `leaseId` | `QueueLeaseId` | Yes | — | Active lease identifier. |
| `error` | `DataLoomError` | Yes | — | Canonical error describing the failure. |
| `disposition` | `QueueFailureDisposition` | Yes | — | Failure disposition. |
| `metadata` | `DataLoomMetadata` | No | `Empty` | Contextual attributes. |

### Rules

- Construction does not mutate storage.
- The provider must verify the active lease.
- A successful operation clears the lease.
- The provider must not evaluate retry policy or recoverability.

---

## QueueCancellationRequest

**Package:** `io.dataloom.api.queue`  
**Type:** `QueueCancellationRequest` (data class)

Immutable request to cancel a queue entry.

### Members

| Member | Type | Required | Default | Description |
|---|---|---|---|---|
| `entryId` | `QueueEntryId` | Yes | — | Entry to cancel. |
| `context` | `ExecutionContext` | Yes | — | Execution context for this request. |
| `metadata` | `DataLoomMetadata` | No | `Empty` | Contextual attributes. |

### Rules

- Construction does not cancel an entry.
- Cancellation of an actively leased entry may fail or be deferred according
  to provider and runtime policy.
- Cancellation does not automatically cancel a running coroutine.
- Runtime execution cancellation is deferred to a future issue.

---

## ExpiredLeaseRecoveryRequest

**Package:** `io.dataloom.api.queue`  
**Type:** `ExpiredLeaseRecoveryRequest` (data class)

Immutable request to recover queue entries whose leases have expired.

### Members

| Member | Type | Required | Default | Description |
|---|---|---|---|---|
| `currentTime` | `DataLoomInstant` | Yes | — | Current instant used to identify expired leases. |
| `metadata` | `DataLoomMetadata` | No | `Empty` | Contextual attributes. |

### Rules

- Construction does not access storage or read the system clock.
- The provider compares each leased entry's `expiresAt` against `currentTime`
  to determine which entries are eligible for recovery.

---

## ExpiredLeaseRecoveryResult

**Package:** `io.dataloom.api.queue`  
**Type:** `ExpiredLeaseRecoveryResult` (data class)

Immutable result of an expired-lease recovery operation.

### Members

| Member | Type | Description |
|---|---|---|
| `recoveredEntries` | `Int` | Count of entries recovered from expired leases. |

### Rules

- `recoveredEntries` must be zero or greater. Negative values are rejected.
- Equality compares `recoveredEntries` by value.
- No mutable collection is exposed.

---

## Related Documentation

- [Queue Provider](./queue-provider.md) — `QueueProvider` SPI.
- [Queue Boundaries](../architecture/queue-boundaries.md) — Architectural boundaries.
- [Provider SPI](./provider-spi.md) — DataLoom provider framework.
- [Error Model](./error-model.md) — Canonical `DataLoomError`.
- [Retry Policy](./retry-policy.md) — Retry-policy contracts (DL-013, deferred).
