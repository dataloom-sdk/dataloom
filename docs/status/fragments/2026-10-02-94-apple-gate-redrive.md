# Fragment: Apple circuit-breaker/retry-budget gate re-drive after relaunch (2026-10-02)

## Context

`docs/status/market-readiness.md`'s `#94` row's "Still pending" text named:
"Apple's circuit-breaker and retry-budget kill/relaunch proofs still only
check raw persisted state and never re-drive the real gate." This mirrors,
on the Apple side, the exact gap PRs #449/#452 already closed for Android
(`AndroidProcessTerminationRetryBudgetInstrumentedTest`'s `availableAt`
equality + real-acquire-gate re-drive, and
`AndroidProcessTerminationCircuitBreakerInstrumentedTest`'s extended
reject-before-deadline/probe-granted-at-deadline/recovery-after-success
re-drive of the real `CircuitBreakerCoordinator` gate).

Read in full before writing any code:
`AndroidProcessTerminationCircuitBreakerInstrumentedTest.kt`,
`AndroidProcessTerminationRetryBudgetInstrumentedTest.kt`, and their
content-provider implementations
(`CircuitBreakerProcessTerminationContentProvider.kt`,
`RetryBudgetProcessTerminationContentProvider.kt`), all in
`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`; the
existing Apple proof objects
(`apple-process-termination-proof/src/iosMain/kotlin/io/dataloom/processterminationproof/AppleCircuitBreakerProcessTerminationProof.kt`,
`AppleRetryBudgetProcessTerminationProof.kt`); their app wiring
(`apple-process-termination-proof-app/Sources/ProcessTerminationProofApp/AppDelegate.swift`,
`apple-process-termination-proof-retry-budget-app/Sources/RetryBudgetProcessTerminationProofApp/AppDelegate.swift`);
and the CI mechanics in `.github/workflows/apple-validation.yml`'s
`apple-process-termination-proof` / `apple-retry-budget-process-termination-proof`
jobs, which confirmed the gap exactly as described: each job's own "Install,
launch, kill, relaunch, and verify persisted state" step reads the raw
on-disk state file (`dataloom-circuit-state-v1.tsv` /
`dataloom-queue-state-v1.tsv`) directly via `cat`, from outside the app
process, and byte-diffs it before/after the kill -- it never calls back into
the relaunched app to re-drive `CircuitBreakerCoordinator.acquire`/
`recordSuccess` or `AppleFileQueueProvider.acquire`.

## Why iOS needed a different mechanism than Android's ContentProvider IPC

Android's re-drive works because the JVM instrumentation test process stays
alive for the whole test and can issue multiple separate
`ContentResolver.call` IPC round-trips into the *same* already-relaunched
`:circuitproof`/`:retrybudgetproof` process, observing and asserting on each
one in sequence. iOS has no equivalent: `xcrun simctl launch` starts a
process and returns; there is no mechanism in this repository's CI
environment to call back into a running Simulator app's code from the shell
script driving it. The genuine re-drive therefore has to happen *inside* the
app's own `application(_:didFinishLaunchingWithOptions:)` on the relaunch,
with its outcome written to a file the CI script reads afterward -- the same
pattern already used for the raw-state file, extended rather than replaced.

A second real constraint shaped the circuit-breaker side specifically: the
three-decision re-drive (reject-before-deadline / probe-granted-at-deadline /
recovery-after-success) necessarily *mutates* the persisted circuit state
(OPEN -> HALF_OPEN -> CLOSED) as part of proving the gate works, which would
break the existing job's own byte-identical-file assertion if run inside the
same relaunch that assertion observes. Rather than touch that already-green,
required check, the re-drive logic was still added to the *same* app
(`AppDelegate.swift`'s relaunch branch) but is verified by a **new,
separate** CI workflow (`.github/workflows/apple-gate-redrive-proof.yml`,
two new jobs, each booting its own independent Simulator device) that builds
and drives the same proof apps through their own independent
install/launch/kill/relaunch/verify cycle, checking a second, separate
result file (`dataloom-circuit-gate-redrive-v1.tsv` /
`dataloom-retry-budget-gate-redrive-v1.tsv`) rather than the original state
file. This follows the agent playbook's conflict-avoidance rule (new CI jobs
go in a new workflow file, reusing `apple-validation.yml`'s patterns by
reading, never editing it) and means a failure in this brand-new,
never-run-before check cannot regress the already-proven, required raw-state
check, and vice versa.

