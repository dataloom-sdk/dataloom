# dataloom-ios platform aggregation (`dataloom-platform-ios`)

## Status

**Third bounded slice of `#101` (DL-039A), inside the existing
`dataloom-platform-ios` module.** This slice adds `AppleDataLoomProviders`,
`appleDataLoomProviders(...)`, and `DataLoomBuilder.installAppleProviders(...)`
-- real, public wiring code that bundles the four core iOS providers into
one convenience dependency, mirroring `dataloom-android`'s own
`AndroidDataLoomProviders` / `androidDataLoomProviders` /
`installAndroidProviders` in shape and philosophy (see
[`dataloom-android.md`](../android/dataloom-android.md)). It builds on the
two prior `dataloom-platform-ios` slices,
[`AppleConnectivityProvider`](connectivity-provider.md) and
[`AppleSchedulerProvider`](scheduler-provider.md).

This is **compile-time-verified aggregation only**. It is not a new
provider implementation -- it wires together four providers that already
exist and are independently documented elsewhere in this repository.

## What it provides

- `AppleDataLoomProviders` -- a data-holder class bundling
  `connectivity: AppleConnectivityProvider`, `storage: SqlDelightStorageProvider`,
  `queue: AppleFileQueueProvider`, and `scheduler: AppleSchedulerProvider`.
  `internal constructor`, matching `AndroidDataLoomProviders`.
- `appleDataLoomProviders(preRegisteredIdentifiers, directoryPath, storageDatabaseName, queueFileName)`
  -- constructs the four providers:
  - `AppleConnectivityProvider()` -- no parameters, matching its existing
    public constructor.
  - `SqlDelightStorageProvider(createIosSqlDelightStorageDatabase(storageDatabaseName))`
    -- opens (and, on first run, creates) a real SQLite database via
    SQLDelight's native driver. `storageDatabaseName` defaults to
    `createIosSqlDelightStorageDatabase`'s own default, `"dataloom-storage.db"`.
  - `AppleFileQueueProvider(directoryPath, queueFileName)` -- `directoryPath`
    has **no default**. The host application must resolve its own
    absolute, application-private directory (normally a dedicated child of
    Application Support) and supply it -- DataLoom does not resolve
    platform paths itself, matching `AppleFileQueueProvider`'s own existing
    constructor contract (see the precedent in
    `runtime-external-consumer/src/iosMain/kotlin/io/dataloom/consumer/AppleQueueProviderExternalConsumerProbe.kt`).
    `queueFileName` defaults to `AppleFileQueueProvider.DEFAULT_FILE_NAME`.
  - `AppleSchedulerProvider(preRegisteredIdentifiers)` -- `preRegisteredIdentifiers`
    has **no default**. The host application must supply the exact set of
    `BGTaskScheduler` identifiers it has already declared in its
    `Info.plist` and registered at app-launch time -- DataLoom does not
    invent a default set; see [`scheduler-provider.md`](scheduler-provider.md)
    for the full Info.plist / launch-registration constraint this reflects.
- `DataLoomBuilder.installAppleProviders(providers, transport)` -- registers
  those four providers plus a host-supplied `TransportProvider` and
  configures both the direct-synchronization and strategy-evaluation
  default bindings to resolve them. Does not configure a queue-worker,
  queue-submission, or provider-protection capability -- those stay
  explicit, separate `DataLoomBuilder` calls, since they need
  application-specific policy decisions (retry policy, work resolution,
  circuit scopes) this module cannot make on the host's behalf. This is a
  near-exact structural mirror of `installAndroidProviders`, including its
  explicit doc-comment note that DataLoom never ships a default transport.

DataLoom never ships a default transport -- endpoint selection,
authentication, and payload serialization stay application-owned. Supply
`dataloom-transport-ktor`, `dataloom-transport-retrofit`,
`dataloom-transport-graphql`, `dataloom-transport-grpc`, or a hand-written
`TransportProvider`.

## Why storage and queue are not new iOS-specific modules

Unlike the Android side of `#101`, where `dataloom-connectivity-android`,
`dataloom-storage-room`, `dataloom-queue-room`, and
`dataloom-scheduler-workmanager` are each separate provider modules,
`SqlDelightStorageProvider` (storage) and `AppleFileQueueProvider` (queue)
already have real, working iOS implementations elsewhere in this repository
-- they were not gaps `dataloom-platform-ios` needed to fill:

