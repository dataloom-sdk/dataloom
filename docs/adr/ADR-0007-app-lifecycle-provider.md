# ADR-0007: Application lifecycle provider contract

## Status

Accepted. Records decision D16 for `#101` (DL-039A platform parity). Depends on
[ADR-0002](./ADR-0002-v1-artifact-and-foundation-architecture.md) for the
provider and platform-artifact model.

## Date

2026-09-20

## Context

`#101` listed an iOS `LifecycleProvider` as an open design question: no
lifecycle contract existed for either platform. Provider contracts in
`dataloom-api` (`ConnectivityProvider`, `SchedulerProvider`, `QueueProvider`)
each give the runtime a platform-neutral view of one platform concern, with an
Android and an iOS implementation behind it. Application lifecycle is the
missing concern: the runtime cannot tell whether the app is visible or about to
be suspended, which is the input a future queue-drain policy needs.

The precedent contract, `ConnectivityProvider`, is a single suspend query
returning a `ProviderOperationResult`. Lifecycle is different in kind: the
interesting fact is a transition, and a poll would miss it.

Nothing is published and there are no external consumers, so an additive
public API change is acceptable.

## Decision

### D16: one small platform-neutral lifecycle contract

`io.dataloom.api.lifecycle` in `dataloom-api` defines:

- `AppLifecycleProvider : DataLoomProvider` with a synchronous
  `current: AppLifecycleState` and a cold `states(): Flow<AppLifecycleState>`.
- `AppLifecycleState { FOREGROUND, BACKGROUND, TERMINATING_SOON }`, a coarse
  vocabulary that folds each platform's richer states.
- `AppLifecycleCapabilities` (`state-stream`, `terminating-soon`) so a
  consumer can tell whether a platform can signal imminent termination.
- `AppLifecycleObservationException`, which carries the canonical
  `DataLoomError` when a stream cannot be established (a `Flow` has no result
  channel).
- `ProviderType.APP_LIFECYCLE` in `dataloom-provider-api`.

The contract's stream rules are normative and live in the
`AppLifecycleProvider` KDoc: cold; seeded with the state at collection start;
distinct; conflated; nothing after `TERMINATING_SOON`; observer released on
cancellation, including a cancellation racing registration; no callbacks into
application code; no platform type, identifier, or personal data.

### Naming

The type is `AppLifecycleProvider`, not `LifecycleProvider`.
`ProviderLifecycleState`, `ProviderLifecycleCoordinator` and
`SynchronizationLifecycleEvent` already use "lifecycle" for a provider's or a
workflow's own life, and an unqualified `LifecycleProvider` would read as a
provider of provider lifecycle.

### Implementations

- **Android**: new module `dataloom-lifecycle-android`, one class
  `AndroidLifecycleProvider` over `ProcessLifecycleOwner`. One provider per
  module follows `dataloom-connectivity-android`. It is not added to
  `dataloom-android`'s aggregation, for the reason `DataLoomCoroutineWorker`
  is not: the runtime consumes no lifecycle signal yet, so wiring it would
  imply a behavior that does not exist.
- **iOS**: `AppleLifecycleProvider` in `dataloom-platform-ios`, observing the
  five `UIApplication` notifications through `NSNotificationCenter`. It is not
  added to `AppleDataLoomProviders`, for the same reason. Every UIKit and
  Foundation touch point is behind small internal interfaces
  (`MainThreadExecutor`, `NotificationBinder`) so the ordering and
  cancellation logic is platform-independent and testable.

### Coroutines in the public API

`states()` returns `kotlinx.coroutines.flow.Flow`, so `dataloom-api` now
declares `api(kotlinx-coroutines-core)`. This is the first coroutines type in
any public DataLoom API. It is a deliberate exception to the suspend-only
surface: a transition stream cannot be expressed without a stream type, and
the `ConnectivityProvider` KDoc already anticipated `Flow` for exactly this
case. It does not widen the provider rule against exposing scopes or
dispatchers.

### Dependencies added

- `androidx.lifecycle:lifecycle-process:2.9.4` (catalog entry
  `androidx-lifecycle-process`), for `dataloom-lifecycle-android` only.
- `kotlinx-coroutines-android` (already in the catalog) in that module, for
  main-thread observer registration through `Dispatchers.Main.immediate`.
- `dataloom-testing` as a test-only dependency of `dataloom-lifecycle-android`
  and `dataloom-platform-ios`, for the shared contract suite.

### Contract tests

`dataloom-testing` ships `MutableAppLifecycleProvider` (the deterministic
fake) and `AppLifecycleProviderContract`, one suite that the fake and both real
implementations run. The suite is a plain class of `suspend` checks with a
small `AppLifecycleContractHarness` per implementation, not an abstract test
class: annotation-based discovery of inherited tests is not reliable across
Kotlin targets, so each host declares its own thin `@Test` methods.

## Consequences

- Both platforms report the same three states; consumers written against
  `AppLifecycleProvider` need no platform code.
- `TERMINATING_SOON` is capability-gated. Android cannot signal it and iOS
  delivers it best effort only (never when a suspended process is killed), so
  it can be used to flush cheap state, never as a correctness guarantee.
- The Android background transition is delayed by about 700 ms by
  `ProcessLifecycleOwner` by design.
- `AppleLifecycleProvider.current` from a non-main thread blocks on the main
  queue; a caller that the main thread is waiting on will deadlock. This is
  documented on the class rather than hidden behind a stale cache.
- Host apps that remove `ProcessLifecycleInitializer` from the manifest leave
  the Android provider unstarted; `health()` reports it unhealthy.
- The public `ProviderType` enum gains a value, so exhaustive `when`
  expressions over it must be updated. None exist in this repository.

## Rejected alternatives

- **Listener or callback registration.** Rejected: it calls into application
  code, leaks unless the caller unregisters, and the decision forbids it.
- **A `StateFlow` property.** Rejected: a hot flow forces the provider to
  register a platform observer eagerly and forever, which cannot be released
  on cancellation and would need a scope the provider must not own.
- **A suspend `currentLifecycle(request)` mirroring `ConnectivityProvider`.**
  Rejected: it cannot observe transitions and it discards the synchronous
  reading the runtime needs.
- **Reusing `ProviderType.SCHEDULER` or adding no type.** Rejected: the
  registry and binding validation key on the type, and a lifecycle provider
  is not a scheduler.
- **Placing the Android implementation in `dataloom-android`.** Rejected: the
  aggregator depends on the provider modules, not the reverse, and the
  precedent is one module per Android provider.

## Not decided here

Using the signal to trigger a queue-drain tick, retry or circuit behavior
during replay, and any runtime consumption of lifecycle state are separate
slices that need their own decisions.

## Validation

- Shared contract suite executed against the fake (JVM), the Android
  implementation (Robolectric), and the iOS implementation (see
  [app-lifecycle-provider.md](../api/app-lifecycle-provider.md) for exactly
  what has and has not run on each).
- ABI baselines regenerated for `dataloom-provider-api`, `dataloom-api`,
  `dataloom-testing`, and `dataloom-platform-ios`.

## References

- [Application lifecycle provider](../api/app-lifecycle-provider.md)
- [Connectivity provider](../api/connectivity-provider.md)
- [Scheduler provider](../api/scheduler-provider.md)
