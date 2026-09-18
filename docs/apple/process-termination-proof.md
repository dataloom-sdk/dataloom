# Apple Simulator process-termination/relaunch CI proof (`#94`)

## Status

**Circuit-breaker state: fully proven on real macOS CI as of 2026-09-13** —
see "Update: fully genuine green run" below.

**Retry-budget state: new CI infrastructure added 2026-09-15, mechanically
extending the circuit-breaker proof. Unverified until it runs on real macOS
CI.** See "Extension: durable retry-budget state (2026-09-15)" below for what
was built, exactly what could and could not be verified from a Windows
development host, and why this reuses a second, separate app project rather
than folding the write into `ProcessTerminationProofApp`'s existing launch.
`docs/status/market-readiness.md`'s `#94` row percentage is deliberately
**unchanged** by the retry-budget extension — see that row's own updated
"Still pending" text and the dated log entry for 2026-09-15.

**Conflict-log domains (`#95`): new CI infrastructure added 2026-09-18,
mechanically extending the same single-process kill/relaunch mechanism to
`DurableUnresolvedConflictLog` and `DurableResolvedConflictDecisionLog`.
Unverified until it runs on real macOS CI.** See "Extension: conflict-log
process-kill/relaunch proof (2026-09-18)" below. This is a genuinely
different proof shape from `apple-conflict-log-contention-proof` (round 33,
already proven on real macOS CI) — that job races two processes against each
other; this one kills and relaunches one. `docs/status/market-readiness.md`'s
`#95` row percentage is deliberately **unchanged** by this extension.

The rest of this document (through "Update: fully genuine green run") is
unchanged from round 31 and describes only the circuit-breaker proof.

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

## Update: first real macOS CI run (2026-09-12)

This job ran for real on a `macos-15` GitHub Actions runner for the first
time on this PR. Results, resolving several of the unknowns named above:

- **The hand-authored `.xcodeproj` built successfully on first open** —
  `xcodebuild build` reported `** BUILD SUCCEEDED **` with no scheme-related
  or project-repair errors, despite this project never having been opened by
  Xcode.app and carrying no checked-in `xcshareddata`/`xcuserdata` scheme
  file. The "never verified whether Xcode accepts this file without repair"
  concern above is resolved: it does.
- **`jq` is confirmed present** on the current `macos-15` runner image —
  both the runtime and device-type `simctl ... --json | jq` queries executed
  and returned real values.
- **The XCFramework output path assumption was exactly correct**:
  `apple-process-termination-proof/build/XCFrameworks/release/DataLoomProcessTerminationProof.xcframework`
  was the real path Gradle wrote to, matching `dataloom-apple`'s own proven
  layout as predicted.
- **A real, genuine bug was found and fixed**: `xcrun simctl create` failed
  with `Incompatible device` (`SimError` code 403). Root cause: the
  device-type selection picked a device type independently from the global
  `simctl list devicetypes --json` list, sorted lexicographically and taking
  `last` — which selected `iPhone 6s Plus` over every modern iPhone, since
  `'6'` sorts after `'1'` as a character (`"iPhone 17 Pro Max"` <
  `"iPhone 6s Plus"` lexicographically), and old device types like the 6s
  Plus are dropped from newer iOS runtimes' compatibility list entirely —
  exactly what `Incompatible device` reported. Fixed by restricting the
  candidate device types to the *selected runtime's own*
  `supportedDeviceTypes` array (from the same `simctl list runtimes --json`
  payload already fetched), guaranteeing whatever is chosen is actually
  creatable against that runtime.
- **Still unverified after this run** (the fix above was not yet re-run on
  CI at the time of this update): the app install/launch, kill/relaunch,
  `launchctl list` label format, and `simctl launch` pid-format assumptions
  — the job failed before reaching any of those steps. These remain exactly
  as uncertain as originally documented above until the device-selection fix
  produces a run that reaches them.

## Update: fully genuine green run (2026-09-13)

The device-type-selection fix above produced a complete, fully genuine
end-to-end pass on run `34730220519` — every remaining named unknown is now
resolved:

- App install/launch/kill/relaunch all succeeded for real.
- `simctl launch`'s `<bundle-id>: <pid>` format parsed correctly on both
  launches — real pids `13881` (before kill) and `14307` (after relaunch),
  genuinely distinct.
