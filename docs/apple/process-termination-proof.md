# Apple Simulator process-termination/relaunch CI proof (`#94`)

## Status

**New CI infrastructure added 2026-09-12. Unverified until it runs on real
macOS CI.** This document describes what was built, exactly what could and
could not be verified from a Windows development host, and the specific
format/label uncertainties left for a real macOS CI run to resolve.
`docs/status/market-readiness.md`'s `#94` row percentage is deliberately
**unchanged** by this work — see that row's own updated "Still pending" text
and the dated log entry for 2026-09-12.

This document supersedes nothing in
[`docs/apple/process-termination-investigation.md`](process-termination-investigation.md);
it acts on that investigation's own conclusion. Re-read here: that document's
"What would need to exist first" section named three things — (1) a real,
launchable iOS Simulator app target, (2) a new host-level CI script step
using `xcrun simctl`, (3) a defined, verifiable way to read back persisted
state after the kill — and concluded building any one of them was "a
separate, larger piece of infrastructure work." This round re-examined that
conclusion against `.github/workflows/apple-validation.yml` directly: the
job already runs on real `macos-15` GitHub Actions runners with Xcode
available (confirmed by reading the workflow file, unchanged), so the
hardware/environment was never actually the blocker — only the scaffolding
was missing. This document is that scaffolding, built as far as it honestly
could be from a Windows host.

## What this builds

Three new pieces, all new, none reusing or modifying `dataloom-apple`'s
production XCFramework or `apple-smoke`'s existing compile-only fixture:

### 1. `apple-process-termination-proof` (Kotlin/Native module)

A narrow module, gated identically to `dataloom-apple`/`dataloom-platform-ios`/
`dataloom-scheduler-bgtask` in `settings.gradle.kts` (macOS host, or
`-Pdataloom.appleKlibCrossCompile=true`). It declares its own `XCFramework("DataLoomProcessTerminationProof")`
across `iosArm64`/`iosSimulatorArm64`/`iosX64`, entirely separate from
`dataloom-apple`'s `DataLoom.xcframework` — it is never included in that
export list and no host application is expected to depend on it.

Its entire public API is two files:

- `ProcessTerminationProofState` — a plain data holder of `String`/`Int`/`Long`
  fields (`phase`, `consecutiveFailures`, `openUntilEpochMillis`,
  `probeGeneration`, `version`).
- `AppleCircuitBreakerProcessTerminationProof` — a Kotlin `object` (exported
  to Objective-C/Swift as a class with a `shared` singleton accessor) with two
  methods: `openCircuitAndPersist(directoryPath: String): ProcessTerminationProofState`
  and `readPersistedState(directoryPath: String): ProcessTerminationProofState?`.

Internally, both methods drive the real production
`AppleFileCircuitBreakerStateStore` (`dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/retry/`)
via `kotlinx.coroutines.runBlocking`, so from Swift they are ordinary
synchronous methods — no completion-handler bridging is needed anywhere in
the app. `openCircuitAndPersist` performs two real, sequential
compare-and-set writes: a closed record with one recorded failure (version
`null` -> `0`), then an open record building on that version (version `0` ->
`1`) — the same state shape `CircuitBreakerCoordinator` persists when a
circuit opens.

`dataloom-api`/`dataloom-runtime` are depended on with `implementation`, not
`api`, so none of their own types (`CircuitBreakerScope`, `CircuitBreakerState`,
the sealed `CircuitBreakerLoadResult`/`CircuitBreakerCompareAndSetResult`,
`ProviderOperationResult`, ...) are re-exported into this module's own
generated Objective-C header. This was confirmed directly, not assumed, by
generating this module's `.klib.api` ABI dump (`./gradlew.bat
:apple-process-termination-proof:updateKotlinAbi -Pdataloom.appleKlibCrossCompile=true`)
and reading it: the dump shows exactly the two files above, with every
signature typed in `kotlin/String`/`kotlin/Int`/`kotlin/Long` and the
module's own two declared types — nothing else.

**Scope reduction versus the Android precedent:** Android's
`CircuitBreakerProcessTerminationContentProvider` drives its two failures
through the full `CircuitBreakerExecutionGate`/`CircuitBreakerCoordinator`
pair. `openCircuitAndPersist` instead drives `AppleFileCircuitBreakerStateStore`
directly with hand-built `CircuitBreakerState` records equivalent to what the
coordinator would persist for the same transition. This keeps this module's
Kotlin/Swift-interop surface to the two primitive-typed methods above —
`CircuitBreakerCoordinator`'s own constructor requires a clock abstraction,
failure-threshold configuration, and contention-limit wiring that would
otherwise need to cross the same interop boundary for no proof-relevant
benefit. Wiring the full coordinator through this same app target is a
legitimate, separate follow-up.

### 2. `apple-process-termination-proof-app/` (real, launchable iOS Simulator app)

A hand-authored Xcode project (`ProcessTerminationProofApp.xcodeproj`), one
Swift file (`AppDelegate.swift`), and an `Info.plist` — no storyboard, no
`SceneDelegate` (the app deliberately omits `UIApplicationSceneManifest`, so
UIKit uses the legacy scene-less lifecycle; window setup happens directly in
`application(_:didFinishLaunchingWithOptions:)`). Bundle identifier
`io.dataloom.processterminationproof.app`, `productType =
com.apple.product-type.application` — a real app, not a framework, unlike
`apple-smoke`'s `DataLoomSwiftSmoke` target.

On every launch, it reads back any existing persisted state via
`AppleCircuitBreakerProcessTerminationProof.shared.readPersistedState`; only
if none exists yet does it call `openCircuitAndPersist`. This makes the app
safe to launch twice (once before the CI proof's `simctl terminate`, once
after, as a genuinely new OS process) without the second launch attempting a
conflicting compare-and-set against an already-open circuit. The directory it
persists to is `<app's Documents directory>/CircuitProof` — a location the CI
script resolves from *outside* the app via `xcrun simctl get_app_container
<device> <bundle-id> data`, so the app itself never needs to report anything
back; the host script's own independent file read is the actual proof
mechanism, not a claim the app makes about itself.

