# ADR-0013: Lifecycle-triggered queue drain

## Status

Accepted; implemented. Records decision D23 for `#101` (DL-039A platform
parity). Builds on [ADR-0007](./ADR-0007-app-lifecycle-provider.md), which
deliberately left "using the signal to trigger a queue-drain tick" undecided.

## Date

2026-09-28

## Context

`AppLifecycleProvider` (ADR-0007) reports `FOREGROUND`, `BACKGROUND` and
`TERMINATING_SOON`, but nothing in the runtime consumed it. The natural use is
to flush queued work when the app leaves the foreground, before the OS may
suspend it. The runtime already has one queue entry point, the queue worker
(`DataLoomQueueWorker.run`, or its circuit-aware counterpart), plus health
tracking and scheduling-event bridging wrapped around it. The platform
scheduler modules (`dataloom-scheduler-workmanager`, `dataloom-scheduler-bgtask`)
already call that entry point from an OS wake-up with a host-built request.

`DataLoom` owns no coroutine scope and selects no dispatcher, and every optional
capability is an inert `*Spec` on `DataLoomBuilder`. Pre-V1, so an additive
public surface is acceptable.

## Decision

### D23: an opt-in consumer that drains through the existing worker

- `DataLoomBuilder.lifecycleDrainConfiguration(DataLoomLifecycleDrainSpec)`
  takes an `AppLifecycleProvider`, a `QueueConsumerId` and a
  `LifecycleDrainPolicy`. Absent spec: `DataLoom.lifecycleDrain` is `null` and
  behavior is unchanged. Present spec: `build()` reads only the provider
  descriptor (no collection, clock read, identifier, queue I/O), and requires a
  queue worker or throws `DataLoomBuildException`.
- `DataLoom.lifecycleDrain: DataLoomLifecycleDrain?` exposes one suspending
  `run(): LifecycleDrainEnd`. The **host** launches it in a scope it owns and
  cancels that scope to stop it, consistent with the runtime owning no scope.
- `LifecycleDrainPolicy`: `triggers` (default `BACKGROUND` and
  `TERMINATING_SOON`; add `FOREGROUND` to drain on return to the foreground),
  `minimumInterval` (default 30 s), `maxEntriesPerDrain` (default 25),
  `leaseDuration` (default 60 s).
- A drain is exactly one call to the assembled worker (the same instance
  `DataLoom.queueWorker` returns, after bridging and health-tracking wrappers;
  the circuit worker only when no direct worker is configured). The request is
  built at drain time from the runtime clock, the lease-id generator, the
  spec's consumer id and the policy's bounds. There is no second worker path.
- The state a collection starts in is not a transition and does not drain.
- Drains never overlap: a `Mutex.tryLock` guard, taken before the decision and
  released by the drain job's completion handler (which also fires for a job
  cancelled before it starts), drops a qualifying transition that arrives while
  a drain is in flight. This also holds across concurrent `run()` callers. A
  dropped transition is not replayed, so unbounded queuing is impossible.
- The minimum interval is measured start to next qualifying arrival on the
  runtime clock. A transition inside it is dropped. A clock that moves
  backwards never blocks a drain, so a wall-clock correction cannot silence the
  drain for hours.
- Cancelling the collector cancels the in-flight drain (a child of the `run()`
  call) and `run()` rethrows the cancellation.
- A drain failure never ends the collection: the run's outcome is recorded by
  the worker's own health tracker and event bridge, and exceptions are absorbed.
  A lifecycle stream failure ends `run()` with `LifecycleDrainEnd.ObservationFailed`
  (the canonical error, or a sanitized internal one), not an exception. The
  runtime does not restart the stream.

### Best effort

A background drain on a real device is best effort and bounded by whatever
execution time the OS grants; it can be suspended or killed mid-drain. It is
not a delivery guarantee and does not replace `WorkManager` or `BGTaskScheduler`,
which remain the guaranteed-background-time mechanisms. Expired-lease recovery
is what makes an interrupted drain safe, which is why the lease is short by
default and recovery is included in the request whenever the worker
configuration requires it.

## Consequences

- Public surface added to `dataloom-runtime`: `DataLoomLifecycleDrain`,
  `LifecycleDrainEnd`, `DataLoomLifecycleDrainSpec`, `LifecycleDrainPolicy`,
  `DataLoom.lifecycleDrain` (default getter, so custom `DataLoom`
  implementations still compile) and `DataLoomBuilder.lifecycleDrainConfiguration`.
  Both ABI baseline layouts were regenerated.
- The host must start the collector. There is no automatic start: an automatic
  collector would need a scope and dispatcher the runtime does not own.
- A request that cannot be built (for example an exhausted lease-id generator)
  is absorbed and, because it never reaches the worker, is recorded nowhere.
- The provider's own `initialize`/`close` remain the host's responsibility;
  the provider is not added to the provider registry, and neither
  `AndroidDataLoomProviders` nor `AppleDataLoomProviders` gains a lifecycle
  provider in this slice.
- Because the drain runs through the tracked worker, a drain also shows up in
  `QueueWorkerHealthTracker` and `dataLoomHealthSnapshot` like any other run.

## Rejected alternatives

- **A second, lifecycle-specific worker path.** Rejected: it would duplicate
  recovery, acquisition, retry and scheduling logic and bypass health tracking.
- **The runtime launching its own collector.** Rejected: the runtime owns no
  scope; the host's scope is the only correct cancellation boundary.
- **Deferring or queueing a transition that arrives mid-drain.** Rejected:
  it needs either an unbounded queue or a pending flag whose follow-up drain
  would itself usually be inside the minimum interval. Dropping is simpler and
  bounded.
- **Draining on the collection-start state.** Rejected: an app woken in the
  background by a scheduler already runs the worker through the scheduler
  bridge, and a start-time drain would double up on every collector restart.
- **A host-supplied `QueueWorkerRunRequestFactory`** (as the scheduler modules
  use). Rejected here: the policy must own `maxEntries` for the bound to be
  enforced by the runtime rather than trusted from a callback.
- **Restarting the stream after an observation failure.** Rejected: an
  immediate restart of a failing platform source loops without backoff; the
  host decides.

## Not decided here

Real `BGTaskScheduler` or WorkManager invocation, physical-device proof,
registering the lifecycle providers in the platform provider aggregations, and
retry or circuit behavior during replay.

## Validation

- Unit tests over a fake lifecycle provider and a fake worker, and builder
  tests over a real queue provider, worker and health tracker, run on the JVM
  and the Android host test; iOS test binaries are cross-compiled for all
  three Apple targets but not run (macOS CI only). See
  [app-lifecycle-provider.md](../api/app-lifecycle-provider.md).
- ABI baselines regenerated for `dataloom-runtime` in both layouts.

## References

- [Application lifecycle provider](../api/app-lifecycle-provider.md)
- [ADR-0007](./ADR-0007-app-lifecycle-provider.md)
