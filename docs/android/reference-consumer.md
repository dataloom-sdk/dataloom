# Native Android reference consumer

## Status

**Compile-time proof, a Robolectric-backed runtime proof, a real
Gradle Managed Device emulator proof, and a Robolectric-backed
durable-queue-admission-then-replay proof for two strategies — four staged
slices of `#101` (DL-039A).** The
`runtime-android-reference-consumer` module wires
`AndroidConnectivityProvider`, `RoomStorageProvider`, `RoomQueueProvider`,
and `WorkManagerSchedulerProvider` into one real, buildable `DataLoom`
instance through `DataLoomBuilder`'s public API. This closes the "the
aggregate published artifact and external/reference consumer are missing"
gap issue `#101` names for native Android specifically — not the broader
KMP Android/KMP iOS consumer paths, which remain separate, larger work.

## What this proves, and what it does not

Each of the four Android provider modules
(`dataloom-connectivity-android`, `dataloom-storage-room`,
`dataloom-queue-room`, `dataloom-scheduler-workmanager`) already had its
own isolated unit tests. Nothing previously wired all four together against
a real `DataLoomBuilder` assembly — this module's main source set is that
proof, at compile time: if any provider's constructor signature, required
capability, or `DataLoomBuilder` binding shape drifts out of sync with what
a real native Android application would need, this module fails to
compile.