No shared `.xcscheme` file is checked in, matching `apple-smoke`'s own
existing precedent in this repository — `apple-validation.yml`'s existing
Swift smoke step already relies on Xcode's scheme auto-generation for a
single-target project with no checked-in scheme (`-scheme DataLoomSwiftSmoke`
against a project with no `xcshareddata/xcschemes/` directory), so this new
project follows the same, already-relied-upon pattern rather than
introducing a new one.

### 3. New CI job in `.github/workflows/apple-validation.yml`

A new, separate job, `apple-process-termination-proof`, deliberately kept
independent of the existing `apple-validate` job (own checkout/Java/Gradle
setup) so a failure in this brand-new, never-run-before job cannot fail the
already-proven XCFramework/Swift-smoke checks, and vice versa. It also
carries `continue-on-error: true` while unproven, so it cannot block merges
of unrelated work until a real green run has been observed and that flag is
deliberately removed.

The job:

1. `./gradlew :apple-process-termination-proof:assembleDataLoomProcessTerminationProofReleaseXCFramework`
2. Copies the resulting XCFramework into `apple-process-termination-proof-app/`
   (mirroring `apple-smoke`'s own copy-then-build pattern exactly).
3. `xcodebuild build -scheme ProcessTerminationProofApp -sdk iphonesimulator
   -derivedDataPath build CODE_SIGNING_ALLOWED=NO ...`
4. Dynamically resolves an available iOS runtime and an iPhone device type via
   `xcrun simctl list runtimes --json` / `xcrun simctl list devicetypes --json`
   (piped through `jq`) rather than hardcoding a specific simulator name that
   might not exist on a given Xcode version, creates a dedicated device, and
   boots it (`xcrun simctl boot` + `xcrun simctl bootstatus -b`).
5. Installs the built `.app`, launches it (`xcrun simctl launch`), parses the
   pid from `simctl launch`'s own `<bundle-id>: <pid>` stdout line, resolves
   the app's real container path via `xcrun simctl get_app_container ...
   data`, and polls (30s timeout, failing loudly rather than assuming
   success) for `Documents/CircuitProof/dataloom-circuit-state-v1.tsv` to
   appear.
6. Reads and records that file's content, then kills the app with `xcrun
   simctl terminate` — a genuine OS-level kill, not an in-process simulation —
   and polls `xcrun simctl spawn ... launchctl list` (30s timeout, again
   failing loudly rather than assuming) until no entry mentions the bundle id.
7. Relaunches the app, parses the second pid, and asserts it differs from the
   first — the same "genuinely new process, not a warm one" assertion
   `AndroidProcessTerminationCircuitBreakerInstrumentedTest` makes by
   comparing pids either side of `ActivityManager.killBackgroundProcesses`.
8. Re-reads the same state file and diffs it byte-for-byte against the
   pre-kill content.
9. Always cleans up the created Simulator device (`if: always()`).

## What was verified from this Windows session

- `./gradlew.bat :apple-process-termination-proof:compileKotlinIosArm64
  :apple-process-termination-proof:compileKotlinIosSimulatorArm64
  :apple-process-termination-proof:compileKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` — succeeded for all three targets.
  Production code type-checks and klib-compiles against
  `AppleFileCircuitBreakerStateStore`, `AppleDataLoomClock`, and the
  `dataloom-api` circuit-breaker contract types.
- `./gradlew.bat :apple-process-termination-proof:compileTestKotlinIosArm64
  :apple-process-termination-proof:compileTestKotlinIosSimulatorArm64
  :apple-process-termination-proof:compileTestKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true`, run individually and sequentially
  per this session's own Windows Gradle-concurrency discipline — all three
  succeeded. `AppleCircuitBreakerProcessTerminationProofTest` (pure
  single-process sanity coverage; it cannot itself prove OS-level kill
  survival — see its own KDoc) type-checks and klib-compiles for all three
  targets.
- `./gradlew.bat :apple-process-termination-proof:updateKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` then `./gradlew.bat checkKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` — the whole-build ABI check passed.
  `git status` confirmed only
  `apple-process-termination-proof/api/apple-process-termination-proof.klib.api`
  was added; no existing module's baseline changed. The generated dump was
  read directly and confirms the minimal, primitive-typed public surface
  described above.
- `./gradlew.bat :apple-process-termination-proof:assembleDataLoomProcessTerminationProofReleaseXCFramework
  -Pdataloom.appleKlibCrossCompile=true` — the task exists and runs; its
  constituent `linkReleaseFrameworkIos*`/`assembleRelease*FatFrameworkFor...`
  tasks report `SKIPPED`. This is the expected, already-precedented behavior
  for Apple binary linking attempted from a non-macOS host (Kotlin/Native can
  cross-compile klibs on any host but cannot link final Mach-O
  binaries/frameworks without a macOS toolchain) — `dataloom-apple`'s
  identically-shaped `assembleDataLoomReleaseXCFramework` task has the same
  property and is documented as macOS-only throughout this repository. This
  confirms the Gradle task name and wiring are correct; it does not confirm
  the XCFramework actually assembles.
- `./gradlew.bat :apple-process-termination-proof:tasks --all
  -Pdataloom.appleKlibCrossCompile=true` — confirmed
  `assembleDataLoomProcessTerminationProofReleaseXCFramework` is registered
  under exactly that name (Gradle's `assemble<Name><BuildType>XCFramework`
  convention, matching `dataloom-apple`'s own proven `assembleDataLoomReleaseXCFramework`
  task shape).
- The `.github/workflows/apple-validation.yml` YAML, the `.xcodeproj`'s
  `project.pbxproj`, and the `Info.plist` were reviewed by hand for
  structural/syntactic validity (no YAML/plist linter or `plutil` was
  available on this Windows host to validate them mechanically — see below).

## What was NOT, and could not be, verified from this Windows session

Everything that requires an actual macOS host, Xcode, or the iOS Simulator:

- **The Xcode project itself has never been opened or built by Xcode.**
  `project.pbxproj` was hand-authored by extending `apple-smoke`'s existing,
  CI-proven `DataLoomSwiftSmoke.xcodeproj` pattern (same object-graph shape:
  `PBXProject`/`PBXNativeTarget`/`PBXBuildFile`/`PBXFileReference`/
  `PBXGroup`/`XCBuildConfiguration`/`XCConfigurationList`), changing
  `productType` to `com.apple.product-type.application`, adding an
  `Info.plist` reference and a `PBXResourcesBuildPhase`, and switching Debug
  as the default configuration. Whether Xcode accepts this file without
  needing to be regenerated/repaired on first open is unverified.
- **The scene-less UIKit lifecycle** (no `UIApplicationSceneManifest` key, no
  `SceneDelegate`, `@UIApplicationMain` on a single `AppDelegate`) is a
  long-standing, still-supported UIKit pattern, but its behavior on the
  specific iOS Simulator runtime version a real `macos-15` GitHub Actions
  runner provides has not been observed.
- **Whether `AppleCircuitBreakerProcessTerminationProof.shared.openCircuitAndPersist(directoryPath:)`
  is exactly the Swift call signature Kotlin/Native generates.** Kotlin
  `object`-to-`shared`-singleton and suspend-free-method export are
  well-established Kotlin/Native conventions, and this module's own
  `.klib.api` dump confirms the underlying Kotlin signatures are exactly as
  designed — but the *Objective-C header generation and Swift import* step
  itself (`cinterop`/Swift Package Manager or Xcode's own header-import
  machinery) was never run, since that requires the macOS Kotlin/Native
  backend's actual framework/header emission, not just klib compilation.
- **The exact XCFramework output directory layout for a project-authored
  `XCFramework()` block.** The workflow assumes
  `apple-process-termination-proof/build/XCFrameworks/release/DataLoomProcessTerminationProof.xcframework`,
  matching `dataloom-apple`'s own proven output layout
  (`dataloom-apple/build/XCFrameworks/release/DataLoom.xcframework`) exactly
  — same Gradle plugin, same `XCFramework(name)` API, same `release` build
  type — but this was not directly observed for this new module since
  linking never actually ran on this host.
- **`xcodebuild build -sdk iphonesimulator -derivedDataPath build`'s exact
  output path**, assumed to be
  `build/Build/Products/Debug-iphonesimulator/ProcessTerminationProofApp.app`
  (the standard, well-documented Xcode derived-data layout for that
  configuration/SDK pair) — not directly observed.
- **`xcrun simctl launch`'s exact stdout format.** The script assumes the
  well-documented `<bundle-id>: <pid>` line and parses the trailing integer
  with `sed`. Never observed directly.
- **The exact `launchctl list` label format for a Simulator app process
  after `simctl terminate`.** This is the single largest format
  uncertainty in the whole script — there is no `simctl` subcommand that
  directly answers "is this bundle id's process still running," and the
  investigation this document is based on explicitly named this exact gap:
  "no host-level kill API is callable from in-process test code the way
  `ActivityManager.killBackgroundProcesses` is on Android." The script
  deliberately greps for the bundle id as a *substring* of the whole
  `launchctl list` output rather than asserting an exact label shape, to
  tolerate this uncertainty, but whether the bundle id appears in that
  output at all for a Simulator-hosted app (as opposed to only a
  device-hosted one, or only under a differently-named label) is unverified.
  **If this specific assumption is wrong, the job will time out at the
  "Polling until the OS confirms the process is actually gone" step** rather
  than silently reporting a false pass — the timeout path exits non-zero and
  dumps the raw `launchctl list` output for debugging.
- **Code signing behavior for a `CODE_SIGNING_ALLOWED=NO` app built for
  `iphonesimulator` and then `simctl install`ed.** `apple-smoke`'s existing,
  proven CI step only ever runs `xcodebuild build` for a framework target
  (never `simctl install`); this is the first time this repository attempts
  to actually install and run something on a Simulator. Whether the
  Simulator's own install validation accepts a completely unsigned `.app`
  bundle (widely reported to work in general iOS tooling documentation and
  community practice, but not independently confirmed against this exact
  Xcode/Simulator version pairing) is unverified.
- **Whether GitHub's `macos-15` runner image has `jq` pre-installed** (used
  to parse `simctl list runtimes/devicetypes --json`). GitHub's own
  hosted-runner image documentation lists `jq` as pre-installed on all
  `macos-*` images, but this was not independently re-confirmed against the
  exact current image revision.

## What remains open after this PR, even once CI infrastructure is proven

- **Retry-budget state** (`AndroidProcessTerminationRetryBudgetInstrumentedTest`'s
  Apple counterpart) is not attempted here. The same app/module/CI shape
  should extend mechanically once the circuit-breaker path is proven, the
  same way the Android retry-budget proof mechanically extended the
  Android circuit-breaker proof.
- **Cross-process probe contention** (`AndroidCircuitBreakerProbeContentionInstrumentedTest`'s
  Apple counterpart) remains genuinely blocked for the reason
  `process-termination-investigation.md` already names: iOS has no
  `android:process`-equivalent mechanism to host two genuinely independent
  processes inside one app bundle on demand, so this proof shape does not
  transfer even once single-process kill/relaunch is proven.
- **The full `CircuitBreakerExecutionGate`/`CircuitBreakerCoordinator` pair**
  is not exercised by this proof app (see the scope-reduction note under
  "What this builds" above) — only direct `AppleFileCircuitBreakerStateStore`
  writes are.
- **A real `BGTaskScheduler`-triggered background-task tick** (`#101`) is a
  separate, still-fully-open gap; `DataLoomBackgroundTaskHandler`
  (`dataloom-scheduler-bgtask`, round 30) exists and compiles against the
  real platform surface but has never been invoked by a genuine OS-driven
  background-task launch. The app-target/`simctl` scaffolding this PR adds
  is oriented toward process-kill/relaunch proofs, not toward triggering
  `BGTaskScheduler` (which additionally requires the app to be backgrounded
  and the Simulator/device's background-task debugging tools, a materially
  different `simctl`/Xcode interaction this PR does not attempt). Closing
  this gap is a named, separate follow-up.

## References

- [`docs/apple/process-termination-investigation.md`](process-termination-investigation.md) —
  the investigation this work acts on.
- [`docs/apple/background-task-handler.md`](background-task-handler.md) —
  round 30's `DataLoomBackgroundTaskHandler`, the still-uninvoked background-task
  half of `#101` referenced under "What remains open" above.
- `AndroidProcessTerminationCircuitBreakerInstrumentedTest`
  (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) —
  the Android proof this Apple proof mirrors in shape (pid-before/pid-after
  comparison, poll-with-timeout rather than assume, byte-for-byte persisted
  state comparison).
- `.github/workflows/apple-validation.yml` — the new `apple-process-termination-proof`
  job.
- `apple-process-termination-proof/` and `apple-process-termination-proof-app/` —
  the new module and app.
