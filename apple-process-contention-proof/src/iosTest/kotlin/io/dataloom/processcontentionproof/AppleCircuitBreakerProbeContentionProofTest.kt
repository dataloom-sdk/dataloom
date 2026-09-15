@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processcontentionproof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
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
 * [AppleCircuitBreakerProbeContentionProof], exercised **within a single
 * process, sequentially, with the "go" signal already present** -- this
 * does not, and cannot, prove genuine cross-process contention by itself.
 * It only confirms the setup/wait/acquire logic the two real Simulator
 * proof apps rely on is correct before either app ever runs. See the Apple
 * Simulator CI job (`docs/apple/process-contention-proof.md`) for the
 * actual two-process contention proof this module exists to support.
 *
 * As with every other `dataloom-runtime`/`apple-process-termination-proof`
 * iosTest suite in this repository, this compiles and klib-verifies from a
 * Windows host via `-Pdataloom.appleKlibCrossCompile=true`, but actually
 * *running* the compiled Kotlin/Native test binary requires a macOS host
 * and has not been performed from this environment.
 */
class AppleCircuitBreakerProbeContentionProofTest {

    @Test
    fun `opening the circuit then immediately racing alone wins the probe`() = runTest {
        val directory = uniqueDirectory()
        mkdir(directory, 448u) // 0700 octal
        val readyMarker = "$directory/ready.marker"
        val goSignal = "$directory/go.marker"

        AppleCircuitBreakerProbeContentionProof.openCircuitAndSignalReady(directory, readyMarker)
        assertEquals(0, access(readyMarker, F_OK), "openCircuitAndSignalReady must create the ready marker.")

        // Sleep past the 400ms openDuration before the lone "racer" attempts
        // the probe, mirroring the real CI job's own real wall-clock sleep.
        kotlinx.coroutines.delay(700L)
        touchFile(goSignal)

        val result = AppleCircuitBreakerProbeContentionProof.waitForGoSignalThenAttemptProbe(
            directoryPath = directory,
            goSignalPath = goSignal,
        )
        assertEquals("ALLOWED", result.outcome)
        assertEquals(1L, result.probeGeneration)
    }

    @Test
    fun `a second attempt against an already in-flight probe is rejected`() = runTest {
        val directory = uniqueDirectory()
        mkdir(directory, 448u)
        val readyMarker = "$directory/ready.marker"
        val goSignal = "$directory/go.marker"

        AppleCircuitBreakerProbeContentionProof.openCircuitAndSignalReady(directory, readyMarker)
        kotlinx.coroutines.delay(700L)
        touchFile(goSignal)

        val first = AppleCircuitBreakerProbeContentionProof.waitForGoSignalThenAttemptProbe(directory, goSignal)
        assertEquals("ALLOWED", first.outcome)

        val second = AppleCircuitBreakerProbeContentionProof.waitForGoSignalThenAttemptProbe(directory, goSignal)
        assertEquals("REJECTED", second.outcome)
        assertEquals("PROBE_IN_FLIGHT", second.rejectionReason)
    }

    @Test
    fun `waiting for a go signal that never appears times out rather than hanging`() = runTest {
        val directory = uniqueDirectory()
        mkdir(directory, 448u)
        val goSignal = "$directory/never-appears.marker"

        val result = AppleCircuitBreakerProbeContentionProof.waitForGoSignalThenAttemptProbe(
            directoryPath = directory,
            goSignalPath = goSignal,
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
        NSTemporaryDirectory() + "dataloom-process-contention-proof-" + NSUUID().UUIDString()
}
