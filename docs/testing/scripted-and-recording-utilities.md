# Scripted and Recording Utilities (DL-035)

## Scripted utilities

### `ScriptedTransportProvider`

Scripts push and pull results independently. Exhaustion fails fast so tests do
not silently continue with unexpected behaviour.

### `ScriptedRetryPolicy`

Dequeues retry decisions in order and optionally falls back to a final decision
when the script is exhausted.

### `ScriptedConflictDetector` / `ScriptedConflictResolver`

Provide deterministic conflict outcomes without runtime I/O.

## Limitations

> **These utilities are for testing only.**

- **Memory-only.** Scripted results and recorded requests exist only within the
  in-memory instance.
- **Caller-serialized.** Utilities are not thread-safe. Confine each instance
  to a single test or serialize access externally.
- **Non-production.** Do not wire scripted or recording utilities into release
  builds. They carry no production intent.
- **No scheduling, I/O, or network.** `RecordingSchedulerProvider` records
  requests without dispatching work. `MutableConnectivityProvider` performs no
  real network check.

## Example: scripted transport with retry

```kotlin
val transport   = ScriptedTransportProvider()
val retryPolicy = ScriptedRetryPolicy(RetryPolicyId("policy-001"))

transport.enqueuePushResult(
    ProviderOperationResult.Failure(transientError)
)
retryPolicy.enqueueDecision(RetryDecision.Retry(delay = SchedulingDelay(500L)))
transport.enqueuePushResult(
    ProviderOperationResult.Success(acknowledgement)
)

// Run code under test, then assert:
assertEquals(2, transport.pushRequests.size)
assertEquals(1, retryPolicy.evaluationRequests.size)
```

## Example: conflict detection and resolution

```kotlin
val detector = ScriptedConflictDetector(ConflictDetectorId("detector-001"))
val resolver = ScriptedConflictResolver(ConflictResolverId("resolver-001"))

detector.enqueueResult(ConflictDetectionResult.Conflict(conflict))
resolver.enqueueDecision(ConflictResolutionDecision.UseLocal)

// Run code under test, then assert:
assertEquals(1, detector.detectionRequests.size)
assertEquals(1, resolver.resolutionRequests.size)
assertSame(conflict, detector.detectionRequests[0].localChange.let { it })
```

## Example: observer event ordering

```kotlin
val observer = RecordingSynchronizationObserver(SynchronizationObserverId("obs-001"))

// Emit events from code under test, then assert ordering:
assertEquals(3, observer.eventCount)
assertIs<SynchronizationEvent.Started>(observer.events[0])
assertIs<SynchronizationEvent.ProgressUpdated>(observer.events[1])
assertIs<SynchronizationEvent.Completed>(observer.events[2])
```

## Reset semantics

Call `clearRecordings()` to retain scripted results while resetting recorded
request lists. Call `resetState()` to clear both scripted results and
recordings.
