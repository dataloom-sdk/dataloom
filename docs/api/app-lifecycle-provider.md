# DataLoom Application Lifecycle Provider

[API reference index](./README.md)

> **Status:** Partial. The contract, a fake, a shared contract suite, an
> Android implementation and an iOS implementation exist. Nothing in the
> runtime consumes lifecycle signals yet, and neither implementation has run
> on a device. Decision record: [ADR-0007](../adr/ADR-0007-app-lifecycle-provider.md).

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

## Not part of this slice

Using lifecycle signals to trigger a real queue-drain tick, retry or circuit
behavior during replay, and exposing a durably admitted queue entry's id
through a public API are separate slices.
