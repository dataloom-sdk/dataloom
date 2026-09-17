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
 * [AppleUnresolvedConflictLogContentionProof], exercised **within a single
 * process, sequentially, with the "go" signal already present** -- this
 * does not, and cannot, prove genuine cross-process contention by itself. It
 * only confirms the warm-up/wait/record logic the two real Simulator proof
 * apps rely on is correct before either app ever runs, mirroring
 * [AppleCircuitBreakerProbeContentionProofTest]'s own precedent and its
 * documented single-process boundary exactly.
 *
 * As with every other `apple-process-contention-proof`/`dataloom-runtime`
 * iosTest suite in this repository, this compiles and klib-verifies from a
 * Windows host via `-Pdataloom.appleKlibCrossCompile=true`, but actually
 * *running* the compiled Kotlin/Native test binary requires a macOS host and
 * has not been performed from this environment.
 *
 * Deliberately uses [runBlocking], never `kotlinx.coroutines.test.runTest`:
 * per [AppleCircuitBreakerProbeContentionProofTest]'s own documented, real,
 * previously-confirmed regression, `runTest`'s `TestDispatcher` auto-skips
 * `delay()` calls in virtual time. This suite has no real-wall-clock
 * duration of its own to wait past (unlike the circuit breaker's 400ms
 * `openDuration`), but `waitForGoSignalThenAttemptRecord`'s internal poll
 * loop still calls real `kotlinx.coroutines.delay` between attempts, so
 * `runBlocking` is used throughout for the same avoid-the-known-gotcha
 * consistency the circuit-breaker suite already establishes.
 */
class AppleUnresolvedConflictLogContentionProofTest {

    @Test
    fun `warming up then racing alone records the conflict`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u) // 0700 octal
        val readyMarker = "$directory/ready.marker"
        val goSignal = "$directory/go.marker"

        AppleUnresolvedConflictLogContentionProof.warmUpAndSignalReady(directory, readyMarker)
        assertEquals(0, access(readyMarker, F_OK), "warmUpAndSignalReady must create the ready marker.")

        touchFile(goSignal)
        val result = AppleUnresolvedConflictLogContentionProof.waitForGoSignalThenAttemptRecord(
            directoryPath = directory,
            goSignalPath = goSignal,
        )
        assertEquals("RECORDED", result.outcome)
        assertEquals(0L, result.persistedVersion)
    }

    @Test
    fun `a second attempt against an already-recorded conflict observes AlreadyRecorded`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u)
        val goSignal = "$directory/go.marker"
        touchFile(goSignal)

        val first = AppleUnresolvedConflictLogContentionProof.waitForGoSignalThenAttemptRecord(directory, goSignal)
        assertEquals("RECORDED", first.outcome)

        val second = AppleUnresolvedConflictLogContentionProof.waitForGoSignalThenAttemptRecord(directory, goSignal)
        assertEquals("ALREADY_RECORDED", second.outcome)
        assertEquals(0L, second.persistedVersion, "Exactly one insert, zero compare-and-set overwrites.")
    }

    @Test
    fun `waiting for a go signal that never appears times out rather than hanging`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u)
        val goSignal = "$directory/never-appears.marker"

        val result = AppleUnresolvedConflictLogContentionProof.waitForGoSignalThenAttemptRecord(
            directoryPath = directory,
            goSignalPath = goSignal,
            maxPollAttempts = 5,
        )
        assertEquals("TIMED_OUT_WAITING_FOR_GO_SIGNAL", result.outcome)
        assertEquals(-1L, result.persistedVersion)
    }

    private fun touchFile(path: String) {
        val descriptor = open(path, O_WRONLY or O_CREAT, 384) // 0600 octal
        check(descriptor >= 0)
        close(descriptor)
    }

    private fun uniqueDirectory(): String =
        NSTemporaryDirectory() + "dataloom-unresolved-conflict-contention-proof-" + NSUUID().UUIDString()
}