The retry-budget side needed no such split: `AppleFileQueueProvider.acquire`
is read via caller-supplied `acquiredAt`/`leaseExpiresAt` instants (never an
internal wall clock), so the new `redriveAcquireGateAfterRelaunch` mirrors
`RetryBudgetProcessTerminationContentProvider.readRetryBudget`'s exact
two-step shape (gate-check `NoEntries` one millisecond before `availableAt`,
then a real confirming `acquire` at `availableAt`) without disturbing the
existing byte-diff check in practice, though it is also verified through the
new workflow rather than the existing one for the same conflict-avoidance
reason.

## What shipped

- `apple-process-termination-proof`'s commonMain gained
  `CircuitBreakerGateRedriveProofState` (new primitive-typed result class,
  same String/Long-field pattern as `ProcessTerminationProofState`).
- `AppleCircuitBreakerProcessTerminationProof.redriveGateAfterRelaunch(directoryPath)`
  (new, iosMain): loads the already-persisted record, then drives the real
  `io.dataloom.runtime.retry.CircuitBreakerCoordinator` (constructed
  directly, the same scope-reduction rationale the module doc already gives
  for `openCircuitAndPersist`) through the same three decisions Android's
  `CircuitBreakerProcessTerminationContentProvider` proves, using a fixed,
  synthetic clock computed relative to the persisted `openUntil` deadline
  (never real wall-clock) so it is exact regardless of how long the real
  `simctl terminate`/relaunch cycle around it takes.
- `AppleRetryBudgetProcessTerminationProof.redriveAcquireGateAfterRelaunch(directoryPath)`
  (new, iosMain): mirrors `readRetryBudget`'s gate-check-then-confirm-acquire
  exactly against the real `AppleFileQueueProvider`.
  `RetryBudgetProcessTerminationProofState` gained an `availableAtEpochMillis`
  field (constructor signature change, acceptable pre-V1) so `availableAt`
  equality can be asserted explicitly rather than only implied by a raw byte
  diff, closing the specific gap the row named
  ("`availableAt`-equality plus real-acquire-gate re-drive").
- Both `AppDelegate.swift` files: the previously-idempotent "do nothing on
  relaunch" branch now calls the new re-drive method and writes its outcome
  to a second result file alongside the original state file.
- New `.github/workflows/apple-gate-redrive-proof.yml`: two `continue-on-error:
  true` jobs (never run before), each independently building, installing,
  launching, killing (`xcrun simctl terminate`), relaunching, and verifying
  the new result file's content against the expected outcomes.
- Both iosTest files extended with same-process sanity coverage of the new
  methods (open/write then immediately re-drive, asserting the expected
  outcomes) -- the same "does not prove kill/relaunch by itself" caveat the
  existing tests already carry applies equally here.

## Verified (from this Windows host; no macOS available)

- `:apple-process-termination-proof:compileKotlinIosSimulatorArm64`,
  `compileKotlinIosArm64`, `compileKotlinIosX64` and the matching
  `compileTestKotlin*` tasks for all three Apple targets: clean
  (`-Pdataloom.appleKlibCrossCompile=true`).
- `:apple-process-termination-proof:updateKotlinAbi` then `checkKotlinAbi`
  (same flag): clean. The regenerated `apple-process-termination-proof.klib.api`
  diff is exactly the intended additive surface (`CircuitBreakerGateRedriveProofState`,
  `redriveGateAfterRelaunch`, `redriveAcquireGateAfterRelaunch`, and the new
  `availableAtEpochMillis` property/constructor parameter on
  `RetryBudgetProcessTerminationProofState`) -- no other module's baseline
  changed (`git status` confirms only this module's files and the two
  `AppDelegate.swift` files touched).
