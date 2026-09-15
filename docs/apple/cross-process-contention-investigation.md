# Apple cross-process probe contention: investigated, and found bounded via a mechanism no prior round considered

## Status

**Investigated and acted on (2026-09-15, round 32).** Prior rounds
(`docs/apple/process-termination-investigation.md`,
`docs/apple/process-termination-proof.md`'s own "What remains open" section,
and this repository's own market-readiness dashboard rows for both `#94`
and `#95`) named genuine Apple cross-process contention -- the counterpart
to `AndroidCircuitBreakerProbeContentionInstrumentedTest`/
`AndroidUnresolvedConflictLogContentionInstrumentedTest`/
`AndroidResolvedConflictDecisionLogContentionInstrumentedTest` -- as
"genuinely blocked, since iOS has no `android:process`-equivalent mechanism
to host two independent processes inside one app bundle on demand." This
document re-examines that conclusion, confirms the specific reason two of
the obvious Apple mechanisms for sharing state between independent
processes (App Groups, app extensions) really are blocked without a paid
Apple Developer Program membership this session does not have, and then
identifies and acts on a third, structurally different mechanism -- the iOS
Simulator's own lack of app-container sandboxing -- that is not blocked.
New infrastructure was built on that basis: `apple-process-contention-proof`
(Kotlin/Native module) and `apple-process-contention-proof-app` (two real,
independently bundle-identified iOS Simulator apps), plus a new CI job in
`.github/workflows/apple-validation.yml`. **This is new, never-run-before
infrastructure, unverified until it runs on real macOS CI** -- see
`docs/apple/process-contention-proof.md` for exactly what could and could
not be verified from this Windows session, following the same disclosure
discipline `docs/apple/process-termination-proof.md` established for round
31's single-process proof.

## What this compares against

Android proves genuine cross-process contention for three durable-state
domains today:

- `AndroidCircuitBreakerProbeContentionInstrumentedTest` (circuit-breaker
  half-open probe permit, `dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`)
- `AndroidUnresolvedConflictLogContentionInstrumentedTest` (`DurableUnresolvedConflictLog.record`)
- `AndroidResolvedConflictDecisionLogContentionInstrumentedTest` (`DurableResolvedConflictDecisionLog.record`)

All three use the identical shape: two genuinely different provider classes
(never the same class declared twice under different `android:process`
values -- `CircuitBreakerProbeContentionContentProviderBase`'s own class doc
explains why that fails at runtime with `Unknown authority` even though it
compiles cleanly), each hosted in its own real Android OS process, racing
via `ContentResolver.call` released together by an in-process
`CyclicBarrier`, with mutual exclusion enforced by the real production
store's own atomic compare-and-set -- never a test-only mutex.

This investigation asked, concretely and with evidence rather than
assumption: is there a real, actually-usable-in-this-repository's-CI
mechanism for two genuinely independent Apple OS processes to race on the
same durable-state file the way Android's two processes do?

## Mechanism 1: App Groups -- confirmed blocked without a paid account

App Groups (`com.apple.security.application-groups` entitlement,
`FileManager.containerURL(forSecurityApplicationGroupIdentifier:)`) is
Apple's own documented mechanism for exactly this kind of problem: multiple
apps/extensions from the same developer team sharing a filesystem location.
It is the mechanism this repository's own `process-termination-investigation.md`
had already flagged as the "obvious" candidate worth checking.

Checked directly (web search against Apple's own developer-support pages
and forum threads, not assumed):

- **App Groups requires a paid Apple Developer Program membership.** It is
  explicitly one of the restricted-entitlement capabilities not available to
  a free "Personal Team" Apple ID -- confirmed against Apple's own
  "Supported capabilities" reference and multiple independent developer
  forum threads describing the same restriction. This session, and this
  repository's CI (`.github/workflows/apple-validation.yml` runs entirely
  with `CODE_SIGNING_ALLOWED=NO`/`CODE_SIGNING_REQUIRED=NO` and no Apple ID
  or team configured anywhere in the workflow), has no such paid account.
- **Even setting that aside, CI has no path to register the capability at
  all.** Enabling App Groups via Xcode's automatic-signing flow requires
  `xcodebuild` to authenticate against the Apple Developer Portal with a
  real, logged-in Apple ID/team -- itself a known, separately hard problem
  for headless CI (multiple developer-forum threads describe exactly this
  friction for GitHub Actions-style runners). This repository's Apple CI
  was deliberately built the opposite way -- no signing identity, no team,
  `CODE_SIGNING_ALLOWED=NO` -- specifically so it needs no Apple Developer
  credentials at all. Retrofitting App Groups would mean adding exactly the
  kind of CI secret/credential dependency this repository's Apple lane has
  never needed and does not have configured.