- `SqlDelightStorageProvider` lives in `dataloom-storage-sqldelight`'s
  `commonMain`, backed on iOS by `createIosSqlDelightStorageDatabase` in
  that module's `iosMain`.
- `AppleFileQueueProvider` lives in `dataloom-runtime`'s `iosMain`.

This slice's job was purely to add the two new cross-module dependencies
(`dataloom-runtime`, `dataloom-storage-sqldelight`) to
`dataloom-platform-ios/build.gradle.kts` and wire the four providers
together -- not to implement storage or queue behavior itself.

## Why the wiring code lives in `src/iosMain`, not `src/commonMain`

`AppleConnectivityProvider` and `AppleSchedulerProvider` (the two prior
slices) live in `dataloom-platform-ios`'s `src/commonMain`, because neither
one directly references a declaration that exists only in a dependency's
`iosMain` source set -- their platform calls are hidden behind `internal
actual` functions.

`AppleDataLoomProviders` is different: it directly references
`AppleFileQueueProvider` (declared in `dataloom-runtime`'s `iosMain`, not
its `commonMain`) and `createIosSqlDelightStorageDatabase` (declared in
`dataloom-storage-sqldelight`'s `iosMain`). Neither symbol is part of
either dependency's common API surface, so a `commonMain` file in
`dataloom-platform-ios` cannot resolve them -- `dataloom-platform-ios`'s
own `commonMain` metadata compilation only type-checks against
dependencies' `commonMain` API. Consequently `AppleDataLoomProviders.kt`
lives in `dataloom-platform-ios/src/iosMain`, a source set Kotlin's default
hierarchy template already wires as the natural intermediate between
`commonMain` and the module's three `iosArm64`/`iosSimulatorArm64`/`iosX64`
targets (the same source set `NetworkPathQuery.ios.kt` and
`BackgroundTaskGateway.ios.kt` already use for their `actual`
implementations).

## Dependency gating checked, no mismatch found

`dataloom-runtime` and `dataloom-storage-sqldelight` both use the same
`io.dataloom.kotlin.multiplatform-library` convention plugin
(`build-logic/.../DataLoomKotlinMultiplatformLibraryPlugin.java`) as every
other Kotlin Multiplatform module in this repository. That plugin declares
`iosArm64()`/`iosSimulatorArm64()`/`iosX64()` targets under the exact same
condition (`isAppleHost || dataloom.appleKlibCrossCompile`) that
`settings.gradle.kts` uses to decide whether to `include(":dataloom-platform-ios")`
at all. Both modules are also included in `settings.gradle.kts`
unconditionally (outside the Apple-gated block), so they are always present
when `dataloom-platform-ios` itself is present. There is no gating
mismatch between this module and its two new dependencies.

## Explicit scope boundary

This slice is **only** the aggregation wiring described above. It does
**not** attempt, and must not be read as claiming, any of the following:

- **iOS lifecycle integration.** There is no `LifecycleProvider` contract
  anywhere in this codebase -- not a partial implementation, not a stub.
  Designing one (and deciding whether/how it maps to `UIApplication`
  lifecycle notifications) is a design question for a future session, not
  something this slice attempts.
- **Secure platform integration (Keychain-backed key storage).**
  `KeyReference` (`dataloom-model/.../security/KeyReference.kt`) explicitly
  states in its own KDoc that "DataLoom never generates, stores, resolves,
  or rotates the material it names" -- key custody is intentionally the
  host application's job. This slice does not build any Keychain-backed key
  storage, and none should be inferred from its existence.
- **Actual device/simulator/CI runtime proof for any part of the
  `dataloom-ios` stack.** See "What was not verified" below -- this is the
  same honest caveat every other iOS slice in this repository carries.

## Build gating

`dataloom-platform-ios` is included in the Gradle build under the same
condition as `dataloom-apple` in `settings.gradle.kts`: on macOS hosts, or
when klib cross-compilation is explicitly requested with
`-Pdataloom.appleKlibCrossCompile=true`. It is not part of the default
Windows/Linux build.

## Verification performed for this slice

Run from a Windows host, with no Xcode/macOS available:

- `./gradlew.bat :dataloom-platform-ios:compileKotlinIosArm64
  :dataloom-platform-ios:compileKotlinIosSimulatorArm64
  :dataloom-platform-ios:compileKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` -- succeeded (`BUILD SUCCESSFUL in
  2m 58s`). This proves the new `dataloom-runtime` and
  `dataloom-storage-sqldelight` project dependencies resolve, and that
  `AppleDataLoomProviders`, `appleDataLoomProviders`, and
  `installAppleProviders` klib-cross-compile cleanly for all three iOS
  targets against `AppleFileQueueProvider` and
  `createIosSqlDelightStorageDatabase`'s real `iosMain` declarations,
  without Xcode.
- `./gradlew.bat checkKotlinAbi -Pdataloom.appleKlibCrossCompile=true` --
  initially failed only on `dataloom-platform-ios`'s own baseline (the new
  `AppleDataLoomProviders` public surface: the class itself, both its
  properties, `appleDataLoomProviders`, and `installAppleProviders`). Ran
  `:dataloom-platform-ios:updateKotlinAbi`, then re-ran `checkKotlinAbi`,
  which passed across the whole build. `git diff --stat` and a
  `git diff | grep -E "^-[^-]" | grep -v "^--- "` check on
  `dataloom-platform-ios/api/dataloom-platform-ios.klib.api` both confirmed
  the change is purely additive (14 insertions, 0 removed lines) and no
  other module's baseline changed.
