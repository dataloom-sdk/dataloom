# iOS ConnectivityProvider (`dataloom-platform-ios`)

## Status

First bounded slice of the eventual `dataloom-ios` platform artifact
required by `#101` (DL-039A). This module currently provides exactly one
capability: a real `ConnectivityProvider` implementation for iOS. Nothing
else.

## What this is

`dataloom-platform-ios` is a Kotlin Multiplatform module declaring only
iOS Kotlin/Native targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`) --
matching `dataloom-apple`'s target declaration style. It contains
`AppleConnectivityProvider`, an implementation of
`io.dataloom.api.connectivity.ConnectivityProvider` backed by
`NWPathMonitor` from Apple's `Network.framework`, mirroring how
`dataloom-connectivity-android`'s `AndroidConnectivityProvider` implements
the same contract for Android using `ConnectivityManager`.

```
AppleConnectivityProvider
      |
      v  currentConnectivity()
currentNetworkPathObservation()  (iosMain, the only file touching Network.framework)
      |  starts a fresh nw_path_monitor_t, blocks on a dispatch semaphore
      |  until the first update handler callback, cancels the monitor
      v
NetworkPathObservation  (commonMain, platform-independent)
      |
      v  classifyPath()  (commonMain, pure function)
ConnectivitySnapshot
```

### Behavior

- `currentConnectivity()` performs one bounded synchronous query. It starts
  a new `NWPathMonitor`, waits for its first update-handler callback, reads
  `nw_path_get_status`, `nw_path_is_expensive`, and `nw_path_is_constrained`
  from that single callback, cancels the monitor, and returns. No monitor,
  dispatch queue, or semaphore is retained afterward.
- No automatic monitoring, no `Flow<ConnectivitySnapshot>`, no caching, no
  automatic retry -- matching `ConnectivityProvider`'s documented "Deferred
  features" and "must not" list exactly.
- `NWPathMonitor`, `nw_path_t`, and every other Network.framework type stay
  inside the single `internal actual fun currentNetworkPathObservation()`
  in `src/iosMain`. Nothing above that boundary (including the public
  `AppleConnectivityProvider` class) references a platform network type.
- Classification rules (raw `nw_path_status_t` plus the expensive/
  constrained flags, translated into `ConnectivityStatus` and
  `isMetered`) live in a small pure function, `classifyPath`, in
  `src/commonMain/.../internal/ConnectivityClassification.kt`. It has no
  dependency on `NWPathMonitor` and is unit-tested directly in
  `src/commonTest/.../ConnectivityClassificationTest.kt`.

### Classification mapping

| NWPathMonitor status  | `ConnectivityStatus` | `isMetered`                                    |
|------------------------|-----------------------|------------------------------------------------|
| `satisfied`            | `AVAILABLE`           | `true` if expensive or constrained, else `false` |
| `satisfiable`          | `LIMITED`             | `null` (undetermined)                           |
| `unsatisfied`          | `UNAVAILABLE`         | `null` (undetermined)                           |
| no callback observed   | `UNKNOWN`             | `null` (undetermined)                           |

## Explicit scope boundary

This slice is **only** `ConnectivityProvider`. It does **not** attempt, and
must not be read as claiming, any of the following -- each remains a
separate, later `#101` slice:

- iOS lifecycle integration
- background execution (a `BGTaskScheduler`-equivalent scheduler provider)
- files (an iOS-native storage/queue provider)
- secure platform integration (Keychain-backed key storage)
- an aggregate `dataloom-ios` convenience artifact wiring the above
  together, the way `dataloom-android` aggregates its four Android provider
  modules (see `docs/android/dataloom-android.md`)

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
  -Pdataloom.appleKlibCrossCompile=true` -- succeeded. This proves the
  `platform.Network` / `platform.darwin` cinterop declarations used
  (`nw_path_monitor_create`, `nw_path_monitor_set_queue`,
  `nw_path_monitor_set_update_handler`, `nw_path_monitor_start`,
  `nw_path_monitor_cancel`, `nw_path_get_status`, `nw_path_is_expensive`,
  `nw_path_is_constrained`, and the `dispatch_queue_create` /
  `dispatch_semaphore_*` synchronization primitives) resolve and type-check
  against the Kotlin/Native-bundled Apple platform klibs, klib-cross-compiled
  without Xcode.
- `./gradlew.bat :dataloom-platform-ios:compileTestKotlinIosArm64
  :dataloom-platform-ios:compileTestKotlinIosSimulatorArm64
  :dataloom-platform-ios:compileTestKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` -- succeeded. The
  `ConnectivityClassificationTest` suite type-checks and klib-compiles for
  all three iOS targets.
- `./gradlew.bat checkKotlinAbi -Pdataloom.appleKlibCrossCompile=true` --
  passes across the whole build after generating this module's initial ABI
  baseline (`dataloom-platform-ios/api/dataloom-platform-ios.klib.api`) with
  `updateKotlinAbi`; no other module's baseline changed.

### What was *not* verified

This is a Windows host with no macOS, Xcode, iOS simulator, or physical
device available. As a direct consequence:

- `ConnectivityClassificationTest` was never actually **executed** --
  running a Kotlin/Native iOS test binary requires linking and running on an
  actual macOS host or simulator, which this environment cannot do. Its
  compilation (above) confirms the test type-checks against the production
  code; it does not confirm the assertions pass at runtime.
- `currentNetworkPathObservation()`, the one function that calls
  `NWPathMonitor`, was never executed against a real network path. Its
  correctness rests on matching the documented `Network.framework` C API
  shape and successfully klib-compiling against the Kotlin/Native-bundled
  Apple platform declarations -- not on any observed runtime behavior.
- No device or simulator run of `AppleConnectivityProvider` exists. A future
  slice (or a macOS-hosted CI run) still needs to confirm
  `ConnectivityClassificationTest` actually passes and that
  `currentConnectivity()` returns a sensible snapshot against a real
  network path.