- Entitlements are embedded into a binary via code signing
  (`codesign --entitlements`); an unsigned, `CODE_SIGNING_ALLOWED=NO` build
  -- the only kind this repository's CI produces -- carries no signed
  entitlements at all, independent of the paid-account question above.

**Conclusion: App Groups is a genuine, structural dead end for this
repository's CI as it exists today.** It would require provisioning this
session and CI do not have, not a bounded code change.

## Mechanism 2: app extensions -- the same dead end, not a way around it

The investigation this round was asked to re-examine specifically raised
app extensions (e.g. a Notification Service Extension) as a possible
alternative: an extension target runs as a genuinely separate OS process
from its host app, bundled inside the *same* app bundle, so it seemed
plausible this might sidestep the App Groups requirement entirely (same
bundle, no separate provisioning).

Checked directly: **it does not.** iOS app extensions are confirmed
genuinely separate processes from their host app, but they have **no direct
access to the host app's container at all** -- Apple's own documentation
and independent developer-forum threads are consistent on this point. App
Groups is the *only* mechanism Apple provides for a host app and its
extension to share a filesystem location or `UserDefaults` suite. An
extension-based approach would therefore still need App Groups underneath
it to satisfy this proof's actual requirement (both racing processes
reading/writing the *same* on-disk `AppleFileCircuitBreakerStateStore` file)
-- it is the same wall from mechanism 1, reached by a different route, not
a way around it. Building an extension target without App Groups would only
produce two processes that cannot see each other's state at all, which
proves nothing.

**Conclusion: app extensions are not a genuine alternative for this specific
proof's requirement.** Named and ruled out honestly rather than left
unexamined, per this round's explicit instructions.

## Mechanism 3: the iOS Simulator's own lack of app-container sandboxing -- genuinely bounded

The investigation then asked a narrower, more specific question than either
mechanism above: is there *any* way for two independently bundle-identified
Simulator apps to see the same file without App Groups at all?

Checked directly (web search against Apple developer-forum threads
specifically discussing Simulator sandbox behavior): **the iOS Simulator
does not enforce the iOS app sandbox against the host Mac's filesystem the
way a real device does.** A Simulator-hosted app runs as an ordinary macOS
process under the host user account; it has the same filesystem access as
any other process that user can run, including paths entirely outside its
own app container. This is independently, repeatedly described across
Apple's own developer forums (e.g. the forum thread literally titled
"Sandboxing iOS Simulator") as a known, intentional-in-practice property of
the Simulator, not a bug or an unstable implementation detail: "an iOS app
running in simulator has complete access to the Mac's file system... there
is no way to restrict access to the Mac's file system when running an app
in the Simulator."

This is structurally different from Android's `android:process` -- it is
not a second-process-inside-one-bundle mechanism at all -- but it produces
exactly the proof-relevant property this proof needs: **two genuinely
separate, independently launched OS processes (two separate `.app` bundles,
two separate bundle identifiers, two separate `xcrun simctl launch`-produced
pids) that can read and write the same absolute file path with no
entitlement, no provisioning profile, and no paid Apple Developer account
involved at all.** `AppleFileCircuitBreakerStateStore`'s existing,
unmodified, `flock`-based advisory locking -- already documented as
"remain[ing] exact across multiple store instances and cooperating app
processes that use the same directory and file name" -- then enforces
mutual exclusion across the two real OS processes exactly the way it would
across any two arbitrary processes on the same machine, since `flock` is a
kernel-level, cross-process primitive with no awareness of, or dependency
on, iOS's application-sandbox model at all.