- The `launchctl list`-grep polling strategy for confirming a genuine kill
  worked as designed — no timeout, no false pass.
- The persisted circuit-breaker state file, read directly from the
  Simulator's app-container filesystem from outside the app process, was
  byte-identical before the kill and after the relaunch.
- Code signing (`CODE_SIGNING_ALLOWED=NO`) and `jq` presence were both
  non-issues, as this document's first-run update already found.

This closes every item this document's original "What was NOT, and could
not be, verified" section named. `continue-on-error: true` has been removed
from the job in `.github/workflows/apple-validation.yml` — this is now a
required check like every other job in `apple-validation.yml`. `#94`'s
market-readiness row is bumped accordingly (see that gate's own row for the
exact wording).

## Extension: durable retry-budget state (2026-09-15)

This closes the "Retry-budget state" item named below (round 31), the same
way `AndroidProcessTerminationRetryBudgetInstrumentedTest` mechanically
extended `AndroidProcessTerminationCircuitBreakerInstrumentedTest` on
Android. As with that Android precedent, this is a genuinely separate
durable structure, not a variant of circuit-breaker state:

- On Android, retry-budget fields (`retry_attempt_number`,
  `retry_window_started_at_ms`, `retry_last_evaluated_at_ms`,
  `retry_cumulative_delay_ms`) live on the `queue_entries` table written by
  `RoomQueueProvider`, independent of the `circuit_breaker_states` table
  `RoomCircuitBreakerStateStore` owns.
- On Apple, the equivalent fields are `io.dataloom.api.queue.QueueEntry.retryAttempt`
  and `QueueEntry.retryBudgetState` (`io.dataloom.api.retry.RetryAttempt.number`
  and `io.dataloom.api.retry.RetryBudgetState.windowStartedAt`/`lastEvaluatedAt`/`cumulativeDelay`),
  persisted by the real production `AppleFileQueueProvider`
  (`dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleFileQueueProvider.kt`)
  to `dataloom-queue-state-v1.tsv` — a file entirely independent of
  `AppleFileCircuitBreakerStateStore`'s own `dataloom-circuit-state-v1.tsv`.
  This was verified directly by reading `QueueEntry`'s declared properties
  and `AppleFileQueueProvider`'s `reschedule`/`acquire`/`defer`
  implementations, not assumed to carry over 1:1 from Android's column
  names.

### What this adds

1. **`AppleRetryBudgetProcessTerminationProof`** (new Kotlin `object` in the
   existing `apple-process-termination-proof` module, alongside the
   unchanged `AppleCircuitBreakerProcessTerminationProof`) and
   **`RetryBudgetProcessTerminationProofState`** (a new primitive-typed data
   holder alongside the unchanged `ProcessTerminationProofState`). Two
   methods:
   - `writeRetryBudgetAndPersist(directoryPath: String): RetryBudgetProcessTerminationProofState`
     drives the real production `enqueue -> acquire -> reschedule -> acquire
     -> defer` sequence through a new `AppleFileQueueProvider` — the same
     sequence Android's `RetryBudgetProcessTerminationContentProvider`
     drives through `RoomQueueProvider`, with no further scope reduction
     needed (unlike the circuit-breaker proof's own reduction versus
     `CircuitBreakerExecutionGate`/`CircuitBreakerCoordinator`, `QueueProvider`
     has no coordinator/execution-gate layer of its own to bypass).
   - `hasPersistedRetryBudgetState(directoryPath: String): Boolean` is a
     **non-mutating** existence check on the well-known
     `AppleFileQueueProvider.DEFAULT_FILE_NAME` snapshot file. This cannot
     reuse the circuit-breaker proof's own `readPersistedState`-as-idempotency-guard
     pattern: `AppleFileCircuitBreakerStateStore.load` is a pure read, but
     `AppleFileQueueProvider`'s only public read path is `acquire`, which
     atomically assigns a new lease as a side effect. Calling `acquire` from
     an idempotency guard on every app launch would itself mutate the
     persisted snapshot (attaching a lease), making the CI proof's own
     outside byte-for-byte file diff observe a spurious change across the
     kill/relaunch even though the underlying retry-budget fields never
     changed. The existence check avoids this entirely by never invoking the
     provider.
   - Confirmed via `updateKotlinAbi`/`checkKotlinAbi`
     (`-Pdataloom.appleKlibCrossCompile=true`) that the module's exported
     surface remains exactly the four primitive-typed declarations
     (`ProcessTerminationProofState`, `RetryBudgetProcessTerminationProofState`,
     `AppleCircuitBreakerProcessTerminationProof`,
     `AppleRetryBudgetProcessTerminationProof`) with every signature typed in
     `kotlin/String`/`kotlin/Int`/`kotlin/Long`/`kotlin/Boolean` plus the
     module's own declared types — no `dataloom-api`/`dataloom-runtime` type
     leaks into the generated Objective-C header. The whole-build
     `checkKotlinAbi` passed with only this module's own baseline file
     changing.

