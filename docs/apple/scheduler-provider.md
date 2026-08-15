# iOS SchedulerProvider (`dataloom-platform-ios`)

## Status

Second bounded slice of the eventual `dataloom-ios` platform artifact
required by `#101` (DL-039A), alongside the first slice,
[`AppleConnectivityProvider`](connectivity-provider.md). This slice adds
exactly one capability: a real `SchedulerProvider` implementation for iOS.
Nothing else.

## What this is

`AppleSchedulerProvider`, in package `io.dataloom.platform.ios.scheduling`,
implements `io.dataloom.api.scheduling.SchedulerProvider` using Apple's
`BGTaskScheduler` (the BackgroundTasks framework), mirroring how
`dataloom-scheduler-workmanager`'s `WorkManagerSchedulerProvider` implements
the same contract for Android using AndroidX WorkManager.

```
AppleSchedulerProvider
      |
      v  schedule() / cancel()
planSchedule()                         (commonMain, pure function)
      |  translates ScheduleConstraints/SchedulingDelay into a SchedulePlan,
      |  or rejects the request before touching the platform
      v
SchedulePlan
      |
      v
submitBackgroundTaskRequest() / cancelBackgroundTaskRequest() /
pendingBackgroundTaskIdentifiers()     (iosMain, the only functions touching
                                         platform.BackgroundTasks)
      |
      v
BGTaskScheduler.shared
```

### Behavior

- `schedule()` translates a `ScheduleRequest` into a `BGProcessingTaskRequest`
  (when `ScheduleConstraints` declares a connectivity requirement or requires
  charging -- the only `BGTaskRequest` subclass exposing
  `requiresNetworkConnectivity` / `requiresExternalPower`) or a
  `BGAppRefreshTaskRequest` (Apple's lightweight, unconstrained background
  refresh mechanism, used when no constraint is declared), then submits it via
  `BGTaskScheduler.shared.submitTaskRequest(_:error:)`.
- `cancel()` calls `BGTaskScheduler.shared.cancelTaskRequestWithIdentifier(_:)`,
  a documented no-op when no pending request exists for the identifier.
- Classification of a `ScheduleConstraints` combination into a request kind
  (`planSchedule`, in `internal/SchedulePlan.kt`) is a pure function with no
  dependency on `BGTaskScheduler` and is unit-tested directly in
  `src/commonTest/.../internal/SchedulePlanTest.kt`, matching
  `AppleConnectivityProvider`'s `classifyPath` pattern.
- `BGTaskScheduler`, `BGTaskRequest`, `NSError`, and every other platform type
  stay inside three `internal actual` functions in
  `src/iosMain/.../internal/BackgroundTaskGateway.ios.kt`. Nothing above that
  boundary, including the public `AppleSchedulerProvider` class, references a
  platform type.

## The Info.plist / app-launch pre-registration constraint

**This is the central constraint of this slice, and it changes how host
applications must integrate `AppleSchedulerProvider`.**

`BGTaskScheduler` requires every task identifier an app will ever submit to
be, in this order:

1. **listed in the host app's `Info.plist`**, under the
   `BGTaskSchedulerPermittedIdentifiers` array, and
2. **registered once, at app-launch time**, via
   `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`,
   which must complete before `applicationDidFinishLaunching(_:)` returns.

DataLoom is a library embedded in the host app, not the host app itself:

- it **cannot edit the host app's `Info.plist`** to add identifiers to
  `BGTaskSchedulerPermittedIdentifiers`, and
- `AppleSchedulerProvider.schedule()` is called at arbitrary times during the
  app's lifetime, not guaranteed to run before
  `applicationDidFinishLaunching` returns -- so it cannot itself call
  `register(forTaskWithIdentifier:using:launchHandler:)` and have that
  registration be valid.

`SchedulerProvider`'s own contract independently forbids implementations from
"automatically initializ[ing] or register[ing] themselves" -- so
`AppleSchedulerProvider` **never calls
`register(forTaskWithIdentifier:using:launchHandler:)`** at all. This is a
deliberate design choice, not an oversight.

### What this means for host applications

The host application integrating `dataloom-platform-ios` must, on its own:

1. Add every DataLoom schedule identifier it intends to use to its
   `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers` array.
2. Call `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`
   for each of those identifiers before `applicationDidFinishLaunching(_:)`
   returns.
3. Construct `AppleSchedulerProvider(preRegisteredIdentifiers = setOf(...))`
   with the exact same set of identifiers.

A `ScheduleRequest` whose `ScheduleId` is outside the `preRegisteredIdentifiers`
set passed at construction is rejected with a canonical
`SCHEDULER_IOS_IDENTIFIER_NOT_PREREGISTERED` configuration error **before**
this provider touches `BGTaskScheduler` at all. This gives a clear, actionable
DataLoom error instead of forwarding an opaque platform `NSError` (or, worse,
silently accepting a request that the platform would reject or ignore).

## Existing-schedule policy: partial parity, documented honestly

`BGTaskScheduler` has no direct equivalent of WorkManager's
`ExistingWorkPolicy`, so `ExistingSchedulePolicy` is emulated, and the two
values are **not equally well-supported**:

- **`REPLACE`**: unconditionally calls
  `cancelTaskRequestWithIdentifier(_:)` for the identifier (a documented
  no-op if nothing is pending) and then submits the new request. This does
  not require querying pending state first, and is not subject to a race
  window.
