# Observer Delivery Flow (DL-028)

This document describes the synchronization event observer delivery flow
introduced in `dataloom-runtime` by DL-028.

---

## Overview

DL-028 introduces a platform-independent event-delivery infrastructure that
connects the DataLoom runtime to registered `SynchronizationObserver`
implementations. Observers receive `SynchronizationEvent` notifications
sequentially in registration order.

This infrastructure does **not**:

- Generate synchronization events automatically.
- Persist or replay events.
- Expose `Flow`, `StateFlow`, `SharedFlow`, or `Channel`.
- Own a `CoroutineScope`.
- Perform asynchronous background fan-out.
- Invoke any provider or synchronization pipeline.

Runtime event generation and pipeline integration will be implemented in
DL-029.

---

## Standard delivery sequence

```text
SynchronizationEventDispatcher.dispatch(event)
    → SynchronizationObserverRegistry.observers (registration order)
    → Observer A.onEvent(event)        — success
    → Observer B.onEvent(event)        — success
    → Observer C.onEvent(event)        — success
    → SynchronizationEventDispatchResult.Delivered
```

**Delivered** is returned when every attempted observer received the event
successfully.

---

## No observers sequence

```text
SynchronizationEventDispatcher.dispatch(event)
    → SynchronizationObserverRegistry.observers (empty)
    → (no callbacks invoked)
    → SynchronizationEventDispatchResult.NoObservers
```

**NoObservers** is returned when the registry contains no observers. No
callback is invoked.

---

## Partial observer failure sequence

```text
SynchronizationEventDispatcher.dispatch(event)
    → SynchronizationObserverRegistry.observers (registration order)
    → Observer A.onEvent(event)        — success
    → Observer B.onEvent(event)        — throws ordinary Exception
        → SynchronizationObserverDispatchFailure recorded for B
        → delivery continues to C
    → Observer C.onEvent(event)        — success
    → SynchronizationEventDispatchResult.PartiallyDelivered
          summary: delivered=2, failed=1
          failures: [Failure(observerId=B, eventId=..., reason=OBSERVER_CALLBACK_FAILED)]
```

**PartiallyDelivered** is returned when at least one observer succeeded and at
least one failed with an ordinary exception. The failure is isolated; later
observers still receive the event.

---

## All observer callbacks failing sequence

```text
SynchronizationEventDispatcher.dispatch(event)
    → SynchronizationObserverRegistry.observers (registration order)
    → Observer A.onEvent(event)        — throws ordinary Exception
        → SynchronizationObserverDispatchFailure recorded for A
    → Observer B.onEvent(event)        — throws ordinary Exception
        → SynchronizationObserverDispatchFailure recorded for B
    → SynchronizationEventDispatchResult.DeliveryFailed
          summary: delivered=0, failed=2
          failures: [Failure(A), Failure(B)]   — invocation order preserved
```

**DeliveryFailed** is returned when at least one observer was attempted and
every attempted observer failed with an ordinary exception. All observers are
still attempted.

---

## Thrown CancellationException sequence

```text
SynchronizationEventDispatcher.dispatch(event)
    → SynchronizationObserverRegistry.observers (registration order)
    → Observer A.onEvent(event)        — success
    → Observer B.onEvent(event)        — throws CancellationException
        → CancellationException propagates normally
        → Observer C is NOT invoked
        → No SynchronizationEventDispatchResult is returned
        → No retry or redispatch occurs
```

`CancellationException` propagates normally out of `dispatch`. It is never
recorded as a `SynchronizationObserverDispatchFailure`. No normal result is
returned.

---

## Observer failure isolation rules

An ordinary observer callback failure (`Exception`, not `CancellationException`
or fatal `Error`) must not:

- Stop delivery to later observers.
- Fail synchronization execution.
- Change `SynchronizationResult`.
- Trigger retry.
- Trigger queue rescheduling.
- Invoke `SchedulerProvider`, `QueueProvider`, or any provider lifecycle.
- Dispatch another event automatically.

The failure is recorded as a `SynchronizationObserverDispatchFailure` and
delivery continues.

