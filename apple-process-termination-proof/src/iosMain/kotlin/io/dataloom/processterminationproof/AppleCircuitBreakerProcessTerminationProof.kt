package io.dataloom.processterminationproof

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.AppleFileCircuitBreakerStateStore
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerPermission
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import kotlinx.coroutines.runBlocking

/**
 * Real production write/read path exercised by the Apple Simulator
 * process-termination CI proof app, for `#94`'s "prove real Apple process
 * termination/relaunch" gap.
 *
 * This is a Kotlin `object`, which Kotlin/Native exports to Objective-C/Swift
 * as a class with a `shared` singleton accessor -- from Swift:
 * `AppleCircuitBreakerProcessTerminationProof.shared.openCircuitAndPersist(directoryPath:)`.
 *
 * ## What this drives
 *
 * [openCircuitAndPersist] performs two real, sequential compare-and-set
 * writes through [AppleFileCircuitBreakerStateStore] -- the exact production
 * store `CircuitBreakerCoordinator` uses on Apple platforms
 * (`dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/retry/AppleFileCircuitBreakerStateStore.kt`)
 * -- against the caller-supplied directory:
 *
 * 1. A closed record with one recorded failure (version `null` -> `0`).
 * 2. An open record building on that version (version `0` -> `1`), the same
 *    state shape `CircuitBreakerCoordinator` persists when a circuit opens.
 *
 * [readPersistedState] opens a brand-new [AppleFileCircuitBreakerStateStore]
 * instance against the same directory and reads the record back -- mirroring
 * how the existing `AppleFileCircuitBreakerStateStoreTest` proves state
 * survives a new store instance *within the same process*. The CI proof this
 * module exists for adds the missing piece: calling [openCircuitAndPersist]
 * from one real, launched Simulator app process, killing that process with
 * `xcrun simctl terminate` (a genuine OS-level kill, not an in-process
 * simulation), relaunching it, and calling [readPersistedState] from the
 * *relaunched* process -- see `docs/apple/process-termination-proof.md` for
 * the full CI shape and what remains unverified from a Windows host.
 *
 * ## Scope reduction versus the Android precedent
 *
 * Android's `CircuitBreakerProcessTerminationContentProvider` drives its two
 * failures through the full `CircuitBreakerExecutionGate`/
 * `CircuitBreakerCoordinator` pair. [openCircuitAndPersist] instead drives
 * [AppleFileCircuitBreakerStateStore] directly with hand-built
 * [CircuitBreakerState] records equivalent to what the coordinator would
 * persist for the same transition. This keeps this module's own
 * Kotlin/Swift-interop surface to two methods taking/returning only `String`
 * and [ProcessTerminationProofState] -- `CircuitBreakerCoordinator`'s own
 * constructor requires a clock abstraction, failure-threshold configuration,
 * and contention-limit wiring that would otherwise need to cross the same
 * interop boundary for no proof-relevant benefit. Wiring the full
 * coordinator through this same app target is a legitimate, separate
 * follow-up; it is not required to prove genuine process kill/relaunch
 * survival for the persisted store itself.
 *
 * ## Gate re-drive after relaunch
 *
 * [redriveGateAfterRelaunch] closes the gap named above and in
 * `docs/status/market-readiness.md`'s `#94` row ("Apple's circuit-breaker
 * and retry-budget kill/relaunch proofs still only check raw persisted
 * state and never re-drive the real gate"): it drives the real
 * [CircuitBreakerCoordinator] -- the same coordinator class
 * `CircuitBreakerExecutionGate` wraps in production -- through the identical
 * three decisions
 * `AndroidProcessTerminationCircuitBreakerInstrumentedTest`/
 * `CircuitBreakerProcessTerminationContentProvider` prove on Android after a
 * genuine process kill/relaunch: reject-before-deadline,
 * probe-granted-at-deadline, and recovery-after-success. See its own KDoc.
 */
public object AppleCircuitBreakerProcessTerminationProof {

    private val scope: CircuitBreakerScope = CircuitBreakerScope.global()
    private val clock = AppleDataLoomClock()

    /**
     * Drives two real production writes through a new
     * [AppleFileCircuitBreakerStateStore] rooted at [directoryPath] and
     * returns the final persisted record. Throws (via Kotlin `error`) if
     * either write unexpectedly fails or conflicts -- this proof harness
     * intentionally does not swallow or retry failures, matching this
     * repository's "fail loudly rather than assume success" testing
     * discipline.
     */
    public fun openCircuitAndPersist(directoryPath: String): ProcessTerminationProofState = runBlocking {
        val store = AppleFileCircuitBreakerStateStore(directoryPath)
        val now = clock.now().epochMilliseconds

        val closed = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.CLOSED,
            consecutiveFailures = 1,
            failureWindowStartedAt = DataLoomInstant(now),
            openUntil = null,
            probeGeneration = 0L,
            probeInFlight = false,
            updatedAt = DataLoomInstant(now),
        )
        val created = store.compareAndSet(
            CircuitBreakerCompareAndSetRequest(
                scope = scope,
                expectedVersion = null,
                nextState = closed,
            ),
        ).requireUpdated("initial closed-with-failure write")

