# Fragment: real emulator execution evidence for #94/#95 instrumented proofs (2026-10-01)

Lead-authored (not an agent), docs-only. No code changes.

## Context

An Android emulator (`Pixel_8_Pro`, API 35/36 images, Android Studio SDK already
installed on this machine) became available partway through the session. Every
prior fragment's "Not verified: real execution needs `android-validation.yml`'s
Gradle Managed Device, not observable from Windows" caveat was accurate *at the
time it was written* but is now stale for the specific tests re-run below.

Clarifying note for the dashboard: an emulator runs the real Android OS (not a
simulation of it) -- genuine `ActivityManager` process kills, genuine
cross-process `ContentProvider` IPC between separately-processed app
components, real SQLite/Room file locking, and real OS scheduling all behave
identically to physical hardware for everything these gates' acceptance
criteria test. Physical silicon only matters for radio/sensor-specific
behavior, which is not in scope anywhere in this program. This was previously
overstated as a hard "needs physical hardware" ceiling for `#101`/`#94`; that
framing is corrected here for the Android side (iOS Simulator, already used in
macOS CI, was already the equivalent unlock on that side).

## What was actually run (today, on this machine, `connectedDebugAndroidTest`)

`DATALOOM_ANDROID_BUILD=true ./gradlew :dataloom-queue-room:connectedDebugAndroidTest :dataloom-storage-room:connectedDebugAndroidTest :runtime-android-reference-consumer:connectedDebugAndroidTest`
against `Pixel_8_Pro(AVD)`: **49 instrumented tests, 0 skipped, 0 failed**, including every genuine cross-process/OS-process-kill proof in the repository:

- `AndroidProcessTerminationCircuitBreakerInstrumentedTest` (genuine `ActivityManager.killBackgroundProcesses` kill/relaunch of a second `:circuitproof` process, then re-drives the real `CircuitBreakerCoordinator` gate -- the exact extension PR #452 added today, now observed passing on real hardware-equivalent for the first time, not just compiled)
- `AndroidProcessTerminationRetryBudgetInstrumentedTest` (same kill/relaunch mechanics against the `:retrybudgetproof` process, including the `availableAt`-equality and real-acquire-gate re-drive PR #449 added)
- `AndroidProcessTerminationConflictLogInstrumentedTest` / `AndroidProcessTerminationResolvedConflictDecisionLogInstrumentedTest` (both conflict-log domains' kill/relaunch survival)
- `AndroidCircuitBreakerProbeContentionInstrumentedTest` / `AndroidUnresolvedConflictLogContentionInstrumentedTest` / `AndroidResolvedConflictDecisionLogContentionInstrumentedTest` (genuine two-process races via two separately-`android:process`'d `ContentProvider`s racing for the same permit/record)
- `AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest` (the full AC-FUNC-004 backoff/circuit-open/rejection/half-open-probe/recovery sequence through the real composed provider flow -- previously only ever run via `androidTest`'s own compile step, now observed genuinely passing)
- Everything else in all three modules' `androidTest` source sets (Room migrations, DAO instrumented tests, strategy-plan/decision persistence, workflow timeout persistence): all passing too.

Raw results: `dataloom-queue-room/build/outputs/androidTest-results/connected/debug/`, `dataloom-storage-room/build/outputs/androidTest-results/connected/debug/`, `runtime-android-reference-consumer/build/outputs/androidTest-results/connected/debug/` (per-class XML, each showing `failures="0" errors="0"`).

## Proposed dashboard text changes

For `#94`'s "Still pending" cell: remove or soften any remaining "compiled but
not executed on a real Gradle Managed Device" qualifier attached to
`AndroidProcessTerminationCircuitBreakerInstrumentedTest`'s extended re-drive
(PR #452) and `AndroidProcessTerminationRetryBudgetInstrumentedTest`'s
`availableAt`/gate re-drive (PR #449) -- both now have real passing evidence
from this machine's emulator, not just a compile check. The required
`android-validation.yml` CI job (Gradle Managed Device) still has not run
these specific extensions in CI itself -- that remains open -- but "never
observed passing anywhere" is no longer accurate.

For `#95`'s "Still pending" cell: no change needed -- its own text already
credits the Android kill/relaunch and contention proofs as done; this just
adds a second, independent confirmation (local emulator, not just CI).

No percentage change proposed by this fragment alone -- this is evidence
quality improving (compile-only -> genuinely executed), not new production
capability. The lead should judge whether closing the "never observed
passing" caveat for #94's two Android re-drive extensions justifies a small
bump beyond the 79% already set by the round-4 sync.

## What remains open

- The real `android-validation.yml` CI job (Gradle Managed Device) has still
  never run these specific extensions in CI -- only locally, on this one
  machine, once. A CI run remains the more durable, repeatable proof.
- Apple-side equivalents (iOS Simulator, already used in CI) are unaffected by
  this fragment.
- Physical-device (not emulator) proof is unchanged -- still not attempted,
  still a separate, smaller remaining gap than previously framed.
