# Apple Simulator cross-process probe-contention CI proof (`#94`/`#95`)

## Status

**New CI infrastructure added 2026-09-15 (round 32); confirmed genuinely
real across five consecutive green macOS CI runs, `continue-on-error`
removed 2026-09-17 (round 33).** This document describes what was built,
what could and could not be verified from a Windows development host before
its first real run, and the format/label uncertainties that run resolved --
the same disclosure shape
[`docs/apple/process-termination-proof.md`](process-termination-proof.md)
used for round 31's single-process kill/relaunch proof. See "Update:
`continue-on-error` removed (round 33, 2026-09-17)" below for the full
confirmation. `#94`/`#95`'s dashboard percentages remain unchanged: this
proof exercises the circuit-breaker domain specifically, not `#94`'s
retry-budget structure or either of `#95`'s own two conflict-log domains --
extending the identical app/module/CI shape to those is a named, bounded
follow-up, not yet done.

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

## Update: `continue-on-error` removed (round 33, 2026-09-17)

Following the two fixes above, this job produced four further genuine green
runs across separate PR/main pushes -- real distinct pids each time, exactly
one `ALLOWED`/one `REJECTED(PROBE_IN_FLIGHT)`, `HALF_OPEN` state confirmed
each time, including one run on `main` itself. Five genuine green runs total
now meets the same bar `apple-process-termination-proof`'s own circuit-
breaker job cleared before its `continue-on-error` was removed in round 31.
`continue-on-error: true` has been removed from this job in
`.github/workflows/apple-validation.yml` -- it is now a required check like
every other job in this workflow.

This closes out the infrastructure-proving phase of this document. The
mechanism itself (Simulator sandbox bypass, `flock`-based mutual exclusion
across two genuinely independent processes) is confirmed real for the
circuit-breaker domain specifically. Extending the identical app/module/CI
shape to `#95`'s own two conflict-log domains (`DurableUnresolvedConflictLog`,
`DurableResolvedConflictDecisionLog`) and to `#94`'s retry-budget structure's
own contention proof remain named, bounded follow-ups -- not part of this
update, and not yet reflected in `#94`/`#95`'s dashboard percentages, which
stay at their current values pending that follow-up work actually landing.

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
  `CircuitBreakerProbeContentionContentProviderBase`
  (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) --
  the Android proof this Apple proof mirrors in intent.
- `.github/workflows/apple-validation.yml` -- the new
  `apple-process-contention-proof` job.
- `apple-process-contention-proof/` and `apple-process-contention-proof-app/` --
  the new module and apps.