- Full repository `./gradlew.bat jvmTest -Pdataloom.appleKlibCrossCompile=true`
  -- `BUILD SUCCESSFUL`. Parsing all 205 `TEST-*.xml` JUnit result files
  produced under `build/test-results/jvmTest` across every module gives
  **2935 tests, 0 failures, 0 errors, 0 skipped**. This proves the two new
  cross-module dependencies did not regress anything downstream on the JVM
  target. `dataloom-platform-ios` itself has no `jvmTest` task (it declares
  only iOS targets), so it does not appear in this count.
- Every new/changed file (`dataloom-platform-ios/build.gradle.kts`,
  `dataloom-platform-ios/api/dataloom-platform-ios.klib.api`,
  `dataloom-platform-ios/src/iosMain/kotlin/io/dataloom/platform/ios/AppleDataLoomProviders.kt`)
  was byte-scanned for embedded NUL bytes (PowerShell
  `[System.IO.File]::ReadAllBytes($f) -contains 0`) -- none found. No new
  test files were added in this slice, so the separate comma-in-backtick
  test-name check (`fun \`[^\`]*,[^\`]*\``, a known Kotlin/Native symbol
  mangling hazard in this repository) has nothing to check against.

### What was *not* verified

This is a Windows host with no macOS, Xcode, iOS simulator, or physical
device available. As a direct consequence, matching every prior
`dataloom-platform-ios` slice's own disclosure:

- No unit test exists for `AppleDataLoomProviders` itself -- it is thin
  wiring code (constructor calls and `DataLoomBuilder` registration calls)
  with no independent logic of its own to unit test, the same reasoning
  `AndroidDataLoomProviders` follows on the Android side.
- Nothing in this repository has ever called
  `appleDataLoomProviders(...)` or `installAppleProviders(...)` against a
  real iOS process, `BGTaskScheduler`, `NWPathMonitor`, SQLite file, or
  file-backed queue directory. Its correctness rests on matching
  constructor signatures and klib-compiling successfully -- not on any
  observed runtime behavior.
- No device or simulator run, and no dogfooding reference consumer
  (analogous to `runtime-android-reference-consumer` on the Android side)
  exists yet for this iOS wiring. A future slice would need to build one to
  prove `installAppleProviders` composes with `DataLoomBuilder.build()` the
  way the Android reference consumer already proves for
  `installAndroidProviders`.

## Remaining `#101` iOS parity gaps after this slice

- **iOS lifecycle integration** -- no `LifecycleProvider` contract exists in
  this codebase at all. This is a design question (what the contract should
  even look like, and how it maps to `UIApplication` lifecycle
  notifications) for a future session, not something attempted here.
- **Actual device/simulator/CI runtime proof** -- for `AppleConnectivityProvider`,
  `AppleSchedulerProvider`, and now this aggregation, every claim made in
  this repository about iOS behavior rests on successful klib
  cross-compilation from a Windows host, not on any observed execution
  against real `Network.framework`, `BackgroundTasks`, or SQLite/file I/O
  behavior. A macOS-hosted CI run (or manual device/simulator testing)
  remains outstanding for the entire `dataloom-ios` stack, not just this
  slice.
- **A dogfooded iOS reference consumer** analogous to
  `runtime-android-reference-consumer`, proving `installAppleProviders`
  composes with a real `DataLoomBuilder.build()` call, does not yet exist.
