@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processcontentionproof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.access
import platform.posix.close
import platform.posix.mkdir
import platform.posix.open

/**
 * Pure Kotlin/Native sanity coverage for
 * [AppleRetryBudgetLeaseContentionProof], exercised **within a single
 * process, sequentially, with the "go" signal already present** -- this
 * does not, and cannot, prove genuine cross-process contention by itself.
 * It only confirms the seed/wait/acquire logic the two real Simulator proof
 * apps rely on is correct before either app ever runs, matching
 * [AppleCircuitBreakerProbeContentionProofTest]'s own documented scope and
 * limitation exactly (see that class's own KDoc, not repeated here).
 *
 * As with every other `dataloom-runtime`/`apple-process-termination-proof`/
 * `apple-process-contention-proof` iosTest suite in this repository, this
 * compiles and klib-verifies from a Windows host via
 * `-Pdataloom.appleKlibCrossCompile=true`, but actually *running* the
 * compiled Kotlin/Native test binary requires a macOS host and has not been
 * performed from this environment.
 *
 * Deliberately uses [runBlocking], never `kotlinx.coroutines.test.runTest`,
 * matching [AppleCircuitBreakerProbeContentionProofTest]'s own documented
 * reasoning -- although this particular suite's own `acquire` calls do not
 * depend on real wall-clock elapsed time the way the circuit breaker's
 * `openDuration` does (every timestamp here is a fixed logical
 * [io.dataloom.api.time.DataLoomInstant], never `AppleDataLoomClock.now()`),
 * `runBlocking` is used uniformly across this module's suites rather than
 * special-cased per file.
 */
class AppleRetryBudgetLeaseContentionProofTest {

    @Test
    fun `seeding retry-budget state then immediately racing alone wins the lease`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u) // 0700 octal
        val readyMarker = "$directory/ready.marker"
        val goSignal = "$directory/go.marker"

        AppleRetryBudgetLeaseContentionProof.seedRetryWaitingEntryAndSignalReady(directory, readyMarker)
        assertEquals(0, access(readyMarker, F_OK), "seedRetryWaitingEntryAndSignalReady must create the ready marker.")
        touchFile(goSignal)

        val result = AppleRetryBudgetLeaseContentionProof.waitForGoSignalThenAttemptAcquire(
            directoryPath = directory,
            goSignalPath = goSignal,
            racerLeaseId = "lease-race-solo",
            racerConsumerId = "consumer-race-solo",
        )
        assertEquals("ACQUIRED", result.outcome)
        assertEquals(2, result.retryAttemptNumber)
        assertEquals(1_100L, result.retryWindowStartedAtEpochMillis)
        assertEquals(1_200L, result.retryLastEvaluatedAtEpochMillis)
        assertEquals(600L, result.retryCumulativeDelayMillis)
    }

    @Test
    fun `a second attempt against an already-leased entry finds nothing eligible`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u)
        val readyMarker = "$directory/ready.marker"
        val goSignal = "$directory/go.marker"

        AppleRetryBudgetLeaseContentionProof.seedRetryWaitingEntryAndSignalReady(directory, readyMarker)
        touchFile(goSignal)

        val first = AppleRetryBudgetLeaseContentionProof.waitForGoSignalThenAttemptAcquire(
            directoryPath = directory,
            goSignalPath = goSignal,
            racerLeaseId = "lease-race-a",
            racerConsumerId = "consumer-race-a",
        )
        assertEquals("ACQUIRED", first.outcome)

        val second = AppleRetryBudgetLeaseContentionProof.waitForGoSignalThenAttemptAcquire(
            directoryPath = directory,
            goSignalPath = goSignal,
            racerLeaseId = "lease-race-b",
            racerConsumerId = "consumer-race-b",
        )
        assertEquals("NO_ELIGIBLE_ENTRY", second.outcome)
        assertEquals(-1, second.retryAttemptNumber)
    }

    @Test
    fun `waiting for a go signal that never appears times out rather than hanging`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u)
        val goSignal = "$directory/never-appears.marker"

        val result = AppleRetryBudgetLeaseContentionProof.waitForGoSignalThenAttemptAcquire(
            directoryPath = directory,
            goSignalPath = goSignal,
            racerLeaseId = "lease-race-timeout",
            racerConsumerId = "consumer-race-timeout",
            maxPollAttempts = 5,
        )
        assertEquals("TIMED_OUT_WAITING_FOR_GO_SIGNAL", result.outcome)
    }

    private fun touchFile(path: String) {
        val descriptor = open(path, O_WRONLY or O_CREAT, 384) // 0600 octal
        check(descriptor >= 0)
        close(descriptor)
    }

    private fun uniqueDirectory(): String =
        NSTemporaryDirectory() + "dataloom-retry-budget-contention-proof-" + NSUUID().UUIDString()
}
