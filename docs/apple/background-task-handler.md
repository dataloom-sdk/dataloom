# iOS background-task queue-drain bridge (`dataloom-scheduler-bgtask`)

## Status

New, separate module for `#101` (DL-039A), alongside `dataloom-platform-ios`'s
existing three slices ([`AppleConnectivityProvider`](connectivity-provider.md),
[`AppleSchedulerProvider`](scheduler-provider.md),
[`AppleDataLoomProviders`](dataloom-ios.md)). This module adds exactly one
capability: `DataLoomBackgroundTaskHandler`, the Apple counterpart to
`dataloom-scheduler-workmanager`'s `DataLoomCoroutineWorker` /
`DataLoomWorkerFactory` bridge. Nothing else.

## What this closes

Every prior round's `#101` "Still pending" text named this specific gap: *"a
real Apple background-scheduler-triggered queue-drain tick (no
`dataloom-scheduler-workmanager` Apple counterpart module exists yet — the
prior iOS proofs only prove the deterministic single-pass
`queueWorker.run(...)` capability, mirroring the equivalent Android
boundary)."* This module is that counterpart module. It does **not** close
the larger, still-separate gap of a real, physical/simulator-executed
`BGTaskScheduler` tick actually invoking it — see "What remains open" below.

## What this is

`DataLoomBackgroundTaskHandler`, in package `io.dataloom.scheduler.bgtask`,
runs exactly one `DataLoomQueueWorker.run(...)` cycle when called from a real
`BGTask` launch handler, and reports the outcome back to the platform via
`BGTask.setTaskCompletedWithSuccess(_:)`.

```
Host app's launchHandler closure
      |
      v  handle(task: BGTask)
DataLoomBackgroundTaskHandler
      |
      |  launches one coroutine on `scope`
      v
queueWorker.run(requestFactory.create())   (DataLoomQueueWorker, dataloom-runtime,
      |                                      the same commonMain capability the
      |                                      IosReferenceConsumer*QueueTest suite
      |                                      already exercises directly)
      v
QueueWorkerRunResult
      |
      v  succeeded() (pure mapping, internal, unit-tested)
task.setTaskCompletedWithSuccess(success)
```

`task.expirationHandler` is set to cancel the in-flight coroutine's `Job`; the
cycle's own `finally` block still performs the one `setTaskCompletedWithSuccess`
call for a cancelled cycle (reporting `false`). See
`DataLoomBackgroundTaskHandler`'s KDoc for the full exactly-once-completion
argument.

## Why a separate module, not `dataloom-platform-ios`

`dataloom-platform-ios/build.gradle.kts` explicitly forbids leaking
`BGTaskScheduler`, `BGTaskRequest`, "or any other Apple platform type"
through its public API. `DataLoomBackgroundTaskHandler.handle(task: BGTask)`
necessarily accepts `BGTask` directly — there is no `BGTaskScheduler`
equivalent of `WorkerFactory` for it to implement instead; the host
application's own `register(forTaskWithIdentifier:using:launchHandler:)`
closure is the only place a `BGTask` instance ever appears, and it must call
into DataLoom itself.

This is exactly the same reasoning that keeps `DataLoomCoroutineWorker`
(which accepts real `android.content.Context` / `androidx.work.WorkerParameters`)
in its own `dataloom-scheduler-workmanager` module rather than inside
`dataloom-android`'s `AndroidDataLoomProviders` aggregator.
`AndroidDataLoomProviders` wires `WorkManagerSchedulerProvider` (the
scheduler half) but never `DataLoomCoroutineWorker` (the worker-bridge half)
— the host application wires that part directly. `AppleDataLoomProviders`
follows the identical split: it wires `AppleSchedulerProvider` (already);
`DataLoomBackgroundTaskHandler` stays a separate, directly-host-wired
dependency, matching the Android precedent exactly rather than inventing a
new shape.

## What the host application must do

```swift
BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.example.sync", using: nil) { task in
    backgroundTaskHandler.handle(task: task as! BGTask)
}
```

This is in addition to, not instead of, `AppleSchedulerProvider`'s own
Info.plist / launch-time registration requirements documented in
[`scheduler-provider.md`](scheduler-provider.md) — that document's
constraints are unchanged by this module.

## Verification performed for this slice

Run from a Windows host, with no Xcode/macOS available:

