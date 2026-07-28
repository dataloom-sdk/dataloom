# Testing toolkit

> **Audience:** Contributors and application developers testing DataLoom
> integrations
> **Purpose:** Index deterministic fakes, scripts, recorders, clocks, and
> identifiers in `dataloom-testing`
> **Status:** Current test-only utilities; never production providers

[Project overview](../../README.md) ·
[Local build guide](../development/building.md) ·
[Apple testing](../apple/apple-testing.md)

`dataloom-testing` supplies platform-neutral utilities for shared unit and
integration-style tests. The module has no external service dependency and
owns no background coroutine scope.

## Guide map

| Guide | Focus |
|---|---|
| [Clocks and identifiers](clock-and-identifiers.md) | Deterministic time and ID generation |
| [In-memory providers](in-memory-providers.md) | Inspectable storage, queue, and connectivity state |
| [Scripted and recording utilities](scripted-and-recording-utilities.md) | Transport, retry, conflict, scheduler, and event assertions |

## Utility inventory

| Area | Utilities | Primary test use |
|---|---|---|
| Provider lifecycle | `TestProviderLifecycleController` | Script initialize/health/close outcomes and inspect calls |
| Storage | `InMemoryStorageProvider` | Script change operations and retain checkpoints in memory |
| Transport | `ScriptedTransportProvider` | Dequeue push/pull results and record requests |
| Queue | `InMemoryQueueProvider` | Exercise deterministic lease-aware state transitions |
| Scheduling | `RecordingSchedulerProvider` | Capture schedule/cancel requests without dispatching work |
| Connectivity | `MutableConnectivityProvider` | Change snapshots or inject a fixed failure |
| Retry | `ScriptedRetryPolicy` | Return ordered decisions or an explicit fallback |
| Conflict | `ScriptedConflictDetector`, `ScriptedConflictResolver` | Return ordered detection/resolution outcomes |
| Observation | `RecordingSynchronizationObserver` | Assert event order and callback behavior |
| Time | `FixedDataLoomClock`, `MutableDataLoomClock` | Control timestamps without sleeping |
| Identifiers | `SequenceIdentifierGenerator`, `ConstantIdentifierGenerator` | Remove randomness from creation paths |

## Guarantees

- All utilities are usable from common Kotlin tests.
- Inputs and calls are explicit and inspectable.
- No reflection, `ServiceLoader`, platform API, real network, or filesystem is
  used.
- No global mutable singleton is created.
- Script exhaustion fails fast unless a utility explicitly supports a
  fallback.
- Snapshot accessors return copies rather than mutable backing collections.

## Limitations

> These utilities are for tests only.

- Mutable utilities are caller-serialized, not thread-safe.
- State is memory-only and disappears with the process.
- No durability, encryption, transaction isolation, scheduler dispatch, or
  production lifecycle guarantee is provided.
- Do not package or wire `dataloom-testing` into a release application.

## Relationship to V1 strategies

The toolkit can script inputs and assert orchestration needed by future
strategy tests. It does not itself implement offline-first, remote-first,
cache-first, network-only, hybrid, or adaptive behavior.

V1 requires real built-in strategy tests and parity across native Android, KMP
Android, and KMP iOS. Fake-backed tests are necessary for determinism but
cannot replace platform persistence, lifecycle, network, background, and
relaunch qualification. Optional native Swift testing remains a separate
distribution concern.

## Typical pattern

```kotlin
val clock = MutableDataLoomClock(DataLoomInstant(1_000L))
val storage = InMemoryStorageProvider()
val transport = ScriptedTransportProvider()
val observer = RecordingSynchronizationObserver(
    SynchronizationObserverId("test-observer"),
)

storage.enqueueReadOutboundResult(
    ProviderOperationResult.Success(
        OutboundChangeReadResult.Changes(changeSet, hasMore = false),
    ),
)
transport.enqueuePushResult(
    ProviderOperationResult.Success(acknowledgement),
)

// Run the production component under test.

assertEquals(1, storage.readOutboundRequests.size)
assertEquals(1, transport.pushRequests.size)
assertEquals(expectedEvents, observer.events)
```

`changeSet`, `acknowledgement`, and the runtime invocation are
test-fixture-specific placeholders.

## Reset semantics

Use `clearRecordings()` when a utility should retain scripts or stored state
but forget previous calls. Use `resetState()` where available to clear both
recordings and mutable state/scripts. `RecordingSynchronizationObserver`
exposes `clearRecordings()` only.
