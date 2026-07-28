# DataLoom Synchronization Event Dispatcher (DL-028)

[API reference index](./README.md)

> **Status:** Available synchronous in-process dispatch foundation. Durable
> delivery, replay, filtering, bounded buffering, and consumer isolation remain
> V1 gaps.

This document defines the synchronization event dispatcher and observer
registry introduced in `dataloom-runtime` by DL-028.

These components provide the infrastructure for delivering
`SynchronizationEvent` instances to registered `SynchronizationObserver`
implementations in a deterministic, sequential, and failure-isolated manner.

---

## Overview

DL-028 provides:

- `SynchronizationObserverRegistry` — immutable ordered collection of
  registered observers.
- `SynchronizationObserverDispatchFailureReason` — reason classification for
  observer callback failures.
- `SynchronizationObserverDispatchFailure` — immutable structural record of a
  single observer delivery failure.
- `SynchronizationEventDispatchSummary` — immutable delivery count summary.
- `SynchronizationEventDispatchResult` — sealed result contract for a single
  dispatch invocation.
- `SynchronizationEventDispatcher` — performs sequential event delivery.

The dispatcher itself implements event delivery only. It does not:

- Generate synchronization events.
- Execute or modify synchronization pipelines.
- Decide when push, pull, retry, conflict, or queue events should exist.
- Persist or replay events.
- Expose `Flow`, `StateFlow`, `SharedFlow`, or `Channel`.
- Own a `CoroutineScope`.
- Perform asynchronous background fan-out.
- Modify `SynchronizationResult`.
- Invoke any provider.

Runtime lifecycle and selected operational integrations now call this
dispatcher through injected emitters. Those integrations do not add
persistence, replay, buffering, filtering, or cross-call ordering to the
dispatcher.

---

## SynchronizationObserverRegistry

`SynchronizationObserverRegistry` is an immutable ordered collection of
`SynchronizationObserver` instances.

### Purpose

The registry holds all application-supplied observers and provides exact
ID-based lookup and ordered iteration for `SynchronizationEventDispatcher`.

### Unique observer identifiers

Each observer must have a unique `SynchronizationObserverId` within one
registry. Duplicate IDs cause construction to throw `IllegalArgumentException`
immediately, before any observer is invoked.

### Registration-order preservation

Observers are stored and iterated in the order they were supplied at
construction time. This order determines the delivery sequence within each
`dispatch` call.

### Exact ID lookup

`lookup(id: SynchronizationObserverId)` returns the registered observer for
the given ID, or `null` when no observer is registered for that ID.

Observers are never selected by class name, `toString()`, hash order, event
type, `SynchronizationPhase`, or identifier sorting.

### Defensive collection copy

The caller-provided collection is defensively copied at construction time.
Post-construction mutations to the original collection do not affect the
registry.

### No mutable collection exposure

The `observers` property returns a read-only snapshot. No mutable collection
is accessible through any public API.

### Construction restrictions

Construction performs no event delivery, invokes no observer callback, uses
no global registry, no reflection, no `ServiceLoader`, and no
dependency-injection framework.

```kotlin
val registry = SynchronizationObserverRegistry(
    observers = listOf(analyticsObserver, debugObserver),
)

val found: SynchronizationObserver? = registry.lookup(SynchronizationObserverId("analytics"))
val ordered: List<SynchronizationObserver> = registry.observers
```

---

## SynchronizationEventDispatcher

`SynchronizationEventDispatcher` delivers a `SynchronizationEvent` to every
registered observer in registration order and returns a structured result.

### Constructor

```kotlin
public class SynchronizationEventDispatcher(
    private val observerRegistry: SynchronizationObserverRegistry,
)
```

### Operation

```kotlin
public fun dispatch(event: SynchronizationEvent): SynchronizationEventDispatchResult
```

`dispatch` is a regular (non-suspend) function because
`SynchronizationObserver.onEvent` is synchronous.

### Sequential delivery

Observers execute sequentially in registration order. No observer executes in
parallel. No background fan-out occurs.

### Exact event-instance preservation

The exact `SynchronizationEvent` instance is passed to every registered
observer. No copy is created, no field is modified, and no variant is
reconstructed.

### Ordinary observer failure isolation

When `SynchronizationObserver.onEvent` throws an ordinary exception (any
`Exception` that is not `CancellationException`):

- The exception is isolated.
- A `SynchronizationObserverDispatchFailure` is recorded.
- Delivery continues to the next observer.
- A normal `SynchronizationEventDispatchResult` is returned.

### Cancellation propagation

`CancellationException` thrown by any observer callback propagates normally
out of `dispatch`. Later observers are not invoked. Cancellation is never
recorded as a dispatch failure and no normal result is returned.

### Fatal-error propagation

A fatal `Error` thrown by any observer callback propagates normally. Later
observers are not invoked. Fatal errors are never converted into dispatch
failures.

### Event-variant neutrality

`dispatch` delivers every `SynchronizationEvent` variant without special
selection logic:

- `Started`
- `PhaseChanged`
- `ProgressUpdated`
- `RetryScheduled`
- `ConflictDetected`
- `Completed`

No variant is skipped, filtered, or handled differently.

### Event-ordering boundary

Within one `dispatch` call, observers execute in registration order.

Across multiple `dispatch` calls, ordering is the **caller's responsibility**.
This dispatcher maintains no global event queue and performs no cross-call
serialization. Concurrent callers may interleave unless serialized externally.

---

## SynchronizationEventDispatchResult

`SynchronizationEventDispatchResult` is a sealed interface with four variants:

### NoObservers

