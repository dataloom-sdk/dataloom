# DataLoom Application Lifecycle Provider

[API reference index](./README.md)

> **Status:** Partial. The contract, a fake, a shared contract suite, an
> Android implementation and an iOS implementation exist, and the runtime has
> an opt-in consumer that turns the signal into a bounded queue drain
> ([below](#lifecycle-triggered-queue-drain)). Neither platform implementation
> has run on a device, and no drain has run on a real device or under a real
> `BGTaskScheduler` or WorkManager invocation. Decision records:
> [ADR-0007](../adr/ADR-0007-app-lifecycle-provider.md) (contract) and
> [ADR-0013](../adr/ADR-0013-lifecycle-triggered-queue-drain.md) (drain).

**Audience:** engineers wiring a platform, or writing a runtime policy that
needs to know whether the app is visible.

`dataloom-api` defines a platform-neutral provider contract for the
application's coarse lifecycle, so the runtime can later ask "is the app in the
foreground" without depending on AndroidX, UIKit, or any other platform API.

## Contract

**Package:** `io.dataloom.api.lifecycle` (`dataloom-api`)

```kotlin
public interface AppLifecycleProvider : DataLoomProvider {
    override val descriptor: ProviderDescriptor  // type = ProviderType.APP_LIFECYCLE
    public val current: AppLifecycleState
    public fun states(): Flow<AppLifecycleState>
}
```

| State | Meaning |
|---|---|
| `FOREGROUND` | The app is visible (Android: at least one activity started; iOS: active or inactive). |
| `BACKGROUND` | The app is not visible; the OS may suspend or terminate it without notice. |
| `TERMINATING_SOON` | The platform signalled imminent termination. Terminal. Only where the platform can signal it. |

### `current`

Synchronous and side-effect free. Never throws; returns `BACKGROUND` when the
platform cannot report a state. Reports only `FOREGROUND` or `BACKGROUND`:
`TERMINATING_SOON` is an event on the stream, not a state a platform can be
read for.

### `states()`

A cold stream. Creating the flow does nothing; each collection registers one
platform observer and releases it when the collector is cancelled or the flow
fails. Every collection:

- starts with the state at collection start;
- then emits only changes (never two equal values in a row);
- is conflated, so a slow collector always ends on the latest state but may
  skip intermediate ones;
- emits nothing after `TERMINATING_SOON` and never completes on its own;
- fails with `AppLifecycleObservationException` (carrying a canonical
  `DataLoomError` with no platform detail) if the platform source cannot be
  observed.

### Capabilities

Every provider declares `state-stream`. A provider declares `terminating-soon`
if and only if it can deliver `TERMINATING_SOON`.

### What a provider must not do

Call back into application code, expose a platform lifecycle type, activity,
scene, or process identifier, trigger synchronization or scheduling, or expose
scopes or dispatchers. `dataloom-api` now declares `api(kotlinx-coroutines-core)`
because `Flow` is part of this contract; it is the only coroutines type in the
public API.

## Android: `AndroidLifecycleProvider`

**Module:** `dataloom-lifecycle-android` (`io.dataloom.lifecycle.android`).
Backed by `ProcessLifecycleOwner`.

- Started or resumed maps to `FOREGROUND`; created, destroyed, or not yet
  started maps to `BACKGROUND`.
- No `TERMINATING_SOON`: Android gives an app no termination callback, so the
  capability is not declared.
- `ProcessLifecycleOwner` delays its stop event by about 700 ms so a
  configuration change does not look like leaving the foreground; `BACKGROUND`
  arrives after that delay.
- Registration and removal run on the main thread through
  `Dispatchers.Main.immediate`, so `states()` may be collected from any
  thread. Removal runs in a `NonCancellable` block, and registration is
  tracked, so a cancellation that lands between `addObserver` and the wait
  cannot leak an observer.
- The process lifecycle must be started by `ProcessLifecycleInitializer`
  (contributed by `lifecycle-process` through `androidx.startup`). If a host
  removes it from the manifest, `current` is `BACKGROUND` and `health()`
  reports `UNHEALTHY`.
- New dependency: `androidx.lifecycle:lifecycle-process:2.9.4`.
- Not added to `dataloom-android`'s aggregation; add the module directly.

## iOS: `AppleLifecycleProvider`

**Module:** `dataloom-platform-ios` (`io.dataloom.platform.ios.lifecycle`).
Backed by `NSNotificationCenter`.

| Notification | State |
|---|---|
| `didBecomeActive`, `willEnterForeground` | `FOREGROUND` |
| `willResignActive` | `FOREGROUND` (inactive is still on screen) |
| `didEnterBackground` | `BACKGROUND` |
| `willTerminate` | `TERMINATING_SOON` |

- `willTerminate` is best effort. iOS does not deliver it when it kills a
  suspended app, which is the common case. Do not rely on it.
- UIKit posts these notifications on the main thread and observers are added
  without an operation queue, so the handler runs synchronously on the posting
  thread. It only hands a value to a conflated channel; it never blocks.
- Registration, the seed read, and removal run on the main thread. Registration
  and the seed happen in one main-thread block, so a state change cannot fall
  between them and a stale seed cannot overtake a newer notification. From
  other threads these are queued, never blocked on, and a cancel that arrives
  before registration ran makes registration a no-op.
- `current` reads `UIApplication.applicationState`, which is main-thread only,
  so from another thread it blocks on the main queue. Do not call it from a
  thread the main thread is waiting on.
- `UIApplication.shared` is unavailable in app extensions; there `current` is
  `BACKGROUND`.
- Not added to `AppleDataLoomProviders`.

Kotlin `Flow` is visible to Swift only as a Kotlin collector interface.

## Testing

`dataloom-testing` provides:

- `MutableAppLifecycleProvider`: deterministic fake driven with `setState`.
- `AppLifecycleProviderContract`: one suite (descriptor, cold flow, seeding,
  ordering, duplicate suppression, slow collector, observer release,
  collector independence, no delivery after cancellation, re-collection,
  `TERMINATING_SOON` gating) that every implementation runs through a small
  `AppLifecycleContractHarness`.

| Where it runs | Against | Runs on |
|---|---|---|
| `dataloom-testing` `commonTest` | the fake | JVM and iOS Simulator |
| `dataloom-lifecycle-android` unit tests | the real provider over a real `LifecycleRegistry`, Robolectric | JVM |
| `dataloom-platform-ios` `commonTest` | the real provider and source over an in-memory notification binder | iOS Simulator |
| `dataloom-platform-ios` `iosTest` | the real provider and source over a real `NSNotificationCenter` | iOS Simulator |

`DispatchMainThreadExecutorTest` (`iosTest`) checks the real `dispatch` hop by
pumping the main run loop from the test.

### Not verified

- Neither implementation has run on a physical device, and neither has
  observed a genuine lifecycle transition from the OS: Android tests drive a
  `LifecycleRegistry` rather than `ProcessLifecycleOwner`, and iOS tests post
  the notifications themselves on a private center.
- The production `UIApplication.applicationState` reader has not been
  executed: a unit-test process has no `UIApplication`.
- iOS tests run only on the macOS CI job.

## Lifecycle-triggered queue drain

**Package:** `io.dataloom.runtime.facade` (`dataloom-runtime`). Decision
record: [ADR-0013](../adr/ADR-0013-lifecycle-triggered-queue-drain.md).

An opt-in runtime consumer of `AppLifecycleProvider`. On a qualifying
transition it performs at most one bounded queue drain through the existing
queue worker; it is not a second worker.

```kotlin
val dataLoom = DataLoomBuilder()
    // ... providers, bindings, queueWorkerConfiguration(...)
    .lifecycleDrainConfiguration(
        DataLoomLifecycleDrainSpec(
            lifecycleProvider = lifecycleProvider,        // AndroidLifecycleProvider / AppleLifecycleProvider
            consumerId = QueueConsumerId("app-lifecycle-drain"),
            policy = LifecycleDrainPolicy(),              // defaults below
        ),
    )
    .build()

// The runtime owns no scope: the host runs the collector in a scope it owns.
appScope.launch { dataLoom.lifecycleDrain?.run() }
```

### Absence is inert

Without `lifecycleDrainConfiguration`, `DataLoom.lifecycleDrain` is `null` and
nothing changes. With it, `build()` reads only the provider's descriptor: it
does not collect, read the clock, generate an identifier, or touch the queue.
Nothing happens until the host calls `run()`. The spec requires a queue worker
(`queueWorkerConfiguration` or `circuitQueueWorkerConfiguration`); `build()`
throws `DataLoomBuildException` otherwise. The provider's own lifecycle
(`initialize`, `close`) stays with the host.

### Policy

| `LifecycleDrainPolicy` | Default | Meaning |
|---|---|---|
| `triggers` | `BACKGROUND`, `TERMINATING_SOON` | States whose arrival drains. Add `FOREGROUND` to also drain on return to the foreground. Must not be empty. |
| `minimumInterval` | 30 s | Minimum time between the *starts* of two drains, on the runtime clock. A transition inside it is dropped, not deferred. A clock that moves backwards never blocks a drain. |
| `maxEntriesPerDrain` | 25 | The `maxEntries` of the drain's single acquisition; a drain never processes more. At least one. |
| `leaseDuration` | 60 s | Lifetime of the drain's queue lease. Keep it short. Greater than zero. |

The state a collection starts in is not a transition. An app that is already in
the background when `run()` starts does not drain until it changes state.

### What one drain is

One `DataLoomQueueWorker.run` call (or `DataLoomCircuitQueueWorker.run` when
only the circuit worker is configured; if both are configured the direct worker
is used), with a request built at drain time: the spec's `consumerId`, a fresh
lease id from the runtime's lease-id generator, the runtime clock for
`acquiredAt`/`leaseExpiresAt`, `policy.maxEntriesPerDrain`, and an
expired-lease recovery request exactly when the worker configuration requires
one. The worker is the same instance `DataLoom.queueWorker` returns, so the
health tracker (`queueWorkerHealthTracker`) and scheduling-event bridge
configured for it observe every drain.

### Guarantees

- **No overlap.** A qualifying transition while a drain is in flight is
  dropped (coalesced into the running drain). This holds across concurrent
  `run()` callers on one instance.
- **Cancellation.** Cancelling the collecting coroutine cancels the in-flight
  drain, and `run()` rethrows the cancellation. The worker's health tracker
  records a cancelled run.
- **Failures never end the collection.** A failed run result, an exception
  from the worker, or a request that cannot be built is absorbed. Failures of
  the run are recorded by the worker's health tracker when one is configured;
  a request that cannot be built never reaches the worker, so it is not
  recorded anywhere.
- **Observation failures are handled, not thrown.** If the lifecycle stream
  fails, `run()` returns `LifecycleDrainEnd.ObservationFailed(error)` (the
  canonical `AppLifecycleObservationException.error`, or a sanitized internal
  error for any other exception). The runtime does not restart the stream. A
  stream that completes returns `LifecycleDrainEnd.StreamCompleted`.
- An in-flight drain is awaited before `run()` returns either result.

### Best effort, bounded by the OS

A background drain on a real device is best effort. The app gets whatever
execution time the OS grants after entering the background (typically seconds
on iOS unless a background task is held; Android varies by version, process
state and battery policy), and can be suspended or killed mid-drain. On
Android `BACKGROUND` also arrives about 700 ms late (see above), and on iOS
`TERMINATING_SOON` is rarely delivered. The drain is therefore never a delivery
guarantee: the lease expiry and expired-lease recovery are what make an
interrupted drain safe, and the platform schedulers (`WorkManager`,
`BGTaskScheduler`) remain the way to get guaranteed background time.

### Tests and what is not verified

| Where | What | Runs on |
|---|---|---|
| `dataloom-runtime` `commonTest` `DefaultDataLoomLifecycleDrainTest` | policy triggers, minimum interval, coalescing, request contents and max-entries bound, cancellation mid-drain, worker failure isolation, observation failure, spec and policy validation, over a fake provider and a fake worker | JVM, Android host test; compiled (not run) for iOS |
| `dataloom-runtime` `commonTest` `DataLoomBuilderLifecycleDrainTest` | absence is inert, build collects nothing, a transition drains a real queue provider through the real health-tracked worker, a failed drain is recorded and the collector survives | JVM, Android host test; compiled (not run) for iOS |

Not verified: any iOS execution of these tests (macOS CI job only); a real
`BGTaskScheduler` or WorkManager invocation; a real device, including a drain
racing a genuine OS suspension; wiring `AndroidLifecycleProvider` and
`AppleLifecycleProvider` into `AndroidDataLoomProviders`/`AppleDataLoomProviders`.

## Not part of this slice

Retry or circuit behavior during replay, registering the lifecycle providers in
the platform provider aggregations, an automatic (scope-owning) collector, and
exposing a durably admitted queue entry's id through a public API are separate
slices.
