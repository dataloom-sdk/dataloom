# Fragment: `#94` (DL-040) real circuit-breaker gate re-drive after process relaunch (2026-09-30)

Addresses item 2 of the ordered remaining backlog left by
[`fragments/2026-09-29-94-composed-queue-circuit-retry-budget-gate.md`](2026-09-29-94-composed-queue-circuit-retry-budget-gate.md)
("Real circuit-breaker gate re-drive after process relaunch — Android and
Apple both"), for the **Android** side only. That fragment confirmed the gap
directly: `AndroidProcessTerminationCircuitBreakerInstrumentedTest`'s
`readCircuitState` called `RoomCircuitBreakerStateStore(database).load(SCOPE)`
directly, never the real `CircuitBreakerCoordinator`/`CircuitBreakerExecutionGate`
decision path, after the genuine kill/relaunch.

## (a) Proposed "Recently shipped" log entry

| Date | Change | Evidence |
|---|---|---|
| 2026-09-30 | `#94`: `AndroidProcessTerminationCircuitBreakerInstrumentedTest` now re-drives the real `CircuitBreakerCoordinator.acquire`/`recordSuccess` gate itself (not just `RoomCircuitBreakerStateStore.load`) against the already-relaunched `:circuitproof` process, asserting reject-before-deadline (`Rejected(OPEN)` one millisecond before the persisted open-deadline), probe-granted-exactly-once-at-deadline (`ProbeAllowed` with a real generation), and recovery-after-success (`Allowed` after the probe's success is recorded) — closing the Android half of the "kill/relaunch proofs never re-drive the real gate" gap. A new, genuinely-executed Robolectric test (`CircuitGateReconnectRobolectricTest`, `runtime-android-reference-consumer`) independently proves the identical three-decision sequence against a fresh `RoomCircuitBreakerStateStore` connection (same on-disk file, discarded first connection — not a genuine OS process kill, but real production gate logic re-derived from a cold on-disk read) | `runtime-android-reference-consumer:testDebugUnitTest --tests CircuitGateReconnectRobolectricTest` (Robolectric, ran and passed locally: 1 test, 0 failures); `dataloom-queue-room:compileDebugAndroidTestKotlin` (compiles; real cross-process execution needs `android-validation.yml`'s Gradle Managed Device, not observable from Windows) |

## (b) Status / percentage

**Proposed: 78% → 79%, one point.** This closes only the Android side of one
named backlog item (the real-gate re-drive), verified by a genuinely-run
Robolectric proof plus a compiles-but-not-executed `androidTest`. It does not
close the item outright (Apple has no equivalent yet, and the `androidTest`
itself has not run on a real Gradle Managed Device from this session), so a
larger bump is not warranted.

- New evidence, genuinely run:
  1. `CircuitGateReconnectRobolectricTest.realGateRedrivenAfterFreshConnectionRejectsProbesAndRecovers`
     (`runtime-android-reference-consumer/src/test/kotlin/io/dataloom/consumer/android/CircuitGateReconnectRobolectricTest.kt`)
     opens the real circuit through `CircuitBreakerExecutionGate` with a fixed
     clock, discards that connection, opens a brand-new
     `RoomCircuitBreakerStateStore` connection to the same on-disk file, and
     drives the real `CircuitBreakerCoordinator` three times against it:
     `acquire` one millisecond before the persisted open-deadline returns
     `Rejected(CircuitBreakerRejectionReason.OPEN)`; `acquire` exactly at the
     deadline returns `ProbeAllowed` with a real generation; `recordSuccess`
     for that exact permit followed by one more `acquire` returns `Allowed`.
     Ran via `./gradlew :runtime-android-reference-consumer:testDebugUnitTest
     --tests "io.dataloom.consumer.android.CircuitGateReconnectRobolectricTest"
     -Dorg.gradle.workers.max=2` with `DATALOOM_ANDROID_BUILD=true`: build
     successful, `TEST-...CircuitGateReconnectRobolectricTest.xml` shows
     `tests="1" skipped="0" failures="0" errors="0"`.
  2. `AndroidProcessTerminationCircuitBreakerInstrumentedTest` (`dataloom-queue-room`,
     `androidTest`) extended with the same three re-drives, all served by the
     already-relaunched `:circuitproof` process (asserted via pid equality to
     `pidAfterRelaunch`, so no fourth process is silently substituted). New
     `CircuitBreakerProcessTerminationContentProvider` methods
     (`attemptAccessBeforeDeadline`, `attemptProbeAtDeadline`,
     `recordProbeSuccessAndReverifyRecovery`) call the real
     `CircuitBreakerCoordinator.acquire`/`recordSuccess` against fresh
     connections to the same on-disk database, using the existing
     `openCircuit` sequence's own deterministic fixed-clock timeline (no new
     argument needed across the process boundary). Compiled successfully
     (`DATALOOM_ANDROID_BUILD=true ./gradlew :dataloom-queue-room:compileDebugAndroidTestKotlin
     -Dorg.gradle.workers.max=2` — `BUILD SUCCESSFUL`). **Not executed**: this
     Windows host has no Gradle Managed Device / emulator; real execution
     needs `android-validation.yml`'s `pixel2Api35` managed device, which this
     session did not trigger or observe.