The registry contained no observers. No callback was invoked.

- `summary` contains all-zero counts.
- `failures` is always empty.

### Delivered

Every attempted observer received the event successfully.

- `summary.failedObserverCount == 0`
- `summary.deliveredObserverCount > 0`
- `failures` is always empty.

### PartiallyDelivered

At least one observer succeeded and at least one observer failed with an
ordinary exception.

- `summary.deliveredObserverCount > 0`
- `summary.failedObserverCount > 0`
- `failures` is non-empty and ordered by invocation order.

### DeliveryFailed

At least one observer was attempted and every attempted observer failed with
an ordinary exception.

- `summary.deliveredObserverCount == 0`
- `summary.failedObserverCount > 0`
- `failures` is non-empty and ordered by invocation order.

---

## SynchronizationEventDispatchSummary

`SynchronizationEventDispatchSummary` is an immutable data class capturing
delivery counts for a single `dispatch` invocation.

### Fields

| Field | Description |
|---|---|
| `registeredObserverCount` | Total observers in the registry at dispatch time |
| `attemptedObserverCount` | Observers for which delivery was attempted |
| `deliveredObserverCount` | Observers that received the event successfully |
| `failedObserverCount` | Observers whose callback threw an ordinary exception |

### Invariants

- All counts are zero or positive.
- `attemptedObserverCount ≤ registeredObserverCount`
- `deliveredObserverCount ≤ attemptedObserverCount`
- `failedObserverCount ≤ attemptedObserverCount`
- For a completed non-cancelled dispatch:
  `deliveredObserverCount + failedObserverCount == attemptedObserverCount`

---

## SynchronizationObserverDispatchFailure

`SynchronizationObserverDispatchFailure` is an immutable structural record of
a single observer delivery failure.

### Fields

| Field | Type | Description |
|---|---|---|
| `observerId` | `SynchronizationObserverId` | The failing observer's ID |
| `eventId` | `SynchronizationEventId` | The event that failed to deliver |
| `reason` | `SynchronizationObserverDispatchFailureReason` | Structural failure reason |
| `error` | `DataLoomError` | Safe canonical diagnostic error |

### Security and diagnostics

The failure record never exposes:

- The observer instance or its `toString()` output.
- The complete event payload.
- The callback exception message.
- The exception class name.
- Stack-trace content.
- Event payload data.

Safe diagnostic information included in `error`:

- Observer ID.
- Event ID.
- Event variant name.
- Canonical `ErrorCode`.

---

## SynchronizationObserverDispatchFailureReason

```kotlin
public enum class SynchronizationObserverDispatchFailureReason {
    OBSERVER_CALLBACK_FAILED,
}
```

`OBSERVER_CALLBACK_FAILED` — The observer's `onEvent` callback threw an
ordinary exception. `CancellationException` is never classified under this
reason.

---

## Provider and side-effect boundary

`SynchronizationEventDispatcher` must not invoke:

- `StorageProvider`
- `TransportProvider`
- `SchedulerProvider`
- `ConnectivityProvider`
- `QueueProvider`
- `ProviderLifecycleCoordinator`
- `SynchronizationProviderResolver`
- `RetryPolicy`
- `ConflictDetector`
- `ConflictResolver`
- Any synchronization pipeline or execution coordinator

It only invokes registered `SynchronizationObserver` callbacks.

---

## Flow and coroutine-scope boundary

`SynchronizationEventDispatcher` does not implement or expose:

- `Flow`, `StateFlow`, or `SharedFlow`
- `Channel` or `callbackFlow`
- Background event queue
- `launch`, `async`, or `GlobalScope`
- `SupervisorJob` or an internal `CoroutineScope`

A future adapter module may expose `Flow` without changing the core observer
dispatcher.

---

## Runtime integration boundary

The current execution coordinator, push/pull pipelines, retry orchestrator,
and conflict orchestrator may call an injected event emitter. The emitter
constructs events and delegates delivery here. This dispatcher still has no
knowledge of provider operations, pipeline phases, retry decisions, conflict
decisions, queue transitions, or durable event state.

Queue-backed retry-event emission remains incomplete. The complete V1 event
and observability subsystem must add durable delivery, replay, filtering,
back-pressure, schema evolution, telemetry/export, and operational read models
outside this narrow callback dispatcher.

---

## Security and diagnostics

Audit-safe information allowed in diagnostic output:

- `SynchronizationEventId`
- `SynchronizationObserverId`
- Event variant name
- `SynchronizationPhase`
- Delivery counts
- Dispatch-result variant
- Canonical `ErrorCode`

Never exposed:

- `DataLoomPayload` bytes
- Conflict payloads
- `SynchronizationRequest` metadata values
- Credentials or authorization headers
- Checkpoint tokens or encryption keys
- Personal data
- Stack traces
- Observer implementation state
- Observer `toString()` output
- Complete event `toString()` when nested values may be sensitive

---

## Performance characteristics

- One bounded pass through registered observers per `dispatch` call.
- Sequential observer invocation.
- No event copy or payload copy.
- No serialization or reflection.
- Bounded failure-record allocation.
- No thread ownership.
- No `CoroutineScope` ownership.
- No blocking wait.

---

## Kotlin Multiplatform compatibility

All production and test code in `dataloom-runtime/src/commonMain` and
`dataloom-runtime/src/commonTest` uses Kotlin standard-library and DataLoom
API types only. No Android API, JVM-only API, reflection, `ServiceLoader`,
or DI framework is used.

---

## Module location

| Artifact | Location |
|---|---|
| Production | `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/observation/` |
| Tests | `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/observation/` |
