# In-Memory Providers (DL-035)

## Overview

The DL-035 in-memory providers support tests that need stable, inspectable
provider state without real storage, transport, connectivity APIs, or queue
infrastructure.

## Providers

### `InMemoryStorageProvider`

- Records outbound read, inbound apply, acknowledgement, and checkpoint calls
- Stores checkpoints in memory
- Supports scripted storage operation results
- Supports checkpoint read and write failure injection

### `InMemoryQueueProvider`

- Preserves enqueue order with `LinkedHashMap`
- Implements enqueue, acquire, complete, reschedule, fail, cancel, and expired
  lease recovery transitions
- Allows cancellation of leased entries in tests for simpler orchestration
  scenarios
- Exposes snapshot helpers for entry IDs and states

### `MutableConnectivityProvider`

- Returns a mutable connectivity snapshot
- Records connectivity checks
- Can inject a constant failure result

## Limitations

> **These providers are for testing only.**

- **Memory-only.** Checkpoints, queue entries, and recorded requests exist only
  within the in-memory instance. No data survives process restart.
- **Caller-serialized.** Providers are not thread-safe. Serialize all mutations
  and inspections externally, or confine each instance to a single test.
- **Non-production.** Do not use these providers in production or release builds.
  They carry no durability, encryption, or transactional guarantee.
- **Not process-safe.** Queue state cannot be shared across processes.

## Example: scripting outbound synchronization

```kotlin
val storage = InMemoryStorageProvider()

storage.enqueueReadOutboundResult(
    ProviderOperationResult.Success(
        OutboundChangeReadResult.Changes(changeSet, hasMore = false)
    )
)
storage.enqueueAcknowledgeResult(ProviderOperationResult.Success(Unit))

// Call the production code under test, then inspect:
assertEquals(1, storage.readOutboundRequests.size)
assertEquals(changeSet, (storage.readOutboundRequests[0].request).let { it })
```

## Example: queue state machine

```kotlin
val queue = InMemoryQueueProvider()
val entryId  = QueueEntryId("job-001")
val leaseId  = QueueLeaseId("lease-001")

queue.enqueue(QueueEnqueueRequest(entry = pendingEntry))

val result = queue.acquire(acquireRequest)
val entries = (result as ProviderOperationResult.Success).value as QueueAcquireResult.Entries
assertEquals(QueueEntryState.LEASED, entries.entries.single().state)

queue.complete(QueueCompletionRequest(entryId, leaseId, completedAt))
assertEquals(mapOf(entryId to QueueEntryState.COMPLETED), queue.snapshotStates())
```

## Resetting state

Use `clearRecordings()` to keep provider state while removing request history.
Use `resetState()` to clear scripted results and in-memory state.