- `bash -n` on every new/extracted multi-line shell block in the new
  workflow file.
- A direct, file:line read-through confirming the new re-drive methods call
  the same real production classes Android's own re-drive calls:
  `io.dataloom.runtime.retry.CircuitBreakerCoordinator.acquire`/`recordSuccess`
  (matching `CircuitBreakerProcessTerminationContentProvider`'s
  `attemptAccessBeforeDeadline`/`attemptProbeAtDeadline`/
  `recordProbeSuccessAndReverifyRecovery`) and
  `io.dataloom.runtime.queue.AppleFileQueueProvider.acquire` (matching
  `RetryBudgetProcessTerminationContentProvider.readRetryBudget`'s own
  gate-check) -- never a test-only shortcut or a parallel/fake
  implementation.

## What was NOT verified, stated honestly

- **This new code has never actually executed on a real macOS runner or
  Simulator.** No `xcrun simctl` command has run against it; no Simulator
  app has launched, been killed, or relaunched with it; the two new
  `apple-gate-redrive-proof.yml` jobs have never produced a single real CI
  run. Everything above is compile/ABI/read-through verification only, done
  from a Windows host with no macOS access in this session, exactly the
  limitation the row's own existing text already discloses for prior Apple
  proofs in this area.
- The byte-level assumption underlying the circuit-breaker job split (that
  the existing `apple-process-termination-proof` job's raw-state-file
  byte-diff would legitimately start failing if the same relaunch also ran
  the mutating gate re-drive) was reasoned from reading
  `CircuitBreakerCoordinator`'s transition logic directly, not observed on a
  real run.
- The retry-budget re-drive's acquire/defer idempotency claim (that
  redriving a second acquire against the same entry does not perturb the
  persisted snapshot in a way the existing byte-diff would notice) was
  likewise reasoned from reading `AppleFileQueueProvider.acquire`/`defer`
  directly, not observed on a real run; this version of the retry-budget
  re-drive does not call `defer` at all (mirroring Android's own
  `readRetryBudget`, which also leaves the entry leased), so the point is
  moot for this PR's actual code, but is recorded here since it was part of
  the design reasoning.
- The existing `apple-process-termination-proof`/
  `apple-retry-budget-process-termination-proof` jobs in
  `apple-validation.yml` are untouched by this PR and should continue to
  pass unaffected; that expectation itself is unverified until CI actually
  runs.

## Proposed percentage

`#94`: 79% -> 80%. Justification: this closes a named, specific documentation
gap with real new production-adjacent proof-harness code (not a test-only
shortcut) that drives the actual `CircuitBreakerCoordinator`/
`AppleFileQueueProvider` gate classes, mirroring Android's own already-merged
fix for the identical gap. It is held to one point, not more, because the
decisive evidence -- an actual green run on macOS CI -- has not happened yet;
until it does, this is "compiles and reads correctly" evidence, the same
tier the dashboard has consistently valued lower than genuine execution
elsewhere in this row's own history.

## Proposed "Still pending" text update

Replace "Apple's circuit-breaker and retry-budget kill/relaunch proofs still
only check raw persisted state and never re-drive the real gate" with:
"Apple's circuit-breaker and retry-budget kill/relaunch proofs now also
re-drive the real `CircuitBreakerCoordinator`/`AppleFileQueueProvider` gate
after a genuine relaunch (mirroring Android's own `#449`/`#452` fix), via a
new, separate `apple-gate-redrive-proof.yml` CI workflow rather than
extending the existing required jobs -- but this has never executed on real
macOS CI; only Windows-host compile/ABI/read-through verification exists so
far, and the first genuine green (or red) run on real Simulator hardware
remains open."