- `./gradlew.bat :dataloom-scheduler-bgtask:compileKotlinIosArm64
  :dataloom-scheduler-bgtask:compileKotlinIosSimulatorArm64
  :dataloom-scheduler-bgtask:compileKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` — production code compiles and
  type-checks against the real Kotlin/Native-bundled
  `org.jetbrains.kotlin.native.platform.BackgroundTasks` klib for all three
  targets, confirmed directly by dumping that klib's metadata
  (`klib dump-metadata`) beforehand to verify `BGTask.expirationHandler`
  (`var`, `Function0<Unit>?`) and `BGTask.setTaskCompletedWithSuccess(Boolean)`
  resolve exactly as this module's production code assumes, rather than
  guessing the generated Kotlin surface from the Objective-C header.
- `./gradlew.bat :dataloom-scheduler-bgtask:compileTestKotlinIosArm64
  :dataloom-scheduler-bgtask:compileTestKotlinIosSimulatorArm64
  :dataloom-scheduler-bgtask:compileTestKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true`, run individually and sequentially
  — succeeded. `QueueWorkerRunOutcomeTest` (pure, `commonTest`) and
  `DataLoomBackgroundTaskHandlerTest` (`iosTest`, real `BGTask` subclass —
  see its own KDoc) both type-check and klib-compile for all three targets.
- `./gradlew.bat checkKotlinAbi -Pdataloom.appleKlibCrossCompile=true` — ran
  `:dataloom-scheduler-bgtask:updateKotlinAbi` once to generate this new
  module's baseline, then `checkKotlinAbi` passed across the whole build.
  `git status` confirmed only `dataloom-scheduler-bgtask/api/dataloom-scheduler-bgtask.klib.api`
  was added; no existing module's baseline changed.

### What was *not* verified

This is a Windows host with no macOS, Xcode, iOS simulator, or physical
device available, matching every other `dataloom-platform-ios`/
`dataloom-scheduler-bgtask` document's own disclosure:

- Nothing in this module was ever actually **executed**. Compilation confirms
  the production code and tests type-check and klib-compile against the real
  platform bindings; it does not confirm any assertion passes at runtime.
- `DataLoomBackgroundTaskHandlerTest`'s `RecordingBGTask` subclasses the real
  `BGTask` binding and calls its real generated `init`, but overrides
  `setTaskCompletedWithSuccess` rather than forwarding to the platform's own
  implementation — whether invoking that native implementation on a `BGTask`
  this test constructed directly (rather than one the OS supplied through a
  genuine background-task launch) is safe is unknown and unverified; the
  override sidesteps the question rather than answering it. Apple's own
  documentation states host applications receive `BGTask` instances only
  through the OS-driven launch handler; constructing one directly is
  undocumented, not officially unsupported-and-confirmed-safe.
- No real `BGTaskScheduler` ever actually invoked `handle()`. This module
  narrows, but does not close, `#101`'s still-open "a real Apple
  background-scheduler-triggered queue-drain tick" gap: the missing bridge
  code now exists and compiles against the real platform surface, but no
  simulator or device run has yet observed it fire from a genuine
  `BGTaskScheduler` wake-up. A future macOS-hosted CI run (or a real device)
  is still needed to close that remaining gap.

## Explicit scope boundary

This slice is **only** `DataLoomBackgroundTaskHandler`. It does not attempt,
and must not be read as claiming:

- registering or submitting any `BGTaskRequest` (that remains
  `AppleSchedulerProvider`'s sole responsibility, unchanged)
- iOS lifecycle integration
- a real, executed background-scheduler tick (see "What was not verified")
- any change to `AppleDataLoomProviders`'/`installAppleProviders`'s wiring —
  this module is deliberately not referenced from either, matching how
  `DataLoomCoroutineWorker` is not referenced from `AndroidDataLoomProviders`

## Build gating

`dataloom-scheduler-bgtask` is included in the Gradle build under the same
condition as `dataloom-platform-ios` in `settings.gradle.kts`: on macOS
hosts, or when klib cross-compilation is explicitly requested with
`-Pdataloom.appleKlibCrossCompile=true`. It is not part of the default
Windows/Linux build, and it does not depend on `dataloom-platform-ios` (or
vice versa) — the two modules are independent, each consumed separately by
the host application.