- **`KEEP`**: performs one bounded synchronous query of pending request
  identifiers via `getPendingTaskRequestsWithCompletionHandler(_:)` (blocking
  on a dispatch semaphore, the same bounded-query pattern
  `AppleConnectivityProvider` uses for `NWPathMonitor`). If the identifier is
  already pending, the new request is not submitted and `schedule()` still
  reports success. **This query-then-decide sequence is not atomic** the way
  WorkManager's `enqueueUniqueWork(KEEP)` is atomic at the WorkManager
  database layer -- a concurrent submission for the same identifier between
  this query and the decision it drives could still race. This is a known,
  documented imperfect-parity limitation of `BGTaskScheduler`'s API surface,
  not a claim of atomic uniqueness.

## Unsupported constraint: `ConnectivityRequirement.UNMETERED`

`BGProcessingTaskRequest.requiresNetworkConnectivity` is a single boolean --
there is no `BGTaskScheduler` API to require specifically-unmetered
(non-cellular) connectivity the way WorkManager's `NetworkType.UNMETERED`
does on Android. Silently mapping `UNMETERED` to
`requiresNetworkConnectivity = true` would let a schedule execute over a
metered connection while claiming an unmetered guarantee. Instead,
`schedule()` rejects `ConnectivityRequirement.UNMETERED` with a canonical
`SCHEDULER_IOS_UNSUPPORTED_CONSTRAINT` configuration error rather than
under-enforcing it.

## Explicit scope boundary

This slice is **only** `SchedulerProvider`. It does **not** attempt, and must
not be read as claiming, any of the following -- each remains a separate,
later `#101` slice:

- iOS lifecycle integration
- files (an iOS-native storage/queue provider)
- secure platform integration (Keychain-backed key storage)
- an aggregate `dataloom-ios` convenience artifact wiring every slice
  (connectivity, scheduling, and the slices above) together, the way
  `dataloom-android` aggregates its four Android provider modules (see
  `docs/android/dataloom-android.md`)

It also does not attempt any of the following, which stay out of scope for
`SchedulerProvider` itself, matching its documented "must not" list:
executing synchronization directly, performing storage or transport
operations, implementing retry policy, or exposing `BGTaskScheduler`,
`BGTaskRequest`, or any other platform type through its public API.

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
  -Pdataloom.appleKlibCrossCompile=true` -- succeeded, with no warnings, for
  all three targets. This proves the `platform.BackgroundTasks` cinterop
  declarations used (`BGTaskScheduler`, `BGTaskScheduler.sharedScheduler`,
  `submitTaskRequest`, `cancelTaskRequestWithIdentifier`,
  `getPendingTaskRequestsWithCompletionHandler`, `BGAppRefreshTaskRequest`,
  `BGProcessingTaskRequest`, `requiresNetworkConnectivity`,
  `requiresExternalPower`, the `BGTaskSchedulerErrorCode*` constants) resolve
  and type-check against the Kotlin/Native-bundled Apple platform klibs
  (`org.jetbrains.kotlin.native.platform.BackgroundTasks`), klib-cross-compiled
  without Xcode. `platform.Foundation.NSDate.dateByAddingTimeInterval` and
  `NSError.code`/`.domain` (used to compute `earliestBeginDate` and classify
  submission failures) resolved the same way against
  `org.jetbrains.kotlin.native.platform.Foundation`.
- `./gradlew.bat :dataloom-platform-ios:compileTestKotlinIosArm64
  :dataloom-platform-ios:compileTestKotlinIosSimulatorArm64
  :dataloom-platform-ios:compileTestKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` -- succeeded. The `SchedulePlanTest`
  suite (alongside the pre-existing `ConnectivityClassificationTest`)
  type-checks and klib-compiles for all three iOS targets.
- `./gradlew.bat checkKotlinAbi -Pdataloom.appleKlibCrossCompile=true` --
  initially failed only on `dataloom-platform-ios`'s own baseline (the new
  `AppleSchedulerProvider` public surface). Ran
  `:dataloom-platform-ios:updateKotlinAbi`, then re-ran `checkKotlinAbi`,
  which passed across the whole build. `git diff --stat` confirmed only
  `dataloom-platform-ios/api/dataloom-platform-ios.klib.api` changed (13
  insertions, 0 deletions -- purely additive); no other module's baseline was
  touched.

### What was *not* verified

This is a Windows host with no macOS, Xcode, iOS simulator, or physical
device available. As a direct consequence, matching
[`connectivity-provider.md`](connectivity-provider.md)'s own disclosure:

- `SchedulePlanTest` was never actually **executed** -- running a
  Kotlin/Native iOS test binary requires linking and running on an actual
  macOS host or simulator, which this environment cannot do. Its compilation
  (above) confirms the test type-checks against the production code; it does
  not confirm the assertions pass at runtime.
- `submitBackgroundTaskRequest`, `cancelBackgroundTaskRequest`, and
  `pendingBackgroundTaskIdentifiers` -- the three functions that call
  `BGTaskScheduler` -- were never executed against a real `BGTaskScheduler`
  instance. Their correctness rests on matching the documented
  BackgroundTasks C/Objective-C API shape and successfully klib-compiling
  against the Kotlin/Native-bundled Apple platform declarations, not on any
  observed runtime behavior.
- No device or simulator run of `AppleSchedulerProvider` exists, and no host
  app was built to exercise the Info.plist + launch-time registration flow
  described above. A future slice (or a macOS-hosted CI run) still needs to
  confirm `SchedulePlanTest` actually passes and that `schedule()` /
  `cancel()` behave correctly against a real `BGTaskScheduler`, including
  whether a genuinely mis-registered identifier surfaces the
  `BGTaskSchedulerErrorCodeNotPermitted` mapping this module expects.
