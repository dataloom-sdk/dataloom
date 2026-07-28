# In-memory test providers

> **Audience:** Developers testing provider orchestration without platform I/O
> **Purpose:** Define inspectable storage, queue, and connectivity fake
> behavior
> **Status:** Current test-only implementations; memory-only and
> caller-serialized

[← Testing toolkit](testing-toolkit.md) ·
[Clocks and identifiers](clock-and-identifiers.md) ·
[Scripted utilities](scripted-and-recording-utilities.md)

## Providers

| Provider | State and recordings |
|---|---|
| `InMemoryStorageProvider` | Scripts change-operation results, records requests, and stores checkpoints |
| `InMemoryQueueProvider` | Stores entries in insertion order and implements lease-aware transitions |
| `MutableConnectivityProvider` | Returns a mutable snapshot, records checks, or returns a fixed failure |

All three reuse `TestProviderLifecycleController` for deterministic lifecycle
behavior.

## Limitations

> Do not use these providers in production or release builds.

- Nothing survives process termination.
- Instances are mutable and not thread-safe; confine each to one test or
  serialize access.
- There is no encryption, real transaction isolation, multi-process sharing,
  platform callback, or production scheduler.
- Behavior is intentionally inspectable and may be more permissive than a
  platform provider.

One deliberate difference is cancellation: `InMemoryQueueProvider` permits a
leased entry to be cancelled for test convenience, while the current Room
provider accepts cancellation only from `PENDING` or `RETRY_WAITING`. Use
platform-provider tests when that distinction matters.

## Storage example

```kotlin
val storage = InMemoryStorageProvider()

storage.enqueueReadOutboundResult(
    ProviderOperationResult.Success(
        OutboundChangeReadResult.Changes(changeSet, hasMore = false),
    ),
)

val result = storage.readOutboundChanges(outboundRequest)

assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
assertEquals(outboundRequest, storage.readOutboundRequests.single())
```

`changeSet` and `outboundRequest` are supplied by the surrounding test fixture.
When no outbound result is scripted, the provider returns
`OutboundChangeReadResult.NoChanges`.

## Queue example

```kotlin
val queue = InMemoryQueueProvider()

queue.enqueue(QueueEnqueueRequest(entry = pendingEntry))
queue.acquire(acquireRequest)

assertEquals(
    QueueEntryState.LEASED,
    queue.snapshotStates()[pendingEntry.id],
)

queue.complete(
    QueueCompletionRequest(
        entryId = pendingEntry.id,
        leaseId = acquireRequest.leaseId,
        completedAt = completedAt,
    ),
)

assertEquals(
    QueueEntryState.COMPLETED,
    queue.snapshotStates()[pendingEntry.id],
)
```

`pendingEntry`, `acquireRequest`, and `completedAt` are test-fixture values.
Acquisition orders eligible entries by availability and then insertion order.

## Connectivity example

```kotlin
val connectivity = MutableConnectivityProvider(
    initialSnapshot = ConnectivitySnapshot(
        status = ConnectivityStatus.AVAILABLE,
        isMetered = false,
    ),
)

connectivity.setSnapshot(
    ConnectivitySnapshot(
        status = ConnectivityStatus.UNAVAILABLE,
        isMetered = null,
    ),
)
```

The provider performs no Android or Apple network query.

## Reset state safely

- `clearRecordings()` retains stored/provider state and removes request history.
- `resetState()` removes recordings plus stored entries, checkpoints, scripts,
  or overrides owned by that provider.

Create a fresh instance per test when state sharing is not intentional.

## Related documentation

- [Testing toolkit](testing-toolkit.md)
- [Room queue provider](../android/room-queue-provider.md)