2. **`apple-process-termination-proof-retry-budget-app/`** — a second,
   separate hand-authored Xcode project
   (`RetryBudgetProcessTerminationProofApp.xcodeproj`), rather than a second
   write inside `ProcessTerminationProofApp`'s existing single launch.
   Reasoning: the circuit-breaker and retry-budget proofs persist to
   genuinely distinct on-disk snapshot files owned by two independent
   production stores, so nothing is shared by combining them into one
   app/process, and doing so would only add shared-failure risk to the
   already-proven circuit-breaker path for no proof-relevant benefit — if
   something about the new retry-budget write were wrong, it must not be
   able to take the proven circuit-breaker app down with it. Bundle id
   `io.dataloom.processterminationproof.retrybudget.app`; persists to
   `<Documents>/RetryBudgetProof/dataloom-queue-state-v1.tsv` inside its own
   app container (a distinct container from `ProcessTerminationProofApp`'s,
   since it is a distinct bundle id). The project file was produced by
   copying `ProcessTerminationProofApp.xcodeproj`'s own proven object graph
   verbatim and substituting only object-id prefixes, the target/product
   name, and the bundle identifier — confirmed structurally byte-identical
   to the original after reversing those substitutions (`sed`-normalize and
   `diff`), the strongest confidence available from a Windows host that a
   hand-edited `.pbxproj` did not introduce a structural error, short of
   Xcode itself opening it. `AppDelegate.swift` mirrors
   `ProcessTerminationProofApp`'s own idempotency-guard shape exactly,
   substituting `hasPersistedRetryBudgetState`/`writeRetryBudgetAndPersist`
   for `readPersistedState`/`openCircuitAndPersist`.

