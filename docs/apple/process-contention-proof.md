# Apple Simulator cross-process probe-contention CI proof (`#94`/`#95`)

## Status

**New CI infrastructure added 2026-09-15 (round 32). Unverified until it
runs on real macOS CI.** This document describes what was built, exactly
what could and could not be verified from a Windows development host, and
the specific format/label uncertainties left for a real macOS CI run to
resolve -- the same disclosure shape
[`docs/apple/process-termination-proof.md`](process-termination-proof.md)
used for round 31's single-process kill/relaunch proof before its own first
real run. `docs/status/market-readiness.md`'s `#94` row is bumped with a new
dated "Recently shipped" entry describing this work but **percentage
unchanged** until a real green CI run is observed, matching that same
precedent exactly. `#95`'s row is left with sharper "still pending" text
(see that row) rather than a percentage bump, since this proof exercises the
circuit-breaker domain specifically, not either of `#95`'s own two
conflict-log domains.

This document acts on
[`docs/apple/cross-process-contention-investigation.md`](cross-process-contention-investigation.md)'s
conclusion: App Groups and app-extension-based approaches are both genuine,
structural dead ends for this repository's CI without a paid Apple
Developer Program account, but the iOS Simulator's own well-documented lack
of app-container sandboxing lets two genuinely independent, independently
bundle-identified Simulator apps race on a shared host-filesystem path with
no entitlement and no paid account at all. Read that document first for the
full reasoning; this document covers only the CI shape built on top of it.

## What this builds

Three new pieces, all new, none reusing or modifying
`apple-process-termination-proof`'s own module/app/CI job (kept fully
independent so a failure in this new, never-run-before job cannot affect
that already-proven one, or `dataloom-apple`'s production XCFramework):

### 1. `apple-process-contention-proof` (Kotlin/Native module)

A narrow module, gated identically to `dataloom-apple`/
`apple-process-termination-proof`/etc. in `settings.gradle.kts` (macOS host,
or `-Pdataloom.appleKlibCrossCompile=true`). It declares its own
`XCFramework("DataLoomProcessContentionProof")` across
`iosArm64`/`iosSimulatorArm64`/`iosX64`, entirely separate from
`dataloom-apple`'s and `apple-process-termination-proof`'s own
XCFrameworks.

Its public API is exactly two files:

- `ProcessContentionProofResult` -- a plain data holder of `String`/`Long`
  fields (`outcome`, `rejectionReason`, `probeGeneration`), mirroring
  `apple-process-termination-proof`'s own `ProcessTerminationProofState`
  precedent (a plain class with manual `equals`/`hashCode`/`toString`, not a
  `data class`).
- `AppleCircuitBreakerProbeContentionProof` -- a Kotlin `object` (exported to
  Objective-C/Swift as a class with a `shared` singleton accessor) with two
  methods: `openCircuitAndSignalReady(directoryPath:readyMarkerPath:)` and
  `waitForGoSignalThenAttemptProbe(directoryPath:goSignalPath:maxPollAttempts:)`.

Unlike `AppleCircuitBreakerProcessTerminationProof` (`#94`'s single-process
proof, which drives `AppleFileCircuitBreakerStateStore` directly with
hand-built state records to minimize its Swift-interop surface), this module
drives the real `CircuitBreakerCoordinator`/`CircuitBreakerExecutionGate`
pair -- matching `CircuitBreakerProbeContentionContentProviderBase`'s own
choice for the Android precedent it mirrors, since this proof's entire point
is exercising the coordinator's own contention-handling logic
(`CircuitBreakerRejectionReason.PROBE_IN_FLIGHT`, the compare-and-set retry
loop), not just the lower-level store.

Confirmed directly, not assumed, from this Windows session:

- `./gradlew.bat :apple-process-contention-proof:compileKotlinIosArm64
  :apple-process-contention-proof:compileKotlinIosSimulatorArm64
  :apple-process-contention-proof:compileKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` -- succeeded for all three targets.