`AndroidReferenceConsumerRobolectricTest` goes one step further: it runs
`DataLoom.initialize()` then `DataLoom.shutdown()` against a real
(Robolectric-simulated) Android runtime — a genuine `WorkManager` instance
via `WorkManagerTestInitHelper`, two genuine Room-backed SQLite databases,
and a genuine `ConnectivityManager` service lookup. This is real runtime
evidence, not a compile-time inference: if any provider's `initialize()`/
`shutdown()` implementation genuinely fails against Android framework code
(not just against DataLoom's own contracts), this test fails.

A second test in the same class, `realAndroidProvidersApplyAPulledChangeToRealStorage`,
goes one step further still: it calls `DataLoom.synchronize()` with a real
inbound `ChangeSet` (via a test-only `TransportProvider` that always
returns one `CREATE` event) and asserts `summary.inboundEventsApplied == 1`
— proving the event was genuinely written to the real Room database, not
just that the call returned a "succeeded" status. `SynchronizationExecutionCoordinator`
defaults to `SynchronizationConnectivityConfiguration.NONE` (confirmed by
reading its own KDoc), so this does not depend on Robolectric's
`ConnectivityManager` shadow reporting a connected network.

`AndroidReferenceConsumerInstrumentedTest` (`src/androidTest`) goes one
step further still: it runs the identical two proofs — initialize/shutdown,
then a real `synchronize()` PULL pass — against `pixel2Api35`, a real
Gradle Managed Device AVD emulator (API 35, AOSP, x86_64) executed on the
real Linux CI runner via KVM, the same managed-device mechanism
`dataloom-queue-room`'s and `dataloom-storage-room`'s own instrumented
tests already use. This is genuine on-emulator execution — a real
`android.app.Application` process, a real `WorkManager` instance, a real
Room-backed SQLite database on a real filesystem — not a JVM shadow layer.

It still does **not** prove behavior on a physical device — an AVD emulator
is closer to real hardware than Robolectric, but background execution
limits, real network conditions, and manufacturer-specific OS behavior can
still differ from an actual phone or tablet. This mirrors the same
documented boundary `runtime-external-consumer` already established for the
JVM-only public runtime surface — "compile-only, then Robolectric
initialize/shutdown, then Robolectric synchronize(), then the same two
proofs again on a real managed-device emulator" is a deliberately staged
proof, not a claim that everything is now covered.

A fourth test, `AndroidReferenceConsumerDurableQueueRobolectricTest`
(a separate Robolectric test class in this module), closes part of the
queue-admission/retry/circuit/conflict gap the three tests above left
completely untouched — every proof above exercises only
`DataLoom.synchronize()`'s *direct* execution path, never the
durable-admission-then-replay path at all. It builds a
`StrategySynchronizationRequest` from `OfflineFirstStrategyProfile`
(`requireDurableQueue = true`, connectivity available) and asserts, in
order: (1) `DataLoom.synchronize(...)` returns `DurablyEnqueued` with zero
calls to the test transport — proving the request was durably admitted via
`StrategyDurableQueueAdmitter` into a real Room-backed
`RoomQueueProvider`, not executed synchronously; (2) one deterministic
`DataLoom.queueWorker.run(...)` cycle — the same `DataLoomQueueWorker`
capability the real `DataLoomCoroutineWorker`/`WorkManager` bridge
delegates to in production, called directly here instead of waiting on a
real scheduled tick, mirroring how the other tests in this module drive one
direct pass deterministically — acquires the entry back out of that same
real database and reaches `QueueProcessingResult.Processed` with
`summary.completed == 1`; and (3) `AcceptedStrategyPlanExecutionCoordinator`
(the durable-continuation replay coordinator none of the other three tests
ever reach) genuinely replays the admitted plan's `InboundPullSynchronizationPipeline`
leg, observed through a real `SynchronizationObserver` registered via
`DataLoomBuilder.observer(...)` — `summary.inboundEventsApplied == 1`, the
same bar the second test above established, now proven for the queued path
too. This covers Android + offline-first only; see "What remains open"
below for what it does not.

A fifth test, `AndroidReferenceConsumerCacheFirstQueueRobolectricTest`
(also a separate Robolectric test class in this module), proves the same
admission-then-replay shape for a second strategy: cache-first. Investigation
found that cache-first's *documented* durable-refresh branch
(`docs/api/cache-first-strategy-execution.md`'s PULL/BIDIRECTIONAL
`SERVE_LOCAL` + refresh shape) is not exercisable against the real,
unmodified `RoomStorageProvider`, which implements only `StorageProvider`
and not `StrategyLocalFallbackProvider` — the same real constraint the
fourth test's own KDoc already documented as its reason for avoiding
`SERVE_LOCAL`. `BuiltInSynchronizationStrategyEvaluator` has a second,
previously-undocumented `ENQUEUE_DURABLE_WORK` branch for cache-first,
reached for `SynchronizationDirection.PUSH` with connectivity `UNAVAILABLE`
(`requireDurableRefresh = true`, the profile default): its durable
continuation is exactly `[READ_LOCAL, PUSH_REMOTE]` — no `SERVE_LOCAL`, no
`RECONCILE` — `AcceptedStrategyPlanExecutionCoordinator`'s supported PUSH
replay shape, and compatible with the real `RoomStorageProvider` as-is. This
test asserts, in order: (1) `DataLoom.synchronize(...)` returns
`Deferred` with a real, non-null `queueEntryId` and zero calls to the test
transport; (2) one deterministic `DataLoom.queueWorker.run(...)` cycle
acquires the entry back out of the real Room queue database and reaches
`QueueProcessingResult.Processed` with `summary.completed == 1`; and (3)
`AcceptedStrategyPlanExecutionCoordinator` genuinely replays the admitted
plan through the real, registered `OutboundPushSynchronizationPipeline`,
observed through the same real `SynchronizationObserver` wiring the fourth
test uses. Because outbound content is read from local storage rather than
supplied by the transport, and `StorageProvider` (the contract
`RoomStorageProvider` implements) exposes no public API to seed local
pending outbound changes, the genuine, honestly-observed terminal result
here is `SynchronizationResult.Skipped(NO_CHANGES)` — a real Room query
against a real, empty outbound table — rather than an applied/pushed count;
see the test's own KDoc for the full investigation and reasoning. This
covers Android + cache-first only, on top of the fourth test's Android +
offline-first coverage; see "What remains open" below for what remains.

## Transport is intentionally illustrative

DataLoom does not ship a default transport — endpoint selection,
authentication, and payload serialization are always application-owned. A
real application would use `dataloom-transport-ktor`,
`dataloom-transport-retrofit`, `dataloom-transport-graphql`,
`dataloom-transport-grpc`, or its own `TransportProvider`. This module's
`ReferenceTransportProvider` is a minimal stub (always reports no remote
changes, always fails push) — proving one already-covered transport
module's own HTTP integration again here would not add real evidence; the
point of this module is Android *provider* composition.

## Build and verification

Gated behind `DATALOOM_ANDROID_BUILD=true`, same as every other Android
module in this repository — requires the Android SDK and network access
to the Google Maven repository.

```bash
DATALOOM_ANDROID_BUILD=true ./gradlew :runtime-android-reference-consumer:check
```

Verified for real (local Windows host): `compileDebugKotlin` succeeds
(proving the whole provider-composition graph resolves and type-checks);
`testDebugUnitTest` runs `AndroidReferenceConsumerRobolectricTest`,
`AndroidReferenceConsumerDurableQueueRobolectricTest`, and
`AndroidReferenceConsumerCacheFirstQueueRobolectricTest`, and passes
(4 tests, 0 failures, 0 errors — confirmed via the JUnit XML report, not
just a green build); `compileDebugAndroidTestKotlin` and
`assembleDebugAndroidTest` both succeed, producing a real, packaged
instrumented-test APK; `lintDebug` passes; and the full repository's
Android `check` task (excluding a pre-existing, unrelated
`dataloom-storage-datastore` test failure — see that module's own test
report) is unaffected. No ABI baseline applies — this is a plain
`com.android.library` module, not a KMP convention-plugin module, matching
every other Android provider module in this repository.

Robolectric's own SDK-jar downloads run once per machine on first use, not
on every build — expect the first `testDebugUnitTest` invocation on a
fresh checkout to take noticeably longer than subsequent runs.

Actually **running** `AndroidReferenceConsumerInstrumentedTest` on
`pixel2Api35` requires KVM, which is Linux-only — this repository's
`android-validation.yml` CI job enables it specifically for this purpose.
Local Windows verification is limited to compiling and packaging the test
APK; the real pass/fail signal is `android-validation.yml`'s own
"Run managed-device tests" step, which now also runs
`:runtime-android-reference-consumer:pixel2Api35DebugAndroidTest` alongside
`dataloom-queue-room`'s and `dataloom-storage-room`'s existing
managed-device tests.

## What remains open on `#101`

- KMP Android: shared modules still expose only a `jvm()` target consumed
  by Android bytecode, not an explicit `androidTarget()` KMP variant.
  This has now been attempted twice and confirmed genuinely blocked in
  this repository's current Kotlin/AGP combination, not just theoretically
  risky — see
  [kmp-android-target-blocker.md](kmp-android-target-blocker.md) for the
  reproduced failure and what has already been ruled out.
- KMP iOS: `dataloom-ios` does not exist as a published artifact yet
  (though `dataloom-platform-ios` now covers its provider layer — see
  [the Apple guide](../apple/README.md)). No production Apple lifecycle
  adapter exists for either platform.
- Physical-device runtime proof — Android now has a real AVD emulator proof
  (`pixel2Api35`, above) alongside Robolectric, and iOS has the Simulator
  (`runtime-ios-reference-consumer`); neither platform has been proven on
  actual physical hardware.
- Queue admission/retry/circuit/conflict behavior during a real
  synchronization pass, beyond the Android + offline-first and Android +
  cache-first admission-then-replay slices
  `AndroidReferenceConsumerDurableQueueRobolectricTest` and
  `AndroidReferenceConsumerCacheFirstQueueRobolectricTest` now prove
  (above): iOS has no equivalent durable-queue proof for cache-first (only
  offline-first, per `#334`); the other built-in strategies eligible for
  durable admission (remote-first, hybrid) remain unexercised at this
  layer on either platform, and cache-first's own genuinely non-empty
  push-content path remains unproven (`StorageProvider` exposes no public
  API to seed local pending outbound changes — see the fifth test's own
  KDoc); and retry, circuit-breaker, and conflict-detection behavior during
  queue replay itself remain unproven even for the two slices covered —
  each proven entry always succeeds on its first attempt.
- Native Android and KMP Android+iOS consumers resolving staged/published
  artifacts rather than project includes — the same bar
  `runtime-external-consumer` also does not yet meet for the JVM path.
- The full foreground/offline/retry/circuit/conflict/event/asset/
  cancellation/concurrency/resource-limit/migration/termination/relaunch
  matrix `#101`'s acceptance criteria require.
