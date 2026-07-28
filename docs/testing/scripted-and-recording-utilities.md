# Scripted and recording test utilities

> **Audience:** Developers asserting transport, retry, conflict, scheduling,
> and event interactions
> **Purpose:** Document ordered scripts, recordings, fallbacks, and reset
> semantics
> **Status:** Current test-only utilities; no real I/O or background dispatch

[← Testing toolkit](testing-toolkit.md) ·
[In-memory providers](in-memory-providers.md) ·
[Clocks and identifiers](clock-and-identifiers.md)

## Utility behavior

| Utility | Script or recording behavior |
|---|---|
| `ScriptedTransportProvider` | Dequeues push and pull results independently; exhaustion fails fast |
| `ScriptedRetryPolicy` | Dequeues retry decisions; optional fallback after exhaustion |
| `ScriptedConflictDetector` | Dequeues detection results; optional fallback after exhaustion |
| `ScriptedConflictResolver` | Dequeues resolution decisions; optional fallback after exhaustion |
| `RecordingSchedulerProvider` | Records schedule/cancel requests without dispatching platform work |
| `RecordingSynchronizationObserver` | Records events in order and optionally invokes a callback |

All mutable utilities are caller-serialized and memory-only. They do not
perform network I/O, sleep, schedule background work, or provide production
durability.

## Script transport and retry

```kotlin
val transport = ScriptedTransportProvider()
val retry = ScriptedRetryPolicy(RetryPolicyId("retry-test"))

transport.enqueuePushResult(
    ProviderOperationResult.Failure(transientError),
)
retry.enqueueDecision(
    RetryDecision.Retry(delay = SchedulingDelay(500L)),
)

// Run the production component under test.

assertEquals(pushRequest, transport.pushRequests.single())
assertEquals(retryRequest, retry.evaluationRequests.single())
```

`transientError`, `pushRequest`, and `retryRequest` are test-fixture values.
The test must explicitly drive the production retry orchestration; scripting a
decision does not schedule or repeat an operation.

## Script conflict handling

```kotlin
val detector = ScriptedConflictDetector(
    ConflictDetectorId("detector-test"),
)
val resolver = ScriptedConflictResolver(
    ConflictResolverId("resolver-test"),
)

detector.enqueueResult(
    ConflictDetectionResult.ConflictDetected(conflict),
)
resolver.enqueueDecision(
    ConflictResolutionDecision.UseLocal(),
)

val detection = detector.detect(detectionRequest)
val decision = resolver.resolve(resolutionRequest)

assertIs<ConflictDetectionResult.ConflictDetected>(detection)
assertIs<ConflictResolutionDecision.UseLocal>(decision)
assertEquals(detectionRequest, detector.detectionRequests.single())
assertEquals(resolutionRequest, resolver.resolutionRequests.single())
```

These fakes return decisions only. They do not persist a conflict, apply a
change, or implement application business rules.

## Record event order

```kotlin
val observer = RecordingSynchronizationObserver(
    SynchronizationObserverId("observer-test"),
)

events.forEach(observer::onEvent)

assertEquals(events, observer.events)
assertEquals(events.lastOrNull(), observer.latestEvent)
```

The optional observer callback runs after the event is recorded. Callback
exceptions propagate to the caller.

## Reset semantics

- `clearRecordings()` retains unconsumed scripts and removes prior call/event
  history.
- `resetState()` clears scripts plus recordings where the utility exposes it.
- `RecordingSynchronizationObserver` exposes `clearRecordings()` rather than
  `resetState()`.

Prefer a new utility instance for each test unless retained state is the
behavior under test.

## Strategy boundary

Scripted retry and conflict outcomes help test orchestration, but they are not
built-in offline-first, remote-first, cache-first, network-only, hybrid, or
adaptive policies. V1 strategy behavior requires production implementations
and cross-platform qualification.
