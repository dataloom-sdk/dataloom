# Native Android reference consumer

## Status

**Compile-only fixture — first bounded slice of `#101` (DL-039A).** The
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
a real `DataLoomBuilder` assembly — this module is that proof, at compile
time: if any provider's constructor signature, required capability, or
`DataLoomBuilder` binding shape drifts out of sync with what a real native
Android application would need, this module fails to compile.

It does **not** prove runtime behavior on a device or emulator.
`buildReferenceDataLoom(context)` is real, correct wiring code — not a
stub — but nothing in this module calls `DataLoom.initialize()` or
`DataLoom.synchronize()` against a real Android `Context`, `Room`
database, or `WorkManager` instance. This repository has no Robolectric or
instrumented-test infrastructure yet; adding either is a separate,
larger follow-up, not silently claimed as covered here. This mirrors the
same documented boundary `runtime-external-consumer` already established
for the JVM-only public runtime surface — "compile-only fixture" is an
established pattern in this repo, not a new one invented for this module.

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

Verified for real: `compileDebugKotlin` succeeds (proving the whole
provider-composition graph resolves and type-checks), `lintDebug` passes,
and the full repository's Android `check` task (excluding a pre-existing,
unrelated `dataloom-storage-datastore` test failure — see that module's
own test report) is unaffected. No ABI baseline applies — this is a plain
`com.android.library` module, not a KMP convention-plugin module, matching
every other Android provider module in this repository.

## What remains open on `#101`

- KMP Android: shared modules still expose only a `jvm()` target consumed
  by Android bytecode, not an explicit `androidTarget()` KMP variant.
  Adding one is a separate, larger, and structurally riskier piece of work
  (this repository's own history with AGP 9+ and `androidTarget()`/
  `com.android.library` conflicts — see the SQLDelight module-split
  precedent — means this needs its own careful, isolated slice).
- KMP iOS: `dataloom-ios` does not exist. No production Apple lifecycle,
  connectivity, `BGTaskScheduler`, files, security, or persistence
  adapters exist; `dataloom-apple` today only assembles the XCFramework
  distribution surface.
- Runtime proof (Robolectric or instrumented) that this module's wiring
  actually initializes and synchronizes successfully, not just compiles.
- Native Android and KMP Android+iOS consumers resolving staged/published
  artifacts rather than project includes — the same bar
  `runtime-external-consumer` also does not yet meet for the JVM path.
- The full foreground/offline/retry/circuit/conflict/event/asset/
  cancellation/concurrency/resource-limit/migration/termination/relaunch
  matrix `#101`'s acceptance criteria require.