---

## Fatal-error propagation

A fatal `Error` (e.g., `OutOfMemoryError`) thrown by an observer callback
propagates normally. Later observers are not invoked. Fatal errors are never
converted into dispatch failures.

---

## Cancellation boundary

`CancellationException` is distinct from a `SynchronizationEvent.Completed`
event containing a cancelled `SynchronizationResult`:

| Condition | Behavior |
|---|---|
| Observer throws `CancellationException` | Propagates normally; no result produced |
| `SynchronizationEvent.Completed` with `SynchronizationResult.Cancelled` | Delivered as a normal event to all observers |

The dispatcher does not dispatch an explicit cancelled event automatically
when `CancellationException` is thrown.

---

## Event-variant neutrality

The dispatcher handles every `SynchronizationEvent` variant without special
selection logic:

| Variant | Behavior |
|---|---|
| `Started` | Delivered to all observers |
| `PhaseChanged` | Delivered to all observers |
| `ProgressUpdated` | Delivered to all observers |
| `RetryScheduled` | Delivered to all observers |
| `ConflictDetected` | Delivered to all observers |
| `Completed` | Delivered to all observers (not treated as registry shutdown) |

No variant is skipped or filtered. Observer selection never uses event type.

---

## Event immutability

The exact `SynchronizationEvent` instance is passed to every observer. No
copy is created. No field is modified. No variant is reconstructed.

Observers must treat events as immutable. The dispatcher does not provide
mutable event wrappers.

---

## Event-ordering boundary

| Scope | Responsibility |
|---|---|
| Within one `dispatch` call | Dispatcher guarantees registration order |
| Across multiple `dispatch` calls | Caller's responsibility |

The dispatcher maintains no global event queue and performs no cross-call
serialization. Concurrent callers may interleave unless serialized externally.

The dispatcher does not implement:

- Event sequence validation
- `Started`-before-`Completed` validation
- Phase-transition validation
- Progress monotonicity validation
- Duplicate event-ID rejection across `dispatch` calls
- Event replay or event buffering
- Event history

---

## Security and diagnostics

### Never exposed

- `DataLoomPayload` bytes
- Local or remote conflict payloads
- `SynchronizationRequest` metadata values
- Credentials or authorization headers
- Checkpoint tokens or encryption keys
- Personal data
- Stack traces
- Observer implementation state
- Observer `toString()` output
- Complete event `toString()` when nested values may be sensitive

### Safe diagnostics allowed

- `SynchronizationEventId`
- `SynchronizationObserverId`
- Event variant name
- `SynchronizationPhase`
- Delivery counts
- Dispatch-result variant
- Canonical `ErrorCode`

### Observer delivery error

When an ordinary observer callback exception occurs, a
`SynchronizationObserverDispatchFailure` is recorded with:

- `observerId` — the failing observer's ID.
- `eventId` — the event that could not be delivered.
- `reason` — `OBSERVER_CALLBACK_FAILED`.
- `error` — a canonical `DataLoomError` with a safe message, not containing
  the exception message, class name, or stack trace.

---

## Concurrency limitations

- No observer executes in parallel.
- No background fan-out occurs.
- The dispatcher owns no `CoroutineScope`.
- The dispatcher uses no thread pool, `launch`, `async`, or `GlobalScope`.
- Thread selection is the caller's responsibility.

A future adapter module may expose `Flow` without changing the core observer
dispatcher.

---

## Pipeline integration boundary

DL-028 does not integrate with:

- `SynchronizationExecutionCoordinator`
- `OutboundPushSynchronizationPipeline`
- `InboundPullSynchronizationPipeline`
- `BidirectionalSynchronizationPipeline`
- `SynchronizationRetryOrchestrator`
- `SynchronizationConflictOrchestrator`
- `DurableQueueExecutionProcessor`
- `QueuedSynchronizationExecutionHandler`

DL-029 will wire event emission into runtime execution.

---

## Kotlin Multiplatform compatibility

All components are `commonMain`-compatible. No Android API, JVM-only API,
reflection, `ServiceLoader`, or DI framework is used.
