@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processterminationproof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Pure Kotlin/Native sanity coverage for [AppleRetryBudgetProcessTerminationProof],
 * exercised **within a single process** -- this does not, and cannot, prove
 * genuine OS-level process kill/relaunch survival by itself. It only confirms
 * the write/read logic this module hands to the real Simulator proof app is
 * correct before that app ever runs, the same role
 * `AppleCircuitBreakerProcessTerminationProofTest` plays for the
 * circuit-breaker proof. See the Apple Simulator CI job
 * (`docs/apple/process-termination-proof.md`) for the actual process-kill
 * proof this module exists to support.
 *
 * As with every other `dataloom-runtime`/`dataloom-scheduler-bgtask` iosTest
 * suite in this repository, this compiles and klib-verifies from a Windows
 * host via `-Pdataloom.appleKlibCrossCompile=true`, but actually *running*
 * the compiled Kotlin/Native test binary requires a macOS host and has not
 * been performed from this environment.
 */
class AppleRetryBudgetProcessTerminationProofTest {

    @Test
    fun `missing state reports no persisted retry budget`() = runTest {
        val directory = uniqueDirectory()
        assertFalse(AppleRetryBudgetProcessTerminationProof.hasPersistedRetryBudgetState(directory))
    }

    @Test
    fun `write retry budget and persist writes retry state a fresh provider instance confirms`() = runTest {
        val directory = uniqueDirectory()

        val written = AppleRetryBudgetProcessTerminationProof.writeRetryBudgetAndPersist(directory)
        assertEquals(3, written.retryAttemptNumber)
        assertEquals(1_100L, written.retryWindowStartedAtEpochMillis)
        assertEquals(1_200L, written.retryLastEvaluatedAtEpochMillis)
        assertEquals(750L, written.retryCumulativeDelayMillis)
        assertEquals(1_300L, written.availableAtEpochMillis)

        assertTrue(AppleRetryBudgetProcessTerminationProof.hasPersistedRetryBudgetState(directory))
    }

    @Test
    fun `redrive acquire gate after relaunch re-drives the real gate against the persisted entry`() = runTest {
        val directory = uniqueDirectory()
        val written = AppleRetryBudgetProcessTerminationProof.writeRetryBudgetAndPersist(directory)

        val redriven = AppleRetryBudgetProcessTerminationProof.redriveAcquireGateAfterRelaunch(directory)

        assertEquals(written.retryAttemptNumber, redriven.retryAttemptNumber)
        assertEquals(written.retryWindowStartedAtEpochMillis, redriven.retryWindowStartedAtEpochMillis)
        assertEquals(written.retryLastEvaluatedAtEpochMillis, redriven.retryLastEvaluatedAtEpochMillis)
        assertEquals(written.retryCumulativeDelayMillis, redriven.retryCumulativeDelayMillis)
        assertEquals(
            written.availableAtEpochMillis,
            redriven.availableAtEpochMillis,
            "redriveAcquireGateAfterRelaunch must re-drive the real acquire gate against the " +
                "exact availableAt writeRetryBudgetAndPersist persisted.",
        )
    }

    private fun uniqueDirectory(): String =
        NSTemporaryDirectory() + "dataloom-retry-budget-process-termination-proof-" + NSUUID().UUIDString()
}
