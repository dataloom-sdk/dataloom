# Apple process termination/relaunch: investigated, not achievable as a bounded slice

## Status

**Investigated (2026-08-23). Not achievable as a bounded PR with this
repository's current tooling.** This documents why, so a future attempt does
not re-derive the same conclusion from scratch, and names exactly what
infrastructure would need to exist first. No test claiming to prove real
Apple process termination/relaunch was added by this investigation — writing
one without a genuine underlying mechanism would not actually prove what it
claims, so none was written. `docs/status/market-readiness.md`'s `#94` row
is unchanged by this document: "Prove real Apple process termination/relaunch"
remains listed as still pending, exactly as it already read before this
investigation.

## What this compares against

`#323`/`#327` proved genuine Android OS process kill/relaunch for the
circuit-breaker store and the retry-budget structure respectively:
`AndroidProcessTerminationCircuitBreakerInstrumentedTest` and
`AndroidProcessTerminationRetryBudgetInstrumentedTest` (both in
`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) drive a
second real component hosted in its own `:circuitproof`/`:retrybudgetproof`
Android process, call `ActivityManager.killBackgroundProcesses` on it from
*within the instrumented test process itself*, poll
`ActivityManager.getRunningAppProcesses` to confirm the kill (failing loudly
on a timeout instead of assuming success), then call back into the relaunched
process and assert its pid differs from the first and that its persisted
state matches exactly. This investigation asked whether the same shape of
proof is reachable for Apple.

## What this repository's Apple CI actually runs today

`.github/workflows/apple-validation.yml` runs on `macos-15` and does two
kinds of work, neither of which launches or controls a running app process:

1. `./gradlew ... build ... :dataloom-apple:assembleDataLoomReleaseXCFramework`
   — compiles all three Apple targets, runs the shared `commonTest` suites
   Kotlin/Native's own toolchain executes as ordinary Gradle test tasks
   (`iosSimulatorArm64Test`/`iosX64Test`, invoked transitively via `build`),
   and assembles the XCFramework. Kotlin/Native's toolchain uses the iOS
   Simulator internally to run the test binary, but that usage is entirely
   internal to the Gradle test task — nothing in this workflow, or anywhere
   else in the repository, wraps that invocation with a script that could
   kill and relaunch a *second*, independently launched app process mid-run.
2. `xcodebuild build -scheme DataLoomSwiftSmoke -destination 'generic/platform=iOS Simulator' ...`
   — compiles the Swift smoke fixture against the generated XCFramework.
   This is `xcodebuild build`, not `xcodebuild test`; it never installs or
   launches an app on a simulator at all.

A repository-wide search confirms zero existing uses of `xcrun` or `simctl`
anywhere in this codebase — no workflow, script, or doc references them. The
Apple CI lane has never launched an app on a simulator as a distinct,
externally-controllable OS process; it only compiles, links, and runs
Gradle-hosted unit-test binaries.

## Why Android's technique does not transfer

Android's proof works because of two things Apple/iOS has no equivalent for:

1. **A second, separately addressable OS process inside the same app.**
   Android's `android:process` manifest attribute lets one APK declare a
   component that Android hosts in a second, genuinely separate OS process on
   demand — no new code signing, no new bundle, just a manifest attribute
   merged into the instrumented-test APK only. iOS has no analogous
   mechanism: one app bundle is one process. The nearest analogs — app
   extensions, XPC services, watch companion apps — are separate build
   targets with their own bundle identifiers, provisioning/signing
   requirements, and lifecycle, which is categorically more infrastructure
   than a manifest attribute, not a same-shaped substitute.

2. **An API, callable from inside the test process itself, that asks the OS
   to kill another of the app's own processes.** `ActivityManager.
   killBackgroundProcesses` is callable from ordinary Kotlin test code running
   in the instrumentation process — no host-level tooling is required, which
   is exactly what let `#323`/`#327` stay pure Gradle/Kotlin test files with
   zero new CI script steps. Apple has no equivalent call surface. Killing a
   *different* process on iOS/macOS from test code requires either:
   - host-level tooling invoked from *outside* the process under test —
     `xcrun simctl terminate <device> <bundle-id>` is the real, standard
     mechanism, analogous to `adb shell am force-stop` — but this must run as
     a shell step wrapping the test invocation, not from inside a Kotlin/
     Native XCTest process; or
   - the process under test terminating itself (`exit()`/`abort()`), which
     proves nothing beyond "code can call exit," since the test harness would
     be causing its own death rather than surviving a genuine, externally
     initiated OS kill the way `ActivityManager.killBackgroundProcesses`
     represents.

Because this repository's only Apple test-execution mechanism is Gradle's
internal Kotlin/Native test tasks (see above — no `xcodebuild test`, no
`simctl` usage anywhere), a real `simctl terminate`/`simctl launch` cycle
would need a **new host-level shell script step** wrapping a real, installed,
launchable app bundle — not a same-shaped drop-in Kotlin test file the way
`#323`/`#327` were.

## What would need to exist first

A real proof would need, at minimum:

1. **A real, launchable iOS Simulator app target.** `apple-smoke`'s
   `DataLoomSwiftSmoke` scheme today only compiles a Swift fixture against
   the generated XCFramework (`xcodebuild build`, never `xcodebuild test` or
   `simctl launch`). It is not a runnable, installable app with its own
   bundle identifier a host script could `simctl install`/`launch`/
   `terminate` today; it would need to become one, or a sibling target would
   need to be added.
2. **A new CI script step** (not a Kotlin test file) invoking `xcrun simctl
   launch`/`terminate`/`launch` around that real app, analogous in shape to
   Android's `pixel2Api35DebugAndroidTest` managed-device lane but
   fundamentally host-orchestrated rather than test-process-orchestrated —
   this repository has no precedent for a CI step that launches and kills an
   app as an external process today.
3. **A defined, verifiable way to read back persisted state after the kill.**
   Two shapes are possible, and this investigation did not find either one to
   already be low-cost:
   - *Host-script file inspection*: the script locates the real Simulator
     app-container path (via `xcrun simctl get_app_container <device>
     <bundle-id> data`, not a fixed path known in advance) and reads
     `AppleFileCircuitBreakerStateStore`'s on-disk TSV file directly. This
     store's on-disk format itself is not the blocker — it is already a
     stable, deterministic, `flock`-protected, atomically-renamed TSV file
     under a caller-supplied directory (`AppleFileCircuitBreakerStateStore.kt`,
     `dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/retry/`) — but
     nothing today drives the app to actually write through this store while
     running as a genuine installed process, and nothing resolves its real
     container path from outside the app.
   - *A second app-side read-back after relaunch*, mirroring Android's second
     `ContentProvider` call — but iOS has no manifest-level second-process
     declaration (see above), so this shape would need a real second app
     target/extension with its own signing and entitlements, which is a
     materially larger, separate piece of infrastructure, not a bounded
     addition.

None of this exists in the repository today, and building any one of these
three items is itself a separate, larger piece of infrastructure work — not
a bounded slice addable alongside a single test file the way `#323`/`#327`
were for Android.

## What is not in question

- `AppleFileCircuitBreakerStateStore`'s existing coverage
  (`AppleFileCircuitBreakerStateStoreTest`,
  `dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/retry/`) already
  proves state survives a **new store instance within the same process** —
  useful evidence, but not a genuine OS-level kill, the same gap this
  repository's own [Apple testing guide](apple-testing.md) and
  [Apple platform guide](README.md) already name under "process
  termination/relaunch" and "process-death" as missing V1 evidence.
- This finding does not change that assessment. It adds the specific
  technical reason a same-shaped Android-style proof cannot be built as a
  bounded PR, and a concrete list of what a future, larger PR would need to
  build first.

## References

- [Apple testing guide](apple-testing.md) — names process termination/
  relaunch as a missing V1 gap independently of this investigation.
- [Apple platform guide](README.md) — "Mandatory V1 KMP iOS gaps" lists
  "process-death" recovery evidence as still missing.
- `docs/android/kmp-android-target-blocker.md` — the precedent this document
  follows: a real, reproduced investigation finding recorded so a future
  attempt starts from a different angle instead of re-discovering the same
  dead end.
- `AndroidProcessTerminationCircuitBreakerInstrumentedTest`/
  `AndroidProcessTerminationRetryBudgetInstrumentedTest`
  (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) —
  the Android proof this investigation compared against.
