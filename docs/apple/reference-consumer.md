# iOS reference consumer

## Status

**Compile-only fixture — mirrors the native Android reference consumer for
`#101` (DL-039A).** The `runtime-ios-reference-consumer` module wires
`AppleConnectivityProvider`, `SqlDelightStorageProvider`,
`AppleFileQueueProvider`, and `AppleSchedulerProvider` into one real,
buildable `DataLoom` instance through `DataLoomBuilder`'s public API, using
`dataloom-platform-ios`'s own `appleDataLoomProviders`/`installAppleProviders`
helpers rather than hand-wiring the four providers directly. This closes the
"the aggregate published artifact and external/reference consumer are
missing" gap issue `#101` names for iOS specifically — not the broader KMP
Android/native Android consumer paths, which are separately covered (see
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
consuming module — this module is that proof, at compile time: if any
provider's constructor signature, required capability, or `DataLoomBuilder`
binding shape drifts out of sync with what a real iOS application would
need, this module fails to compile.

It does **not** prove runtime behavior on a device or simulator.
`buildReferenceDataLoom(...)` is real, correct wiring code — not a stub —
but nothing in this module calls `DataLoom.initialize()` or
`DataLoom.synchronize()` against a real iOS `Network.framework` path,
`BGTaskScheduler`, or SQLite database. This repository has no XCTest-backed
integration-test infrastructure yet; adding it is a separate, larger
follow-up, not silently claimed as covered here. This mirrors the same
documented boundary `runtime-android-reference-consumer` and
`runtime-external-consumer` already established — "compile-only fixture" is
an established pattern in this repository, not a new one invented for this
module.

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

Verified for real: `compileKotlinIosArm64`/`IosSimulatorArm64`/`IosX64` all
succeed (proving the whole provider-composition graph resolves and
type-checks on real Kotlin/Native targets, not just JVM), the module's own
`check` task passes, and the module is correctly absent from the project
graph on a plain (non-Apple, non-cross-compile) build — confirmed via
`./gradlew projects` with and without the cross-compile flag. No ABI
baseline applies — this is a compile-only fixture module, not a published
API surface, matching `runtime-android-reference-consumer` and
`runtime-external-consumer`'s existing precedent.

## What remains open on `#101`

- KMP Android: shared modules still expose only a `jvm()` target consumed
  by Android bytecode, not an explicit `androidTarget()` KMP variant —
  confirmed genuinely blocked in this repository's current Kotlin/AGP
  combination; see
  [kmp-android-target-blocker.md](../android/kmp-android-target-blocker.md).
- iOS lifecycle integration: no `LifecycleProvider` contract exists in this
  codebase at all yet — an open design question, not attempted by this
  module or `dataloom-platform-ios`.
- Runtime proof (XCTest or a real device/simulator run) that this module's
  wiring actually initializes and synchronizes successfully, not just
  compiles — the same gap `runtime-android-reference-consumer` has for
  Robolectric/instrumented tests.
- Native Android, KMP Android, and KMP iOS consumers resolving staged/
  published artifacts rather than project includes — the same bar
  `runtime-external-consumer` also does not yet meet for the JVM path.
- The full foreground/offline/retry/circuit/conflict/event/asset/
  cancellation/concurrency/resource-limit/migration/termination/relaunch
  matrix `#101`'s acceptance criteria require.
