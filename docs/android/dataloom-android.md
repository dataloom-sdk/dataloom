# dataloom-android platform artifact

## Status

**Real, production module — second bounded slice of `#101` (DL-039A),
alongside [the reference consumer](reference-consumer.md).** `dataloom-android`
is issue `#101`'s own explicitly required "stable `dataloom-android`...
platform artifact with narrow provider/SPI dependencies" — not a fixture, not
a stub. It aggregates the four core Android provider modules into one
convenience dependency and supplies real, public wiring helpers a host
application can actually call.

## Why this exists, and why it isn't the KMP-Android-variant fix

This module does **not** attempt to give `dataloom-model`/`dataloom-api`/
`dataloom-runtime` an explicit `androidTarget()` KMP variant — that path is
confirmed genuinely blocked in this repository's current Kotlin/AGP
combination; see
[kmp-android-target-blocker.md](kmp-android-target-blocker.md).

A sibling-module split (the pattern that unblocked
`dataloom-storage-sqldelight-android`) was considered as an alternative and
deliberately **not** applied the same way here. That precedent's sibling
module exists because it contains real, distinct Android-specific code
(`AndroidSqliteDriver` wiring) that the shared JVM+iOS module cannot host.
`dataloom-model`, `dataloom-api`, `dataloom-core`, and `dataloom-runtime`
have no Android-specific implementation today — they are pure common Kotlin,
which is exactly why Android already consumes their `jvm()` target
successfully (proven by the reference consumer). A same-shaped sibling
module for any of them would contain nothing but `api(project(":..."))` and
an empty manifest: a second artifact coordinate with zero capability delta
over what already works, built only to have *something* labeled "Android
variant." This repository has an explicit standing rule against building
infrastructure ahead of a concrete, non-speculative need, and an empty
wrapper module is exactly that.

`dataloom-android` is the alternative, non-speculative half of `#101`'s
Android platform-artifact requirement: instead of manufacturing a hollow
variant for modules with no Android-specific code, it aggregates the
modules that *do* have real Android-specific implementations
(`dataloom-connectivity-android`, `dataloom-storage-room`,
`dataloom-queue-room`, `dataloom-scheduler-workmanager`) and adds real,
previously-nonexistent production wiring code on top.

## What it provides

- `androidDataLoomProviders(context, ...)` — constructs the four core Android
  providers (`AndroidConnectivityProvider`, `RoomStorageProvider`,
  `RoomQueueProvider`, `WorkManagerSchedulerProvider`) from an application
  `Context`, using each provider's own documented production construction
  path (`DataLoomStorageDatabaseBuilder.build`, `DataLoomDatabaseBuilder.build`,
  etc.).
- `DataLoomBuilder.installAndroidProviders(providers, transport)` — registers
  those four providers plus an application-supplied `TransportProvider` and
  configures both the direct-synchronization and strategy-evaluation default
  bindings to resolve them. Does not configure a queue-worker,
  queue-submission, or provider-protection capability — those stay explicit,
  separate `DataLoomBuilder` calls, since they need application-specific
  policy decisions (retry policy, work resolution, circuit scopes) this
  module cannot make on the host's behalf.

DataLoom never ships a default transport — endpoint selection,
authentication, and payload serialization stay application-owned. Supply
`dataloom-transport-ktor`, `dataloom-transport-retrofit`,
`dataloom-transport-graphql`, `dataloom-transport-grpc`, or a hand-written
`TransportProvider`.

## Dogfooded, not just self-declared usable

[`runtime-android-reference-consumer`](reference-consumer.md) was refactored
to consume `installAndroidProviders`/`androidDataLoomProviders` instead of
hand-wiring the four providers directly, and its own `check` (including
`lintDebug`) still passes. This module's public API is proven usable by a
real, independent consumer — not just asserted usable in its own KDoc.

## What this does not prove

Same explicit boundary as the reference consumer: this is verified at
compile time (real `compileDebugKotlin`, `lintDebug`, and full `assembleRelease`
AAR packaging all pass), not at runtime. Nothing in this repository yet calls
`DataLoom.initialize()`/`DataLoom.synchronize()` against a real Android
`Context`, `Room` database, or `WorkManager` instance — no Robolectric or
instrumented-test infrastructure exists yet. That remains a separate, larger
follow-up.

## Deliberately excluded from this module

- `dataloom-storage-datastore` and `dataloom-storage-sqldelight-android` —
  alternative storage choices an application opts into individually, not
  part of the "core four" this module wires together. Bundling every
  storage option into one umbrella would force a choice on applications that
  want exactly one.
- Any `TransportProvider` — see "What it provides" above.
- Queue-worker, queue-submission, and provider-protection wiring — these
  need application-owned policy decisions this module cannot make generically.

## Build and verification

Gated behind `DATALOOM_ANDROID_BUILD=true`, same as every other Android
module in this repository.

```bash
DATALOOM_ANDROID_BUILD=true ./gradlew :dataloom-android:check
DATALOOM_ANDROID_BUILD=true ./gradlew :dataloom-android:build
```

Verified for real: `compileDebugKotlin`, `lintDebug`, `check`, and a full
release `assembleRelease` AAR build all succeed;
`runtime-android-reference-consumer:check` (now depending on
`dataloom-android` instead of the four providers directly) still passes; the
full documented Android module build list plus this new module builds
together without regression; repository-wide `checkKotlinAbi` is unaffected
(plain `com.android.library` module, no KMP convention plugin, no ABI
baseline applies — matching every other Android provider module).
