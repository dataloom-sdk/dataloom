# Testing Toolkit Overview (DL-035)

**Module:** `dataloom-testing`

## Overview

DL-035 extends the testing module with deterministic provider, policy, and
observer utilities for common tests. The toolkit remains multiplatform-friendly,
uses no external dependencies, and avoids platform-specific APIs.

## Included utilities

- `TestProviderLifecycleController`
- `InMemoryStorageProvider`
- `ScriptedTransportProvider`
- `InMemoryQueueProvider`
- `RecordingSchedulerProvider`
- `MutableConnectivityProvider`
- `ScriptedRetryPolicy`
- `ScriptedConflictDetector`
- `ScriptedConflictResolver`
- `RecordingSynchronizationObserver`

## Design goals

- Deterministic state transitions
- CommonMain compatibility
- No reflection or ServiceLoader usage
- No global mutable state
- Explicit recording and reset hooks for tests

## Limitations

> **These utilities are for testing only.**

- **Memory-only.** No state survives process restart. There is no persistence,
  encryption, or durability guarantee of any kind.
- **Caller-serialized.** Mutable test utilities are not thread-safe. Tests must
  serialize all mutation and inspection calls. Do not share a single instance
  across concurrent coroutines without external synchronization.
- **Non-production.** Do not wire these implementations into a production
  application or configure them in release builds.
- **No background work.** No owned `CoroutineScope`, background loop, thread,
  or polling mechanism exists. All behavior is synchronous and caller-driven.

## Typical usage

Use the utilities to script provider outcomes, inspect recorded requests, and
exercise runtime orchestration in unit or integration-style common tests.

```kotlin
val clock    = MutableDataLoomClock(DataLoomInstant(1_000L))
val storage  = InMemoryStorageProvider()
val transport = ScriptedTransportProvider()
val observer = RecordingSynchronizationObserver(SynchronizationObserverId("test"))

// Script the outcomes that the runtime will consume.
storage.enqueueReadOutboundResult(
    ProviderOperationResult.Success(
        OutboundChangeReadResult.Changes(changeSet, hasMore = false)
    )
)
storage.enqueueAcknowledgeResult(ProviderOperationResult.Success(Unit))
transport.enqueuePushResult(ProviderOperationResult.Success(acknowledgement))

// Wire utilities into the runtime, run synchronization, then assert.
val events  = observer.events           // List<SynchronizationEvent> in order
val reads   = storage.readOutboundRequests  // List<OutboundChangeReadRequest>
val pushes  = transport.pushRequests    // List<PushChangesRequest>
```

### Resetting between tests

```kotlin
// Clear recorded calls without changing scripted results or stored state.
storage.clearRecordings()
transport.clearRecordings()
observer.clearRecordings()

// Clear everything — scripted results, stored state, and recordings.
storage.resetState()
transport.resetState()
```
