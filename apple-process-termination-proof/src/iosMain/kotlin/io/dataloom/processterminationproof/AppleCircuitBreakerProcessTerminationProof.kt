package io.dataloom.processterminationproof

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.AppleFileCircuitBreakerStateStore
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
}
