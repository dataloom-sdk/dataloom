# iOS reference consumer

## Status

**Compile-time proof plus a Kotlin/Native Simulator runtime test — mirrors
the native Android reference consumer for `#101` (DL-039A).** The
`runtime-ios-reference-consumer` module wires `AppleConnectivityProvider`,
`SqlDelightStorageProvider`, `AppleFileQueueProvider`, and
`AppleSchedulerProvider` into one real, buildable `DataLoom` instance
through `DataLoomBuilder`'s public API, using `dataloom-platform-ios`'s own
`appleDataLoomProviders`/`installAppleProviders` helpers rather than
hand-wiring the four providers directly. This closes the "the aggregate
published artifact and external/reference consumer are missing" gap issue
`#101` names for iOS specifically — not the broader KMP Android/native
Android consumer paths, which are separately covered (see
[the Android reference consumer](../android/reference-consumer.md)).

## What this proves, and what it does not

Each of the four iOS provider pieces
(`AppleConnectivityProvider`/`AppleSchedulerProvider` in
`dataloom-platform-ios`, `SqlDelightStorageProvider` in
`dataloom-storage-sqldelight`, `AppleFileQueueProvider` in
`dataloom-runtime`) already had its own isolated unit tests, and
`AppleDataLoomProviders`/`appleDataLoomProviders`/`installAppleProviders`
themselves shipped with `dataloom-platform-ios`. Nothing previously wired
all four together against a real `DataLoomBuilder` assembly from a separate
consuming module — this module's main source set is that proof, at compile
time: if any provider's constructor signature, required capability, or
`DataLoomBuilder` binding shape drifts out of sync with what a real iOS
application would need, this module fails to compile.

`IosReferenceConsumerTest` goes one step further: it runs
`DataLoom.initialize()` then `DataLoom.shutdown()` against a real
Kotlin/Native iOS Simulator runtime, executed by
`iosSimulatorArm64Test`/`iosX64Test` on macOS CI — a genuine
`NWPathMonitor` query, `BGTaskScheduler` binding, SQLite database open (via
SQLDelight's native driver), and file read/write under a real temporary
directory. This is Kotlin/Native's own native test-execution mechanism —
the same one `dataloom-runtime`'s Apple circuit/queue/retry-administration
store tests already use — not a JVM shadow layer like Android's
Robolectric.

It still does **not** prove behavior on a physical device, and it does not
call `DataLoom.synchronize()` against real storage/queue/transport I/O,
which stays a separate, larger follow-up — the same two boundaries
`runtime-android-reference-consumer`'s Robolectric test documents for
Android. This repository's Windows development host can cross-compile
`IosReferenceConsumerTest` (catching type errors and API drift) but cannot
execute it — only a real macOS host with Xcode and the iOS Simulator can;
this repository's `apple-validation.yml` CI job (`macos-15`) is the actual
pass/fail signal for this file's runtime behavior.

## Transport is intentionally illustrative

DataLoom does not ship a default transport — endpoint selection,
authentication, and payload serialization are always application-owned. A
real application would use `dataloom-transport-ktor` (this repository's
only transport module that cross-compiles for Kotlin/Native today) or its
own `TransportProvider`. This module's `ReferenceTransportProvider` is a
minimal stub (always reports no remote changes, always fails push) —
proving an already-covered transport module's own HTTP integration again
here would not add real evidence; the point of this module is iOS
*provider* composition.

## Build and verification

Gated the same way as `dataloom-platform-ios` — included only on macOS
hosts, or when `-Pdataloom.appleKlibCrossCompile=true` is passed for
Kotlin/Native cross-compilation without a full XCFramework/Xcode toolchain.

```bash
./gradlew -Pdataloom.appleKlibCrossCompile=true :runtime-ios-reference-consumer:check
```

Verified locally on Windows: `compileKotlinIosArm64`/`IosSimulatorArm64`/
`IosX64` and their `compileTestKotlinIos*` counterparts all succeed
(proving the whole provider-composition graph, including the test, resolves
and type-checks on real Kotlin/Native targets); the module's own `check`
task passes with `iosSimulatorArm64Test`/`iosX64Test` correctly `SKIPPED`
(no simulator toolchain on Windows — this is an expected skip, not a false
pass); and the module is correctly absent from the project graph on a
plain (non-Apple, non-cross-compile) build — confirmed via
`./gradlew projects` with and without the cross-compile flag. No ABI
baseline applies — this is a fixture module, not a published API surface,
matching `runtime-android-reference-consumer` and
`runtime-external-consumer`'s existing precedent. Actual runtime pass/fail
for `IosReferenceConsumerTest` is confirmed by the real macOS CI leg (see
this PR/commit's own CI result), not by anything this Windows host can run.

## What remains open on `#101`

- KMP Android: shared modules still expose only a `jvm()` target consumed
  by Android bytecode, not an explicit `androidTarget()` KMP variant —
  confirmed genuinely blocked in this repository's current Kotlin/AGP
  combination; see
  [kmp-android-target-blocker.md](../android/kmp-android-target-blocker.md).
- iOS lifecycle integration: no `LifecycleProvider` contract exists in this
  codebase at all yet — an open design question, not attempted by this
  module or `dataloom-platform-ios`.
- Device runtime proof — the iOS Simulator is a real Apple-provided
  runtime, not a shadow layer, but device-only behavior (background
  execution limits, real network conditions, memory pressure) can still
  differ; neither platform has been proven on a physical device.
- `DataLoom.synchronize()` runtime proof — this module's Simulator test
  covers `initialize()`/`shutdown()` only, not a full synchronization pass
  against real storage/queue/transport I/O, the same boundary
  `runtime-android-reference-consumer`'s Robolectric test has.
- Native Android, KMP Android, and KMP iOS consumers resolving staged/
  published artifacts rather than project includes — the same bar
  `runtime-external-consumer` also does not yet meet for the JVM path.
- The full foreground/offline/retry/circuit/conflict/event/asset/
  cancellation/concurrency/resource-limit/migration/termination/relaunch
  matrix `#101`'s acceptance criteria require.
