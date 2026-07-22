# DataLoom Queue Boundaries (DL-015)

This document describes the architectural boundaries of the DataLoom durable
synchronization queue and defines what belongs to the queue provider, the
runtime, and the host application.

---

## Queue Provider vs. Storage Provider

DataLoom uses two distinct provider types for persistence. They must not be
confused:

| Concern | Provider | Owned by |
|---|---|---|
| Application domain data (entities, changes) | `StorageProvider` | Host application |
| DataLoom workflow execution records | `QueueProvider` | DataLoom runtime |

```text
StorageProvider
→ Reads and applies application synchronization changes

QueueProvider
→ Persists DataLoom workflow execution records
```

A Room implementation may physically reside in the same database file, but
schemas and ownership boundaries must remain separate.

---

## Queue Provider Boundary

`QueueProvider` owns:

- Persisting `QueueEntry` records in durable storage.
- Atomically acquiring eligible entries and assigning exclusive leases.
- Completing, rescheduling, failing, and cancelling entries.
- Recovering entries whose leases have expired after process death.
- Validating active lease identifiers on all lease-protected operations.

`QueueProvider` must not:

- Execute synchronization work.
- Call `StorageProvider` or `TransportProvider`.
- Evaluate retry policy or choose retry delays.
- Choose failure dispositions.
- Resolve conflicts.
- Automatically remove terminal entries.
- Select threads or dispatchers.
- Expose platform-specific types through its public API.

---

## Runtime Boundary

The DataLoom runtime owns:

- Priority ordering and fairness among eligible entries.
- Workflow-specific concurrency limits.
- Tenant isolation.
- Batching and starvation prevention.
- Dependency ordering.
- Evaluating `RetryPolicy` and supplying `RetryAttempt` and `availableAt`
  to `QueueProvider.reschedule`.
- Choosing `QueueFailureDisposition` based on policy.
- Triggering `recoverExpiredLeases` after detecting potential process death.

---

## Atomic Acquisition Boundary

Acquiring eligible queue entries and assigning their lease must be **one
atomic provider operation**.

A provider must not:

1. Read eligible entries.
2. Return them.
3. Assign the lease later in a separate operation.

That pattern could allow multiple consumers to process the same entry
concurrently.

Eligible entries are conceptually:

```text
PENDING where availableAt <= acquiredAt
```

or:

```text
RETRY_WAITING where availableAt <= acquiredAt
```

Exact ordering, priority, and selection policy are owned by the future
DataLoom queue runtime.

---

## Retry Boundary

`QueueProvider` persists retry state but does not evaluate retry policy.

```text
QueueProvider.acquire()
      ↓
Runtime performs synchronization
      ↓
Failure
      ↓
RetryPolicy.evaluate()
      ↓
RetryDecision.Retry
      ↓
QueueProvider.reschedule()
```

or:

```text
RetryDecision.Stop
      ↓
QueueProvider.fail()
```

`QueueProvider.reschedule` receives a `RetryAttempt` and `availableAt`
supplied by the runtime. It must persist them without re-evaluating policy.

---

## Process-Death Recovery Boundary

Process death may leave entries permanently stuck in `LEASED` state if leases
are not recovered. Recovery is a `QueueProvider` responsibility triggered by
the runtime.

```text
Entry is LEASED
      ↓
Process terminates
      ↓
Lease expires
      ↓
recoverExpiredLeases(...)
      ↓
Entry becomes recoverable
```

Recovery requirements:

- Based on persisted lease information only.
- In-memory state must not be assumed to survive.
- Unexpired leases must not be recovered.
- Concrete implementations must document the recovered state transition
  (`PENDING` or `RETRY_WAITING`) and their transactional guarantees.

---

## Application Storage Boundary

- Application UI must **not** query `QueueProvider` for business data.
- DataLoom queue records must **not** become application domain entities.
- A Room implementation may physically use the same database, but schemas and
  ownership boundaries must remain separate.
- Queue payloads and metadata may be sensitive and require secure storage
  according to application policy.

---

## Queue Ordering Boundary

`QueueProvider` may preserve a deterministic storage order, but must not
claim that this defines the final DataLoom scheduling policy.

The future queue runtime determines:

- Priority ordering.
- Fairness.
- Workflow-specific limits.
- Tenant isolation.
- Concurrency limits.
- Dependency ordering.
- Batching.
- Starvation prevention.

Enum ordinals in `QueueEntryState`, `QueueFailureDisposition`, and
`QueueEntryState` must not be persisted as ordering signals.

---

## Platform Integration Boundary

### Android

A future `dataloom-room` module may implement `QueueProvider` using Room.

A future WorkManager integration may:
1. Acquire leased work via `QueueProvider.acquire`.
2. Execute synchronization through the shared runtime.
3. Complete, reschedule, or fail the entry.

WorkManager, Worker, AlarmManager, JobScheduler, or other platform-specific
types must not be exposed through the `QueueProvider` public API.

### KMP

A future `dataloom-sqldelight` module may implement `QueueProvider` using
SQLDelight for cross-platform durable persistence.

---

## Related Documentation

- [Queue Models](../api/queue-model.md) — Queue model contracts.
- [Queue Provider](../api/queue-provider.md) — `QueueProvider` SPI.
- [Storage Provider](../api/storage-provider.md) — Application storage adapter.
- [Provider SPI](../api/provider-spi.md) — DataLoom provider framework.