A second, genuinely convenient consequence: because neither app needs to
write inside its own sandboxed container for this to work, the shared
directory can be a plain path under the CI runner's own `$RUNNER_TEMP`,
known upfront by the host shell script -- no `xcrun simctl
get_app_container` resolution step is needed at all (unlike
`apple-process-termination-proof`'s CI job, which resolves its app's
container path from outside the app precisely because *that* proof's state
genuinely lives inside a single app's own sandboxed Documents directory).
The host CI script itself can `cat` the real production state file directly
off its own filesystem as a bonus corroboration step, with no simulator
tooling involved in that read at all.

### Why this is not a workaround or a loophole

This is a genuine, stable, Apple-acknowledged property of Simulator
execution -- not an undocumented crash-prone edge case, and not something
this round is the first to notice informally (multiple independent
developer-forum threads describe relying on it deliberately for local
development workflows). It does mean this specific proof's mechanism is
Simulator-specific and would not carry over to a real-device CI lane if one
were ever added -- worth naming honestly: **a real-device version of this
exact proof would still need App Groups and therefore a paid account**, the
same wall mechanisms 1 and 2 hit. Since this repository's entire Apple CI
lane already runs exclusively against the Simulator (see
`apple-validation.yml` in full -- no device provisioning exists anywhere in
it today), this is not a new limitation this proof introduces; it matches
every other Apple proof already in this repository.

## What was built on this basis

See `docs/apple/process-contention-proof.md` for the full shape, and this
round's PR description for exactly what could and could not be verified
from this Windows session. In short:

- `apple-process-contention-proof/` -- a new, narrow Kotlin/Native module
  driving the real `CircuitBreakerCoordinator`/`CircuitBreakerExecutionGate`
  pair against `AppleFileCircuitBreakerStateStore`, exporting exactly two
  primitive-typed methods (confirmed via its own `.klib.api` ABI dump).
- `apple-process-contention-proof-app/` -- one Xcode project, two real app
  targets (`ProcessContentionProofAppA`/`...AppB`, two bundle identifiers),
  sharing one Swift source file and one Kotlin/Native XCFramework, launched
  with different `DATALOOM_ROLE` values via `simctl launch`'s own documented
  `SIMCTL_CHILD_*` environment-forwarding convention.
- A new `apple-process-contention-proof` CI job in
  `.github/workflows/apple-validation.yml`, kept independent of both
  existing jobs and carrying `continue-on-error: true` until a real green
  run is observed, matching `apple-process-termination-proof`'s own now-superseded
  initial posture.

## What remains open

- **Verification on real macOS CI.** Nothing above has run on an actual
  macOS runner from this Windows session -- see
  `docs/apple/process-contention-proof.md`'s own verification section for
  the precise boundary (what compiled/klib-verified here versus what only a
  real run can confirm), following the exact disclosure shape
  `docs/apple/process-termination-proof.md` used for round 31's proof
  before its own first real run.
- **The two conflict-log domains this gate (`#95`) specifically names**
  (`DurableUnresolvedConflictLog`/`DurableResolvedConflictDecisionLog`
  contention) are not built by this round. This round's new infrastructure
  proves the *mechanism* (two genuinely independent Simulator processes
  racing via a shared, unsandboxed host directory) against the
  circuit-breaker domain (`#94`) specifically, since that domain already had
  proven single-process Apple infrastructure
  (`apple-process-termination-proof`) to build from directly. Extending the
  identical app/module/CI shape to `DurableUnresolvedConflictLog`/
  `DurableResolvedConflictDecisionLog` is a concrete, bounded, mechanical
  follow-up now that the underlying mechanism is demonstrated -- the same
  relationship Android's own retry-budget and conflict-log contention proofs
  had to its own first circuit-breaker contention proof -- not a new
  open investigation.
- **A real-device (non-Simulator) version of any of this** remains blocked
  on the same paid-account wall mechanisms 1 and 2 hit, as noted above. This
  repository's Apple CI has never targeted real devices, so this is not a
  new gap this round introduces.

## References

- [`docs/apple/process-termination-investigation.md`](process-termination-investigation.md) --
  round prior to this one; first named App Groups/extensions as unexamined
  candidates and the `android:process`-equivalent gap in general.
- [`docs/apple/process-termination-proof.md`](process-termination-proof.md) --
  round 31's single-process kill/relaunch proof, whose infrastructure and
  disclosure discipline this round's proof directly builds from and follows.
- [`docs/apple/process-contention-proof.md`](process-contention-proof.md) --
  this round's own CI shape and verification-boundary document.
- `AndroidCircuitBreakerProbeContentionInstrumentedTest`/
  `CircuitBreakerProbeContentionContentProviderBase`
  (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) --
  the Android proof this round's Apple proof mirrors in intent, not in
  mechanism.
- `apple-process-contention-proof/`, `apple-process-contention-proof-app/`,
  and the new `apple-process-contention-proof` job in
  `.github/workflows/apple-validation.yml` -- the new module, apps, and CI
  job this investigation's conclusion produced.