- `./gradlew.bat :apple-process-contention-proof:compileTestKotlinIosArm64`
  /`...IosSimulatorArm64`/`...IosX64` (run individually and sequentially per
  this session's own Windows Gradle-concurrency discipline) -- all three
  succeeded. The new `AppleCircuitBreakerProbeContentionProofTest` (pure
  single-process sanity coverage -- it opens the circuit, then races itself
  sequentially with the "go" signal already present, and separately confirms
  the poll loop times out rather than hanging forever if the signal never
  appears; it cannot itself prove genuine cross-process contention) compiles
  for all three targets.
- `./gradlew.bat :apple-process-contention-proof:updateKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` then `./gradlew.bat checkKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` -- the whole-build ABI check passed.
  `git status` confirmed only
  `apple-process-contention-proof/api/apple-process-contention-proof.klib.api`
  was added; no existing module's baseline changed. The generated dump was
  read directly and confirms the minimal, primitive-typed public surface
  described above -- exactly the two methods and the one result class, with
  every parameter/return type `kotlin/String`, `kotlin/Long`, or
  `kotlin/Int`.

### 2. `apple-process-contention-proof-app/` (two real, launchable iOS Simulator apps)

One hand-authored Xcode project
(`ProcessContentionProofApp.xcodeproj`), extended from
`apple-process-termination-proof-app`'s own proven single-target shape to
**two** `PBXNativeTarget`s -- `ProcessContentionProofAppA` and
`ProcessContentionProofAppB` -- sharing one Swift file
(`AppDelegate.swift`) and one `Info.plist`, differing only in their own
`PRODUCT_BUNDLE_IDENTIFIER` build setting
(`io.dataloom.processcontentionproof.appa` / `...appb`). There is no
source-level "A" vs "B" branch: `AppDelegate.swift` reads a `DATALOOM_ROLE`
environment variable at runtime (set per-launch by the CI script via
`simctl launch`'s own documented `SIMCTL_CHILD_*` environment-forwarding
convention) rather than baking the role into either target's own code, so
both targets build from byte-identical source.

On launch, each app reads four environment variables
(`DATALOOM_SHARED_DIR`, `DATALOOM_READY_MARKER`, `DATALOOM_GO_SIGNAL`,
`DATALOOM_RESULT_FILE`) and, on a background queue (never the main thread,
so `UIWindow` setup is never blocked by the up-to-60-second poll):

- If `DATALOOM_ROLE == "A"`: calls
  `AppleCircuitBreakerProbeContentionProof.shared.openCircuitAndSignalReady`,
  which drives two real injected failures through the real
  coordinator/gate pair, opening the circuit, then touches the ready marker.
- Otherwise (role `"B"`, or an absent/unexpected value -- deliberately the
  narrower, non-circuit-touching fallback): touches its own ready marker
  directly via `FileManager`.
- Both then call
  `AppleCircuitBreakerProbeContentionProof.shared.waitForGoSignalThenAttemptProbe`,
  which busy-polls for the shared "go" marker file, then calls the real
  `CircuitBreakerCoordinator.acquire` and writes its own outcome
  (`outcome`/`rejectionReason`/`probeGeneration`, tab-separated) to its own
  result file.

The host CI script reads each app's result file directly from the plain
shared host directory -- the same "host reads a file the app wrote, not a
claim the app makes via IPC" pattern `apple-process-termination-proof-app`
already established, except this time the shared directory is a plain
`$RUNNER_TEMP` path rather than a per-app Simulator container resolved via
`simctl get_app_container`, since (per the investigation document) neither
app needs to write inside its own sandboxed container for this mechanism to
work at all.

No shared `.xcscheme` file is checked in, matching both
`apple-smoke`'s and `apple-process-termination-proof-app`'s own existing,
CI-proven precedent for a project with no checked-in scheme -- Xcode's own
scheme auto-generation is relied on for each of the two targets by name
(`-scheme ProcessContentionProofAppA` / `...AppB`).

### 3. New CI job in `.github/workflows/apple-validation.yml`

A new, separate job, `apple-process-contention-proof`, independent of both
`apple-validate` and `apple-process-termination-proof`. It carries
`continue-on-error: true` -- this is new, never-run-before infrastructure,
and that flag must not be removed until a real green run is observed, the
same rule `apple-process-termination-proof` itself followed until its own
"fully genuine green run" update.

The job, in order:

1. `./gradlew :apple-process-contention-proof:assembleDataLoomProcessContentionProofReleaseXCFramework`.
2. Copies the resulting XCFramework into
   `apple-process-contention-proof-app/`.
3. Builds both targets (`ProcessContentionProofAppA`/`...AppB`) with
   `xcodebuild build ... -sdk iphonesimulator ... CODE_SIGNING_ALLOWED=NO`.
4. Creates and boots a dedicated Simulator device, reusing
   `apple-process-termination-proof`'s own runtime/device-type selection
   logic verbatim (including its own fix, from that job's first real run,
   restricting device-type candidates to the selected runtime's own
   `supportedDeviceTypes` rather than a naive global pick).
5. Installs both apps, launches app A with `DATALOOM_ROLE=A` plus the shared
   directory/marker-file paths (via `SIMCTL_CHILD_*` environment variables),
   polls for its ready marker (confirming the circuit-open step finished),
   sleeps two real wall-clock seconds past the 400ms `openDuration`, then
   launches app B with `DATALOOM_ROLE=B` pointed at the same shared
   directory and go-signal path, and polls for its ready marker too --
   asserting both launches produced genuinely distinct pids along the way.
6. Once both processes are confirmed alive and polling, touches the shared
   "go" file, releasing both.
7. Polls for both apps' result files, parses each
   (`outcome`/`rejectionReason`/`probeGeneration`, tab-separated), and
   asserts: exactly one `ALLOWED` and exactly one `REJECTED`; the rejected
   process's reason is exactly `PROBE_IN_FLIGHT`; the allowed process's
   granted generation is a positive integer.
8. As bonus corroboration, reads the real
   `AppleFileCircuitBreakerStateStore` TSV file directly off the shared host
   directory (no `simctl get_app_container` needed, per the investigation
   document) and confirms it shows phase `HALF_OPEN`.
9. Always cleans up the created Simulator device and the shared temporary
   directory (`if: always()`).

## What was verified from this Windows session

- All of `apple-process-contention-proof`'s Gradle compile/test-compile/ABI
  tasks listed above under "What this builds" section 1.
- `./gradlew.bat :apple-process-contention-proof:tasks --all
  -Pdataloom.appleKlibCrossCompile=true` confirms
  `assembleDataLoomProcessContentionProofReleaseXCFramework` is registered
  under exactly that name, matching `dataloom-apple`'s and
  `apple-process-termination-proof`'s own proven `assemble<Name><BuildType>XCFramework`
  task-naming convention.
- Every shell script block added to `.github/workflows/apple-validation.yml`
  for the new job was extracted and checked with `bash -n` (syntax-only;
  no execution) from this Windows session -- all passed. This does not
  confirm the scripts behave correctly against real `simctl`/`xcodebuild`
  output, only that they are syntactically valid POSIX shell.
- The workflow YAML's job/step indentation was checked by hand against the
  existing, CI-proven `apple-process-termination-proof` job's own
  indentation for structural consistency (2-space job keys, 4-space
  `steps:`, 6-space `- name:`) -- no YAML linter was available on this
  Windows host to validate it mechanically (same limitation
  `docs/apple/process-termination-proof.md` already named for its own YAML).
- The two-target `project.pbxproj` was hand-authored by duplicating
  `apple-process-termination-proof-app`'s own proven single-target object
  graph (`PBXNativeTarget`/`PBXBuildFile`/`PBXFileReference`/`PBXGroup`/
  `XCBuildConfiguration`/`XCConfigurationList`) once per target, with fresh,
  non-colliding object IDs, and reviewed by hand for structural consistency
  -- no `plutil`/Xcode project validator was available on this Windows host
  (same limitation `docs/apple/process-termination-proof.md` already named).

## What was NOT, and could not be, verified from this Windows session

Everything that requires an actual macOS host, Xcode, or the iOS Simulator
-- a strictly larger set of unknowns than `apple-process-termination-proof`
carried into its own first run, since this job is structurally more complex
(two app targets instead of one, inter-process file-based coordination
instead of a single app's own kill/relaunch cycle):

- **The two-target Xcode project itself has never been opened or built by
  Xcode.** Whether Xcode accepts a hand-authored `project.pbxproj` with two
  native targets sharing one source file, with no checked-in scheme for
  either target, is unverified -- `apple-process-termination-proof-app`'s
  own single-target version of this exact pattern was confirmed to work on
  its first real run (`docs/apple/process-termination-proof.md`'s "first
  real macOS CI run" update), which is the basis for expecting the
  two-target extension to work the same way, but the extension itself is
  unverified.
- **Whether `simctl launch`'s `SIMCTL_CHILD_*` environment-forwarding
  convention behaves exactly as documented** for this specific Xcode/
  Simulator version pairing -- confirmed via web search against
  community-documented `simctl` usage (not Apple's own formal reference
  documentation, which does not fully enumerate this undocumented-but-widely-relied-upon
  convention), not independently reproduced from this Windows session.
- **Whether the iOS Simulator's lack of app-container sandboxing (the
  investigation document's central finding) actually behaves as described
  for two apps launched via `simctl launch` specifically** (as opposed to
  the app-launched-from-Xcode-directly scenarios most community discussion
  of this property describes) -- this is the single largest and most
  consequential unknown in this whole proof, since the entire mechanism
  depends on it. If this assumption is wrong, the job will fail at "Waiting
  for app A to finish opening the circuit" or "Waiting for app B to be
  alive and polling" (both bounded, loud-failure polls, not silent
  timeouts) rather than reporting a false pass.
- **The exact timing margin needed between app A's ready marker and
  dropping the "go" signal.** The job sleeps two real wall-clock seconds
  past the 400ms `openDuration`, generous relative to
  `AndroidCircuitBreakerProbeContentionInstrumentedTest`'s own 700ms margin
  over its identical 400ms `openDuration`, but Simulator app launch latency
  on a real `macos-15` runner has not been observed for this specific job.
- **Whether two apps launched via two separate `xcrun simctl launch`
  invocations from the same shell script, each polling a shared file rather
  than released by an in-process barrier, produce a race close enough to
  simultaneous for both outcomes (`ALLOWED` and `REJECTED`) to actually
  occur** -- see the module's own KDoc for why file-based polling is a
  real, if coarser-grained, substitute for Android's `CyclicBarrier`, and
  why the job's assertions do not depend on which process wins. If both
  processes are not each already polling *before* the "go" file appears
  (guarded against by waiting for both ready markers first, per the "warm
  up before racing" discipline `CircuitBreakerProbeContentionContentProviderBase`'s
  own Android precedent established), this could in principle be biased
  toward one side, but never toward "genuinely no contention" -- the
  underlying `flock`-based compare-and-set enforces mutual exclusion
  regardless of arrival order.
- **`xcrun simctl launch`'s exact stdout pid format for this job.** Reused
  verbatim from `apple-process-termination-proof`'s own already-confirmed
  parsing (`sed -n 's/^.*: *\([0-9][0-9]*\)$/\1/p'`), which that job's own
  "fully genuine green run" update confirmed works for real
  (`13881`/`14307`). Not independently re-confirmed for this new job's two
  new bundle identifiers.
- **Whether GitHub's `macos-15` runner image's `$RUNNER_TEMP`, and ordinary
  shell `mkdir`/`touch`/`cat` against it, behave with no surprises** -- an
  extremely standard GitHub Actions convention, not expected to be a real
  risk, but not independently exercised from this Windows session.

## Update: first real macOS CI run (2026-09-15)

This job ran for real on a `macos-15` GitHub Actions runner for the first
time on this PR. **The core mechanism this document depends on is
confirmed real**: two genuinely separate, independently bundle-identified
Simulator app processes (real distinct pids `11724`/`11761`), launched via
`simctl launch` with `SIMCTL_CHILD_*`-forwarded environment variables,
raced on a shared plain host-filesystem directory with no App Groups
entitlement and no paid developer account. Exactly one process observed
`ALLOWED` and the other `REJECTED`/`PROBE_IN_FLIGHT` -- never both, never
neither -- and the bonus corroboration read the real persisted
`AppleFileCircuitBreakerStateStore` state file directly from that shared
directory afterward, confirming phase `HALF_OPEN`. The iOS Simulator's lack
of app-container sandboxing behaves exactly as the investigation doc
predicted.

Two genuine, unrelated bugs were also found and fixed by this same run,
neither of which affects the core finding above:

- **A real test bug**, caught by the *unmodified* `apple-validate` job
  (not this new job): `AppleCircuitBreakerProbeContentionProofTest`'s two
  timing-dependent cases used `kotlinx.coroutines.test.runTest`, whose
  `TestDispatcher` auto-skips `delay()` calls in virtual time. The test's
  own `delay(700L)` -- meant to let the circuit's real 400ms `openDuration`
  genuinely elapse against `AppleDataLoomClock`'s real wall clock -- never
  actually advanced real time, so the subsequent probe attempt saw a
  circuit that had not actually finished its open window and was spuriously
  `Rejected` instead of `ProbeAllowed`. Fixed by switching both cases (and,
  for consistency, the third) from `runTest` to plain `runBlocking`, which
  has no virtual-time scheduler. The third, non-timing-dependent case never
  failed, which independently corroborates this diagnosis.
- **A real script bug** in this job's own result-parsing step: `IFS=$'\t'
  read -r a b c < file` was used to parse each racer's tab-separated result
  line. Bash's `read` treats tab as one of its built-in "IFS whitespace"
  characters regardless of what `IFS` is explicitly set to, so it collapses
  consecutive delimiters -- exactly the shape a winning `ALLOWED` result
  produces, since its own `rejectionReason` field is empty
  (`"ALLOWED\t\t1"`). This silently shifted the real probe-generation value
  into the reason variable and left the generation variable empty, which in
  turn made the intended `[ "$WINNER_GENERATION" -le 0 ]` validation error
  out as a malformed integer test rather than genuinely failing -- and
  because that comparison sat inside an `if` condition, `set -e` does not
  treat a failing/erroring conditional test as fatal, so the script
  silently skipped the check instead of catching it. The job's own
  load-bearing assertions (exactly one `ALLOWED`, exactly one `REJECTED`
  with reason `PROBE_IN_FLIGHT`, and the `HALF_OPEN` state-file grep) were
  never affected by this bug and all genuinely passed on their own merits.
  Fixed by replacing the `IFS`-based `read` with `cut -d$'\t' -f<n>`, which
  treats every tab as a literal, non-collapsing separator.

`continue-on-error: true` is deliberately left in place on this job for now
-- one genuine green run, immediately following two real fixes, is not yet
the same bar `apple-process-termination-proof`'s own job met (multiple
consecutive genuine green runs) before its `continue-on-error` was removed.
Removing it and reconsidering `#94`/`#95`'s dashboard percentages are both
follow-up decisions, not part of this PR.

## Update: extended to `#95`'s conflict-log domains (round 33, 2026-09-17)

**New CI infrastructure added 2026-09-17. Unverified until it runs on real
macOS CI** -- the same disclosure posture this document's own "Status"
section above used for the circuit-breaker domain before its first real run.
`docs/status/market-readiness.md`'s `#95` row gets a new dated "Recently
shipped" entry describing this work but **percentage unchanged** (still 72%)
until a real green CI run is observed for both new matrix legs.

This extends the identical, now-macOS-CI-proven mechanism above (two
genuinely independent, independently bundle-identified Simulator app
processes racing on a shared, unsandboxed host directory) from the
circuit-breaker domain to `#95`'s two durable conflict-log domains:
`DurableUnresolvedConflictLog` and `DurableResolvedConflictDecisionLog` --
the specific follow-up this document's own "What this builds"/references
section, and `docs/status/market-readiness.md`'s `#95` row, already named as
a "concrete, bounded follow-up rather than an open investigation."

### What changed

1. **`apple-process-contention-proof` (same Kotlin/Native module, new public
   objects)**: `ConflictRecordContentionProofResult` (a new plain
   `String`/`Long` result type, mirroring `ProcessContentionProofResult`'s
   own interop discipline) and two new objects,
   `AppleUnresolvedConflictLogContentionProof`/
   `AppleResolvedConflictDecisionLogContentionProof`. Unlike
   `AppleCircuitBreakerProbeContentionProof`'s asymmetric
   opener/racer shape, these two are symmetric -- both racing processes
   independently warm up (`warmUpAndSignalReady`, a harmless
   `DurableStateStore.load`) and then race
   (`waitForGoSignalThenAttemptRecord`) to `record()` the identical, fixed
   scope/record, mirroring `UnresolvedConflictContentionContentProviderBase`/
   `ResolvedConflictDecisionContentionContentProviderBase`'s own Android
   precedent exactly (see those classes' KDoc,
   `dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`).
   Both drive the real production `DurableUnresolvedConflictLog.record`/
   `DurableResolvedConflictDecisionLog.record` path directly against
   `AppleFileDurableStateStore` (`dataloom-runtime/src/iosMain/...` --
   already existed, unmodified, from `#93`'s own "Apple file-backed
   `DurableStateStore`" work) rather than a lower-level store, matching the
   circuit-breaker proof's own choice to exercise the domain type's real
   record/outcome logic, not just the store underneath it. A small
   `ConflictRecordContentionProofSupport.kt` file factors the
   touch-marker/busy-poll-for-marker helpers shared by both new objects;
   `AppleCircuitBreakerProbeContentionProof` itself is deliberately left
   with its own already-proven, unmodified duplicate of this same logic
   rather than refactored to share it -- see that file's own doc for why.
2. **`apple-process-contention-proof-app/` (two new app targets, same Xcode
   project)**: `ProcessContentionProofAppC`/`ProcessContentionProofAppD`
   (bundle ids `io.dataloom.processcontentionproof.appc`/`...appd`), sharing
   the same `AppDelegate.swift`/`Info.plist` the existing `AppA`/`AppB`
   targets already share. `AppDelegate.swift` gained a new
   `DATALOOM_DOMAIN` environment variable (`"CIRCUIT_BREAKER"` by default,
   preserving `AppA`/`AppB`'s original behavior byte-for-byte when unset;
   `"UNRESOLVED_CONFLICT"`/`"RESOLVED_DECISION"` route to the two new
   objects instead) -- `AppC`/`AppD` are reused, unmodified, across *both*
   conflict-log domains by varying this one environment variable per CI
   job/matrix entry, rather than declaring four more per-domain app targets.
3. **New CI job, `apple-conflict-log-contention-proof`**: a single job with
   a two-entry `strategy.matrix` (one leg per domain), each on its own
   dedicated `macos-15` runner with its own checkout/build/Simulator device
   -- a failure in one domain's leg cannot affect the other's, or either
   already-existing job. One shared job body was chosen over two
   fully-copy-pasted jobs specifically because both domains share the
   identical persistence path, the identical symmetric race shape, and the
   identical two app targets, differing only in which `DATALOOM_DOMAIN` is
   launched with and which on-disk state-file name to check for at the end
   (see the job's own header comment in `.github/workflows/apple-validation.yml`
   for the full reasoning). Each leg: builds `AppC`/`AppD`, creates/boots a
   dedicated Simulator device (identical runtime/device-type-selection logic
   to every other job in this file), launches `AppC` then `AppD` with the
   matrix's `DATALOOM_DOMAIN` and shared directory/marker paths, releases
   both together via a shared "go" file once both ready markers are seen,
   then parses each result file (`OUTCOME\tPERSISTED_VERSION`, via `cut`,
   never `IFS=$'\t' read` -- see the job's own comment recalling the exact
   real bug that convention caused on this same file's circuit-breaker job)
   and asserts exactly one `RECORDED`, one `ALREADY_RECORDED`, and
   `persistedVersion == 0` on both sides. Carries `continue-on-error: true`
   for the same reason `apple-process-contention-proof` itself did before
   its own first real run.
4. Unlike the circuit-breaker job's "bonus corroboration" step (a plain-text
   grep for `HALF_OPEN`), this job's own bonus-corroboration step only
   confirms the shared `AppleFileDurableStateStore` snapshot file exists --
   that store's on-disk format is hex-encoded (see its own class doc), not
   casually greppable for a domain-specific substring the way
   `AppleFileCircuitBreakerStateStore`'s plain-text TSV is. The load-bearing
   assertions are the `RECORDED`/`ALREADY_RECORDED`/`persistedVersion`
   checks reported by the app processes themselves, not this file-existence
   check.

### What was verified from this Windows session

- `./gradlew.bat :apple-process-contention-proof:compileKotlinIosArm64
  :apple-process-contention-proof:compileKotlinIosSimulatorArm64
  :apple-process-contention-proof:compileKotlinIosX64
  -Pdataloom.appleKlibCrossCompile=true` -- succeeded (run individually per
  target, per this session's own Windows Gradle-concurrency discipline).
- The equivalent three `compileTestKotlinIos*` tasks (the new
  `AppleUnresolvedConflictLogContentionProofTest`/
  `AppleResolvedConflictDecisionLogContentionProofTest`, both using
  `runBlocking`, never `runTest`, per this repository's own documented real
  regression from round 32) -- all three succeeded.
- `./gradlew.bat :apple-process-contention-proof:updateKotlinAbi
  -Pdataloom.appleKlibCrossCompile=true` then `checkKotlinAbi` -- passed
  clean; `git status` confirmed the diff to
  `apple-process-contention-proof/api/apple-process-contention-proof.klib.api`
  is purely additive (the new `ConflictRecordContentionProofResult` class
  and the two new objects), and no other module's ABI baseline changed.
- Every new/modified shell block inside `.github/workflows/apple-validation.yml`'s
  new job was extracted and checked with `bash -n` from this Windows
  session -- all five blocks passed. This does not confirm the scripts
  behave correctly against real `simctl`/`xcodebuild` output.
- The `project.pbxproj` additions (two new `PBXNativeTarget`s and their full
  supporting object graph -- `PBXBuildFile`/`PBXFileReference`/
  `PBXFrameworksBuildPhase`/`PBXResourcesBuildPhase`/`PBXSourcesBuildPhase`/
  `XCBuildConfiguration`/`XCConfigurationList` -- plus the `PBXProject`'s own
  `targets`/`TargetAttributes` lists) were hand-authored by duplicating the
  existing `AppA`/`AppB` object graph with fresh, non-colliding object IDs,
  and checked programmatically from this session for balanced
  braces/parentheses and for zero duplicate object-ID definitions -- no
  `plutil`/Xcode project validator was available on this Windows host (the
  same limitation this document's own "What was NOT verified" section
  already named for the original two-target project).
- The `Info.plist` comment-only edit was reviewed for well-formed XML by
  hand (no `plutil`/XML validator available on this Windows host either).

### What was NOT, and could not be, verified from this Windows session

The same category of unknowns this document's own "What was NOT verified"
section above already named for the circuit-breaker domain's first run,
now applying to the two new app targets and the new job:

- Whether Xcode accepts this hand-authored four-target `project.pbxproj`
  (two new targets added to the existing two) -- the two-target version of
  this exact pattern was confirmed to work for real on the circuit-breaker
  job's own first run (see the "Update" section above); the four-target
  extension itself is unverified.
- Whether `AppDelegate.swift`'s new `DATALOOM_DOMAIN` branch behaves
  correctly at runtime -- the Kotlin/Swift interop surface
  (`AppleUnresolvedConflictLogContentionProof.shared.warmUpAndSignalReady`/
  `waitForGoSignalThenAttemptRecord`, `AppleResolvedConflictDecisionLogContentionProof`'s
  same two methods) compiles and klib-verifies, but the generated
  Objective-C header has never actually been consumed by real Xcode/Swift
  compilation.
- Whether two genuinely independent Simulator processes racing to `record()`
  the same conflict/decision through `AppleFileDurableStateStore`'s real
  `flock`-based compare-and-set actually produces the expected
  `RECORDED`/`ALREADY_RECORDED` split under real inter-process timing --
  the underlying mechanism (Simulator apps sharing an unsandboxed host
  directory) is now confirmed real for the circuit-breaker domain, and
  `AppleFileDurableStateStore`'s locking is structurally identical to
  `AppleFileCircuitBreakerStateStore`'s, but this specific domain's own race
  has never run on real hardware/Simulator.
- The two-entry `strategy.matrix` job shape itself -- this is the first job
  in this workflow file to use a GitHub Actions matrix; whether it schedules
  and reports both legs correctly (independent pass/fail per leg, both
  required if `continue-on-error` is later removed) is unverified from this
  session (no way to dispatch a real GitHub Actions run from here).
- `xcrun simctl launch`'s exact stdout pid format for these two new bundle
  identifiers -- reused verbatim from the already-confirmed parsing this
  workflow file's other jobs use, not independently re-confirmed for
  `io.dataloom.processcontentionproof.appc`/`...appd`.

### Deliberately out of scope for this same change

Retry-budget's own Apple cross-process contention proof
(`AppleFileQueueProvider`'s retry-budget compare-and-set) is **not**
attempted here. Unlike the two conflict-log domains above, no Android
retry-budget-*contention*-specific instrumented test exists as a precedent
to mirror (only `AndroidProcessTerminationRetryBudgetInstrumentedTest`,
a process-*kill* proof, exists for that structure) -- extending the
mechanism to it would be a genuinely separate design exercise (deciding
what "contention" even means for a retry-budget compare-and-set with no
existing Android proof shape to copy), not a mechanical port like the two
conflict-log domains were. Scoping it out here mirrors exactly how round 32
itself scoped the original proof to the circuit-breaker domain alone rather
than attempting all three domains named as follow-ups in one PR. Apple
process-kill/relaunch evidence for either conflict-log domain (a
structurally separate proof shape from contention, extending
`apple-process-termination-proof` instead of this module) is likewise left
open.

## References

- [`docs/apple/cross-process-contention-investigation.md`](cross-process-contention-investigation.md) --
  the investigation this work acts on; read that first for why App
  Groups/extensions are blocked and why the Simulator's lack of sandboxing
  is not.
- [`docs/apple/process-termination-proof.md`](process-termination-proof.md) --
  round 31's proof; this document's own structure, CI-independence
  discipline, and disclosure shape all follow that document's precedent
  directly.
- `AndroidCircuitBreakerProbeContentionInstrumentedTest`/
  `CircuitBreakerProbeContentionContentProviderBase`,
  `AndroidUnresolvedConflictLogContentionInstrumentedTest`/
  `UnresolvedConflictContentionContentProviderBase`,
  `AndroidResolvedConflictDecisionLogContentionInstrumentedTest`/
  `ResolvedConflictDecisionContentionContentProviderBase`
  (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) --
  the three Android proofs this module's three Apple proof objects each
  mirror in intent.
- `.github/workflows/apple-validation.yml` -- the `apple-process-contention-proof`
  job (circuit breaker) and the new `apple-conflict-log-contention-proof`
  job (both conflict-log domains, as a two-entry matrix).
- `apple-process-contention-proof/` and `apple-process-contention-proof-app/` --
  the module (now three proof objects) and apps (now four targets:
  `AppA`/`AppB`/`AppC`/`AppD`).