- Explicitly **not** claimed or changed by this work:
  - **Apple** has no equivalent re-drive yet, for either the circuit-breaker
    or retry-budget domain. `apple-process-termination-proof` only ever reads
    the persisted state file directly, the same shape
    `AndroidProcessTerminationCircuitBreakerInstrumentedTest` had before this
    PR. Mirroring this Android change there (re-driving the real Kotlin/Native
    `CircuitBreakerCoordinator`/`CircuitBreakerExecutionGate` against a fresh
    `AppleFileCircuitBreakerStateStore` connection after a genuine
    `xcrun simctl terminate`/relaunch) is a concrete, bounded follow-up, not
    attempted here — this Windows host cannot build, run, or observe Apple
    Simulator CI at all.
  - The extended `androidTest` itself has not been observed passing on real
    hardware/emulator from this session — only compiled. The Robolectric test
    is a genuine, actually-run proof of the same gate logic, but it re-drives
    the gate from a fresh connection inside one JVM process, not across a real
    OS process kill (no distinct pid, no cross-process SQLite file locking).
  - Cross-process *contention* for the half-open probe lease after a relaunch
    (as opposed to without one) is not exercised; that combination was never
    in scope for this slice.

## (c) "Still pending" text update

The current row's "Still pending" column contains this clause, now stale
after `#449` (composed loop + retry-budget `availableAt`/gate re-drive) and
this PR (circuit-breaker gate re-drive, Android only):

> "the kill/relaunch proofs check raw persisted state but never re-drive the
> real production gate after relaunch, and the Android proof never asserts
> `availableAt`"

Propose replacing it with:

> "Apple's kill/relaunch proofs (both the circuit-breaker and retry-budget
> domains) still never re-drive the real production gate after relaunch —
> only Android does now, for both domains (`#449`, this PR); the extended
> Android circuit-breaker gate re-drive itself has not yet been observed
> passing on a real Gradle Managed Device"

## Ordered remaining backlog (this agent's view, for the lead to confirm)

1. Apple counterpart of the circuit-breaker gate re-drive (this PR's Android
   change, mirrored via `apple-process-termination-proof` +
   `AppleFileCircuitBreakerStateStore`/`CircuitBreakerCoordinator`).
2. Apple counterpart of the composed queue/circuit loop
   (`AppleFileQueueProvider` + `AppleFileCircuitBreakerStateStore`) — carried
   over from the prior fragment, unchanged by this PR.
3. A real `android-validation.yml` Gradle Managed Device run observing both
   the extended `AndroidProcessTerminationCircuitBreakerInstrumentedTest` and
   the already-merged extended `AndroidProcessTerminationRetryBudgetInstrumentedTest`
   pass on real hardware/emulator — compiled-only so far for both.
4. Android cross-process queue-lease contention test (or the documented
   single-process alternative the reconciliation audit allows) — carried over,
   unchanged by this PR.
5. `FR-RETRY-005` adapters beyond Ktor, or a recorded decision to scope hints
   to Ktor only — carried over, unchanged by this PR.
6. The "KMP Android consumer" definition decision (release-lead call) — carried
   over, unchanged by this PR.