        val openUntilMillis = now + OPEN_DURATION_MILLIS
        val opened = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.OPEN,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = DataLoomInstant(openUntilMillis),
            probeGeneration = 0L,
            probeInFlight = false,
            updatedAt = DataLoomInstant(now),
        )
        val updated = store.compareAndSet(
            CircuitBreakerCompareAndSetRequest(
                scope = scope,
                expectedVersion = created.record.version,
                nextState = opened,
            ),
        ).requireUpdated("circuit-open write")

        updated.record.toProofState()
    }

    /**
     * Reads the persisted record back through a brand-new
     * [AppleFileCircuitBreakerStateStore] instance rooted at [directoryPath].
     * Returns `null` when no record has been persisted yet. Throws (via
     * Kotlin `error`) on a store failure.
     */
    public fun readPersistedState(directoryPath: String): ProcessTerminationProofState? = runBlocking {
        val store = AppleFileCircuitBreakerStateStore(directoryPath)
        when (val result = store.load(scope)) {
            is ProviderOperationResult.Success -> when (val loaded = result.value) {
                is CircuitBreakerLoadResult.Found -> loaded.record.toProofState()
                CircuitBreakerLoadResult.Missing -> null
            }
            is ProviderOperationResult.Failure ->
                error("AppleFileCircuitBreakerStateStore.load failed: ${result.error}")
        }
    }

    /**
     * Re-drives the real [CircuitBreakerCoordinator] gate against the
     * record [openCircuitAndPersist] already persisted at [directoryPath] --
     * not just [readPersistedState]'s raw-row read -- through the same three
     * decisions `CircuitBreakerProcessTerminationContentProvider` proves on
     * Android after a genuine process kill/relaunch:
     *
     * 1. **Reject-before-deadline**: one millisecond before the persisted
     *    `openUntil` deadline, the real gate must still return
     *    `Rejected(OPEN)`.
     * 2. **Probe-granted-at-deadline**: exactly at that deadline, the real
     *    gate must grant exactly one half-open probe permission.
     * 3. **Recovery-after-success**: after that probe is recorded as a real
     *    success, the real gate must return to normal `Allowed` state.
     *
     * Must be called only after [openCircuitAndPersist] has already
     * persisted an `OPEN` record for [directoryPath] -- in the Simulator
     * proof app this is the app's *second* launch (post-relaunch);
     * [openCircuitAndPersist] itself only ever runs on the first. Throws
     * (via Kotlin `error`/`check`) if no such record exists, or if any of
     * the three decisions above does not hold -- this proof harness
     * intentionally does not swallow or tolerate an unexpected outcome,
     * matching this repository's "fail loudly rather than assume success"
     * testing discipline.
     *
     * Every clock reading used here is a fixed, synthetic instant computed
     * relative to the persisted `openUntil` deadline -- never the real
     * device wall clock -- so this is exact and reproducible regardless of
     * how long the genuine `xcrun simctl terminate`/relaunch cycle around it
     * took in real time. [openCircuitAndPersist] itself still uses the real
     * [AppleDataLoomClock] to decide when the circuit opened in the first
     * place; only the *re-drive* below needs a clock it fully controls.
     */
    public fun redriveGateAfterRelaunch(directoryPath: String): CircuitBreakerGateRedriveProofState = runBlocking {
        val store = AppleFileCircuitBreakerStateStore(directoryPath)
        val persisted = when (val result = store.load(scope)) {
            is ProviderOperationResult.Success -> when (val loaded = result.value) {
                is CircuitBreakerLoadResult.Found -> loaded.record.state
                CircuitBreakerLoadResult.Missing ->
                    error(
                        "No persisted circuit-breaker record found at $directoryPath to re-drive " +
                            "the gate against; openCircuitAndPersist must run first.",
                    )
            }
            is ProviderOperationResult.Failure ->
                error("AppleFileCircuitBreakerStateStore.load failed during gate re-drive: ${result.error}")
        }
        val openUntilMillis = checkNotNull(persisted.openUntil) {
            "Expected a persisted OPEN record with an openUntil deadline, but phase was " +
                "${persisted.phase}."
        }.epochMilliseconds

        val beforeDeadlinePermission = coordinator(FixedClock(openUntilMillis - 1L), store).acquire(scope)
        check(beforeDeadlinePermission is CircuitBreakerPermission.Rejected) {
            "Expected the relaunched process's real gate to still reject access one " +
                "millisecond before the persisted deadline, but got: $beforeDeadlinePermission"
        }

        val atDeadlinePermission = coordinator(FixedClock(openUntilMillis), store).acquire(scope)
        val grantedPermit = (atDeadlinePermission as? CircuitBreakerPermission.ProbeAllowed)?.permit
            ?: error(
                "Expected the relaunched process's real gate to grant a half-open probe " +
                    "exactly at the persisted deadline, but got: $atDeadlinePermission",
            )

        val probeSuccessAtMillis = openUntilMillis + PROBE_SUCCESS_OFFSET_MILLIS
        val recordResult = coordinator(FixedClock(probeSuccessAtMillis), store)
            .recordSuccess(scope, grantedPermit)
        check(recordResult is CircuitBreakerRecordResult.Recorded) {
            "Expected the relaunched process's real recordSuccess to succeed for the probe " +
                "granted at the deadline, but got: $recordResult"
        }

        val recoveryPermission = coordinator(
            FixedClock(probeSuccessAtMillis + RECOVERY_REVERIFY_OFFSET_MILLIS),
            store,
        ).acquire(scope)
        check(recoveryPermission is CircuitBreakerPermission.Allowed) {
            "Expected the relaunched process's real gate to return to normal ALLOWED state " +
                "after the probe's recorded success, but got: $recoveryPermission"
        }

        CircuitBreakerGateRedriveProofState(
            beforeDeadlineOutcome = outcomeName(beforeDeadlinePermission),
            beforeDeadlineRejectionReason = beforeDeadlinePermission.reason.name,
            probeAtDeadlineOutcome = outcomeName(atDeadlinePermission),
            probeGeneration = grantedPermit.generation,
            recoveryOutcome = outcomeName(recoveryPermission),
        )
    }

    private fun coordinator(
        clock: DataLoomClock,
        store: AppleFileCircuitBreakerStateStore,
    ): CircuitBreakerCoordinator = CircuitBreakerCoordinator(
        configuration = CircuitBreakerConfiguration(
            failureThreshold = 2,
            failureWindow = SchedulingDelay(5_000L),
            openDuration = SchedulingDelay(OPEN_DURATION_MILLIS),
            halfOpenProbeLeaseDuration = SchedulingDelay(HALF_OPEN_PROBE_LEASE_MILLIS),
        ),
        clock = clock,
        stateStore = store,
    )

    private fun outcomeName(permission: CircuitBreakerPermission): String = when (permission) {
        CircuitBreakerPermission.Allowed -> "ALLOWED"
        is CircuitBreakerPermission.ProbeAllowed -> "PROBE_ALLOWED"
        is CircuitBreakerPermission.Rejected -> "REJECTED"
        is CircuitBreakerPermission.PersistenceFailure -> "PERSISTENCE_FAILURE"
        CircuitBreakerPermission.ContentionLimitReached -> "CONTENTION_LIMIT"
    }

    /** Fixed, synthetic clock reading for the deterministic gate re-drive above. */
    private class FixedClock(private val epochMillis: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(epochMillis)
    }

    private fun ProviderOperationResult<CircuitBreakerCompareAndSetResult>.requireUpdated(
        step: String,
    ): CircuitBreakerCompareAndSetResult.Updated = when (this) {
        is ProviderOperationResult.Success -> when (val result = value) {
            is CircuitBreakerCompareAndSetResult.Updated -> result
            is CircuitBreakerCompareAndSetResult.Conflict ->
                error("Unexpected circuit-breaker compare-and-set conflict during $step: $result")
        }
        is ProviderOperationResult.Failure ->
            error("Circuit-breaker compare-and-set failed during $step: ${error}")
    }

    private fun CircuitBreakerStateRecord.toProofState(): ProcessTerminationProofState =
        ProcessTerminationProofState(
            phase = state.phase.name,
            consecutiveFailures = state.consecutiveFailures,
            openUntilEpochMillis = state.openUntil?.epochMilliseconds ?: -1L,
            probeGeneration = state.probeGeneration,
            version = version,
        )

    private const val OPEN_DURATION_MILLIS = 30_000L

    // Gate-redrive-only constants: the half-open probe lease given to
    // coordinator() during redriveGateAfterRelaunch (generous relative to
    // the tiny millisecond offsets below so neither offset can accidentally
    // land outside the lease window), and how far past each deadline the
    // probe-success/recovery-reverify readings are taken.
    private const val HALF_OPEN_PROBE_LEASE_MILLIS = 5_000L
    private const val PROBE_SUCCESS_OFFSET_MILLIS = 10L
    private const val RECOVERY_REVERIFY_OFFSET_MILLIS = 10L
}