3. **New CI job `apple-retry-budget-process-termination-proof`** in
   `.github/workflows/apple-validation.yml`, structurally identical to
   `apple-process-termination-proof`'s own steps (assemble XCFramework, copy
   it into the app project, `xcodebuild build`, create/boot a dedicated
   Simulator device using the same runtime-scoped device-type-selection fix
   round 31 already established, install/launch/kill/poll/relaunch, diff the
   persisted state file read directly from the Simulator's app-container
   filesystem from outside the app process) but targeting the new app,
   bundle id, device name, and state-file path
   (`Documents/RetryBudgetProof/dataloom-queue-state-v1.tsv`). Deliberately a
   separate job (own checkout/Java/Gradle setup, own Simulator device) so a
   failure here cannot fail either the existing `apple-validate` job or the
   existing `apple-process-termination-proof` job, and vice versa — neither
   of those two already-proven jobs, nor the circuit-breaker app/module code
   they exercise, is modified by this addition.

   This job carried `continue-on-error: true` while unproven, the same
   posture `apple-process-termination-proof` itself held before its own
   first real run (round 31). **Update (round 33, 2026-09-17): three
   genuine green runs observed** across separate PR/main pushes — real
   distinct pids before/after a genuine `xcrun simctl terminate` + relaunch
   each time, retry-budget queue state confirmed byte-identical, including
   one run on `main` itself. `continue-on-error` has been removed; this is
   now a required check like every other job in this workflow. `#94`'s
   dashboard percentage is deliberately left unchanged — this closes the
   "prove it once for retry-budget state" gap, not the separate, still-open
   retry-budget *contention* proof (the same extension `#95`'s two
   conflict-log domains also still need, per
   `docs/apple/process-contention-proof.md`'s own named follow-ups).

### What was verified from this Windows session

- `./gradlew.bat :apple-process-termination-proof:compileKotlinIosArm64
  :apple-process-termination-proof:compileKotlinIosSimulatorArm64
  :apple-process-termination-proof:compileKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` — succeeded for all three targets
  with `AppleRetryBudgetProcessTerminationProof` added. Production code
  type-checks and klib-compiles against `AppleFileQueueProvider` and the
  `dataloom-api` queue/retry contract types.
- The same three targets' `compileTestKotlin*` tasks, run individually and
  sequentially per this repository's Windows Gradle-concurrency discipline —
  all three succeeded. `AppleRetryBudgetProcessTerminationProofTest` (pure
  single-process sanity coverage, same limitation as the circuit-breaker
  proof's own test suite — it cannot itself prove OS-level kill survival)
  type-checks and klib-compiles for all three targets.
- `./gradlew.bat :apple-process-termination-proof:updateKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` then `./gradlew.bat checkKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` — the whole-build ABI check passed.
  `git status` confirmed only this module's own
  `apple-process-termination-proof/api/apple-process-termination-proof.klib.api`
  changed; no other module's baseline changed.
- `./gradlew.bat :apple-process-termination-proof:assembleDataLoomProcessTerminationProofReleaseXCFramework
  -Pdataloom.appleKlibCrossCompile=true` — ran with the same expected
  `SKIPPED` linking tasks as round 31's own run of this task (Kotlin/Native
  cross-compiles klibs on any host but cannot link final Mach-O
  binaries/frameworks without a macOS toolchain). Confirms the task exists
  and is wired correctly; does not confirm the XCFramework's two exported
  proof objects both actually link.
- The new `RetryBudgetProcessTerminationProofApp.xcodeproj`'s `project.pbxproj`
  and `Info.plist` were diffed byte-for-byte (after reversing the known
  name/id substitutions) against `ProcessTerminationProofApp`'s own,
  already-CI-proven originals, and confirmed structurally identical. The new
  CI job's YAML was reviewed by hand for indentation/structure consistency
  with its sibling jobs (no YAML linter was available on this Windows host
  to validate it mechanically).

### What was NOT, and could not be, verified from this Windows session

Everything that requires an actual macOS host, Xcode, or the iOS Simulator —
the same category of gap round 31 named for the circuit-breaker proof,
applying identically here since this reuses the same infrastructure shape:

- **Whether Xcode accepts `RetryBudgetProcessTerminationProofApp.xcodeproj`
  without repair on first open.** Structurally byte-identical to
  `ProcessTerminationProofApp.xcodeproj` (already proven to build on first
  open in round 31's own first real CI run), but never itself opened or
  built by Xcode.
- **Whether `AppleRetryBudgetProcessTerminationProof.shared.writeRetryBudgetAndPersist(directoryPath:)`
  and `.hasPersistedRetryBudgetState(directoryPath:)` are exactly the Swift
  call signatures Kotlin/Native generates.** The module's own `.klib.api`
  dump confirms the underlying Kotlin signatures; the actual Objective-C
  header generation and Swift import step was never run (same gap round 31
  named for the circuit-breaker proof's own methods, resolved by that
  round's first real CI run).
- **Whether the retry-budget proof app's own production write sequence
  (`enqueue -> acquire -> reschedule -> acquire -> defer`) actually succeeds
  end to end when run for real inside a launched Simulator process**, as
  opposed to only compiling and klib-verifying. `AppleRetryBudgetProcessTerminationProofTest`
  exercises this same sequence within a single Kotlin/Native test process
  (see its own KDoc), but that test binary has not itself been run on this
  Windows host.
- **Whether `dataloom-queue-state-v1.tsv`'s on-disk format is stable and
  diffable the same way `dataloom-circuit-state-v1.tsv` proved to be** —
  both are written by structurally similar atomic-rename TSV codecs
  (`AppleQueueStateFileCodec` vs `AppleCircuitAdministrationStateFileCodec`'s
  sibling for circuit-breaker state), but the queue snapshot's format was
  not independently re-confirmed to be free of any non-deterministic
  ordering or timestamp field that could cause a spurious byte-level diff
  across the kill/relaunch even when the retry-budget fields themselves are
  unchanged. If this assumption is wrong, the new job's final diff step will
  fail loudly (exit non-zero with a printed diff) rather than silently
  report a false pass.
- **Code signing, `jq` presence, `launchctl list` label format, and
  `simctl launch` pid-format** — all already resolved once for the
  circuit-breaker proof's own bundle id in round 31's first real run, but
  not independently re-confirmed for this job's distinct bundle id
  (`io.dataloom.processterminationproof.retrybudget.app`) and device name.
  These are expected, but not guaranteed, to behave identically.

## Extension: conflict-log process-kill/relaunch proof (2026-09-18)

This closes the "Apple process-kill/relaunch evidence for either conflict-log
domain" item `docs/status/market-readiness.md`'s `#95` row has named as still
open since round 33 — a *different* proof shape from
`apple-conflict-log-contention-proof` (round 33, already proven on real macOS
CI), which races two processes against each other. This proof kills and
relaunches one, the same mechanism `apple-process-termination-proof`/
`apple-retry-budget-process-termination-proof` already proved for circuit
breaker and retry-budget state, extended here to `DurableUnresolvedConflictLog`
and `DurableResolvedConflictDecisionLog` — the same two real production types
`apple-process-contention-proof`'s own
`AppleUnresolvedConflictLogContentionProof`/
`AppleResolvedConflictDecisionLogContentionProof` already drive against
`AppleFileDurableStateStore` for the unrelated contention proof shape (a
different module, a different acceptance item — read to confirm the real API
surface, never modified).

### What this adds

1. **`AppleUnresolvedConflictLogProcessTerminationProof`** and
   **`AppleResolvedConflictDecisionLogProcessTerminationProof`** (two new
   Kotlin `object`s in the existing `apple-process-termination-proof` module,
   alongside the unchanged `AppleCircuitBreakerProcessTerminationProof`/
   `AppleRetryBudgetProcessTerminationProof`), and
   **`UnresolvedConflictLogProcessTerminationProofState`**/
   **`ResolvedConflictDecisionLogProcessTerminationProofState`** (two new
   primitive-typed data holders). Each proof object has the same two-method
   shape as `AppleCircuitBreakerProcessTerminationProof`:
   - `recordAndPersist(directoryPath: String): <State>` drives one real
     production `DurableUnresolvedConflictLog.record`/
     `DurableResolvedConflictDecisionLog.record` call against a fresh
     `AppleFileDurableStateStore` rooted at `directoryPath`, then reads the
     persisted record back. Fails loudly (via Kotlin `error`) on anything
     other than the `Recorded` outcome — a caller must guard repeat
     invocations with `readPersistedState` first, same as
     `AppleCircuitBreakerProcessTerminationProof.openCircuitAndPersist`.
   - `readPersistedState(directoryPath: String): <State>?` reads back via
     `AppleFileDurableStateStore.load`/`DurableStateStore.load` — a confirmed
     **non-mutating** operation (verified directly by reading
     `AppleFileDurableStateStore.load`'s implementation: it only reads and
     decodes the snapshot, never writes). This is the same idempotency-guard
     shape `AppleCircuitBreakerProcessTerminationProof.readPersistedState`
     already established, and notably *not* the file-existence workaround
     `AppleRetryBudgetProcessTerminationProof.hasPersistedRetryBudgetState`
     needed — `AppleFileQueueProvider` has no non-mutating read path, but
     `AppleFileDurableStateStore` does, so no workaround is needed here.
   - Confirmed via `updateKotlinAbi`/`checkKotlinAbi`
     (`-Pdataloom.appleKlibCrossCompile=true`) that the module's exported
     surface grew by exactly these four declarations, every new signature
     typed in `kotlin/String`/`kotlin/Long` plus the module's own declared
     types — no `dataloom-api`/`dataloom-runtime` type leaks into the
     generated Objective-C header. The whole-build `checkKotlinAbi` passed
     with only this module's own baseline file changing.

2. **`apple-process-termination-proof-conflict-log-app/`** — a new,
   hand-authored Xcode project (`ConflictLogProcessTerminationProofApp.xcodeproj`)
   with a **single** app target, reused unmodified across **both**
   conflict-log domains via a `DATALOOM_DOMAIN` launch-time environment
   variable (`"UNRESOLVED_CONFLICT"` or `"RESOLVED_DECISION"`) — mirroring
   `apple-process-contention-proof-app`'s own reasoning for reusing
   `ProcessContentionProofAppC`/`AppD` unmodified across the same two domains,
   adapted to this proof's own single-process shape (one target launched
   twice per domain — pre-kill and post-relaunch — rather than two targets
   launched once each to race). This is a deliberate departure from both
   prior precedents in this document: round 31's retry-budget extension added
   a *second, separate* Xcode project per domain; round 33's conflict-log
   *contention* extension added new *targets* to an existing multi-target
   project. Neither fits here as cleanly as a single shared target switched
   by environment variable, since there is no racing/target-per-role need and
   only one process is ever launched at a time. Bundle id
   `io.dataloom.processterminationproof.conflictlog.app`; persists to
   `<Documents>/ConflictLogProof/<state-file-name>` inside its own app
   container, where `<state-file-name>` is
   `AppleUnresolvedConflictLogProcessTerminationProof.STATE_FILE_NAME` or
   `AppleResolvedConflictDecisionLogProcessTerminationProof.STATE_FILE_NAME`
   depending on the launch's `DATALOOM_DOMAIN`. The project file was produced
   the same way round 31's retry-budget project was: copying
   `RetryBudgetProcessTerminationProofApp.xcodeproj`'s own proven object graph
   verbatim and substituting only object-id prefixes, the target/product
   name, and the bundle identifier — confirmed structurally byte-identical to
   that original after reversing those substitutions (`sed`-normalize and
   `diff`), plus a balanced-braces/zero-duplicate-object-id check, the
   strongest confidence available from a Windows host short of Xcode itself
   opening it. `AppDelegate.swift` reads `DATALOOM_DOMAIN` and switches
   between the two new proof objects' `recordAndPersist`/`readPersistedState`
   idempotency-guard calls; it is otherwise a synchronous, single-process
   write on launch, unlike `apple-process-contention-proof-app`'s own
   `AppDelegate.swift` (no go-signal wait, no background dispatch queue, no
   result file).

3. **New CI job `apple-conflict-log-process-termination-proof`** in
   `.github/workflows/apple-validation.yml` — a two-entry matrix (one leg per
   domain), mirroring `apple-conflict-log-contention-proof`'s own matrix
   rationale: both domains share the identical
   `AppleFileDurableStateStore` persistence path and the identical
   install/launch/kill/poll/relaunch/diff steps
   `apple-retry-budget-process-termination-proof` already established,
   differing only in `DATALOOM_DOMAIN` (forwarded to the launched process via
   `simctl launch`'s own `SIMCTL_CHILD_*` environment-forwarding convention,
   set identically on both the pre-kill and post-relaunch launches) and the
   expected state-file name. Each matrix leg builds/installs the one shared
   `ConflictLogProcessTerminationProofApp` target, creates its own dedicated
   Simulator device using the same runtime-scoped device-type-selection fix
   round 31 established, and diffs the persisted state file byte-for-byte
   before the kill and after the relaunch — read directly from the
   Simulator's app-container filesystem from outside the app process, the
   same verification shape as every other job in this document (not the
   "grep for a substring" fallback `apple-conflict-log-contention-proof`
   needed for its own two-writer race, since this proof only ever performs
   one insert against one scope key, making the resulting snapshot
   deterministic and safely diffable byte-for-byte).

   Deliberately a separate job (own checkout/Java/Gradle setup, own Simulator
   device per matrix leg) so a failure here cannot fail any of the five
   already-existing jobs in this workflow, and vice versa — none of them is
   modified by this addition. Carries `continue-on-error: true` while
   unproven, the same posture every other never-run-before job in this
   workflow held before its own first real green run.

### What was verified from this Windows session

- `./gradlew.bat :apple-process-termination-proof:compileKotlinIosArm64
  :apple-process-termination-proof:compileKotlinIosSimulatorArm64
  :apple-process-termination-proof:compileKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` — succeeded for all three targets
  with both new proof objects added. Production code type-checks and
  klib-compiles against `DurableUnresolvedConflictLog`/
  `DurableResolvedConflictDecisionLog` and `AppleFileDurableStateStore`.
- The same three targets' `compileTestKotlin*` tasks, run individually and
  sequentially per this repository's Windows Gradle-concurrency discipline —
  all three succeeded for both new iosTest suites (pure single-process
  sanity coverage; cannot itself prove OS-level kill survival).
- `./gradlew.bat :apple-process-termination-proof:updateKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` then
  `./gradlew.bat :apple-process-termination-proof:checkKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` then the whole-build
  `./gradlew.bat checkKotlinAbi -Pdataloom.appleKlibCrossCompile=true` — all
  passed. `git status` confirmed only this module's own
  `apple-process-termination-proof/api/apple-process-termination-proof.klib.api`
  changed, and the diff is purely additive (two new classes, two new
  objects; nothing existing changed).
- `./gradlew.bat :apple-process-termination-proof:assembleDataLoomProcessTerminationProofReleaseXCFramework
  -Pdataloom.appleKlibCrossCompile=true` — ran with the same expected
  `SKIPPED` linking tasks as every prior round's run of this task
  (Kotlin/Native cross-compiles klibs on any host but cannot link final
  Mach-O binaries/frameworks without a macOS toolchain). Confirms the task
  graph is wired correctly; does not confirm the XCFramework's four exported
  proof objects all actually link.
- The new `ConflictLogProcessTerminationProofApp.xcodeproj`'s `project.pbxproj`
  was diffed byte-for-byte (after reversing the known name/id substitutions)
  against `RetryBudgetProcessTerminationProofApp.xcodeproj`'s own,
  already-CI-proven original, and confirmed structurally identical; a
  balanced-braces check and a zero-duplicate-object-id check were also run
  directly against the new file. The new CI job's shell blocks were each
  extracted and checked with `bash -n` (all passed); the job's YAML
  indentation was reviewed by hand against its sibling
  `apple-conflict-log-contention-proof` job for structural consistency (no
  YAML linter was available on this Windows host to validate it
  mechanically, the same gap every prior round in this document has named).

### What was NOT, and could not be, verified from this Windows session

Everything that requires an actual macOS host, Xcode, or the iOS Simulator —
the same category of gap every prior extension in this document has named,
applying identically here:

- **Whether Xcode accepts `ConflictLogProcessTerminationProofApp.xcodeproj`
  without repair on first open.** Structurally byte-identical to
  `RetryBudgetProcessTerminationProofApp.xcodeproj` (already proven to build
  on first open in round 33), but never itself opened or built by Xcode.
- **Whether the generated Swift call signatures
  (`AppleUnresolvedConflictLogProcessTerminationProof.shared.recordAndPersist(directoryPath:)`,
  etc.) are exactly what `AppDelegate.swift` calls.** The module's own
  `.klib.api` dump confirms the underlying Kotlin signatures; the actual
  Objective-C header generation and Swift import step was never run.
- **Whether both real production write sequences
  (`DurableUnresolvedConflictLog.record`/`DurableResolvedConflictDecisionLog.record`
  against a real `AppleFileDurableStateStore`) actually succeed end to end
  when run for real inside a launched Simulator process**, as opposed to only
  compiling and klib-verifying. The new iosTest suites exercise this same
  sequence within a single Kotlin/Native test process, but that test binary
  has not itself been run on this Windows host.
- **Whether reading the same environment variable (`DATALOOM_DOMAIN`) on both
  the pre-kill and post-relaunch `simctl launch` calls behaves as expected**
  — the CI script re-exports `SIMCTL_CHILD_DATALOOM_DOMAIN` once and relies
  on it staying exported across both `simctl launch` invocations in the same
  shell step; this convention is already used successfully by
  `apple-conflict-log-contention-proof`'s own script for a single launch per
  process, but this job is the first to rely on it surviving across two
  separate `simctl launch` calls to the *same* bundle id in the same script.
  If this assumption is wrong, the second launch's `AppDelegate.swift` would
  `fatalError` with a clear message rather than silently misbehave.
- **Code signing, `jq` presence, `launchctl list` label format, and
  `simctl launch` pid-format** — all already resolved once for sibling jobs'
  own bundle ids, but not independently re-confirmed for this job's distinct
  bundle id (`io.dataloom.processterminationproof.conflictlog.app`) and
  device names.
- **Whether `AppleFileDurableStateStore`'s on-disk snapshot format stays
  free of any non-deterministic ordering or timestamp field** that could
  cause a spurious byte-level diff across the kill/relaunch even when the
  conflict-log record itself is unchanged — the format was read directly
  (see `AppleFileDurableStateStore`'s own class doc: a sorted-by-key,
  hex-encoded TSV, deterministic for a single inserted key), but never
  independently re-confirmed by an actual run. If this assumption is wrong,
  the new job's final diff step will fail loudly (exit non-zero with a
  printed diff) rather than silently report a false pass.

## What remains open after this PR, even once CI infrastructure is proven

- **Cross-process probe contention** (`AndroidCircuitBreakerProbeContentionInstrumentedTest`'s
  Apple counterpart) was, at the time this document was originally written,
  believed genuinely blocked for the reason
  `process-termination-investigation.md` names: iOS has no
  `android:process`-equivalent mechanism to host two genuinely independent
  processes inside one app bundle on demand. **Superseded 2026-09-15:** see
  [`docs/apple/cross-process-contention-investigation.md`](cross-process-contention-investigation.md)
  and [`docs/apple/process-contention-proof.md`](process-contention-proof.md)
  -- a structurally different mechanism (the iOS Simulator's own lack of
  app-container sandboxing, not an `android:process` equivalent) was found
  and acted on, and new, CI-unverified `apple-process-contention-proof`
  infrastructure now exists for exactly this proof. Not yet confirmed on
  real macOS CI as of this update.
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
- **Apple process-kill/relaunch evidence for retry-budget state's own
  cross-process *contention*** (the retry-budget analog of
  `apple-conflict-log-contention-proof`) remains a distinct, still fully open
  follow-up — this PR closes "conflict-log process-kill/relaunch evidence"
  (2026-09-18) and round 33 already closed "conflict-log contention evidence"
  (2026-09-17), but retry-budget's own contention proof was deliberately not
  attempted in either round and remains unaddressed.

## References

- [`docs/apple/process-termination-investigation.md`](process-termination-investigation.md) —
  the investigation this work acts on.
- [`docs/apple/background-task-handler.md`](background-task-handler.md) —
  round 30's `DataLoomBackgroundTaskHandler`, the still-uninvoked background-task
  half of `#101` referenced under "What remains open" above.
- `AndroidProcessTerminationCircuitBreakerInstrumentedTest` and
  `AndroidProcessTerminationRetryBudgetInstrumentedTest`
  (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) —
  the Android proofs this Apple proof (both the circuit-breaker and
  retry-budget halves) mirror in shape (pid-before/pid-after comparison,
  poll-with-timeout rather than assume, byte-for-byte persisted state
  comparison).
- `AndroidProcessTerminationConflictLogInstrumentedTest` and
  `AndroidProcessTerminationResolvedConflictDecisionLogInstrumentedTest`
  (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) —
  the Android kill/relaunch proofs for `#95`'s two conflict-log domains this
  Apple extension mirrors in shape.
- [`docs/apple/process-contention-proof.md`](process-contention-proof.md) —
  round 33's `apple-conflict-log-contention-proof`, the genuinely different
  *contention* (two-process-racing) proof shape for the same two `#95`
  conflict-log domains this document's own extension must not be confused
  with.
- `.github/workflows/apple-validation.yml` — the `apple-process-termination-proof`
  (circuit-breaker), `apple-retry-budget-process-termination-proof`
  (retry-budget), and `apple-conflict-log-process-termination-proof`
  (unresolved-conflict/resolved-conflict-decision, matrixed) jobs.
- `apple-process-termination-proof/` — the shared Kotlin/Native module
  (`AppleCircuitBreakerProcessTerminationProof`/`ProcessTerminationProofState`,
  `AppleRetryBudgetProcessTerminationProof`/`RetryBudgetProcessTerminationProofState`,
  `AppleUnresolvedConflictLogProcessTerminationProof`/`UnresolvedConflictLogProcessTerminationProofState`,
  and `AppleResolvedConflictDecisionLogProcessTerminationProof`/`ResolvedConflictDecisionLogProcessTerminationProofState`).
- `apple-process-termination-proof-app/` — the circuit-breaker proof's app.
- `apple-process-termination-proof-retry-budget-app/` — the retry-budget
  proof's separate app.
- `apple-process-termination-proof-conflict-log-app/` — the conflict-log
  proof's separate app, its single target reused across both domains.
