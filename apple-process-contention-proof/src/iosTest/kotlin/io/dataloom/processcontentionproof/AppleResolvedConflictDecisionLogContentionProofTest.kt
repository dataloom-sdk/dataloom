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
 * [AppleResolvedConflictDecisionLogContentionProof], the resolved-decision
 * twin of [AppleUnresolvedConflictLogContentionProofTest] -- see that
 * class's own doc for the full "single process, sequentially, go signal
 * already present" boundary and the `runBlocking`-not-`runTest` reasoning,
 * both identical here.
 */
class AppleResolvedConflictDecisionLogContentionProofTest {

    @Test
    fun `warming up then racing alone records the decision`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u) // 0700 octal
        val readyMarker = "$directory/ready.marker"
        val goSignal = "$directory/go.marker"

        AppleResolvedConflictDecisionLogContentionProof.warmUpAndSignalReady(directory, readyMarker)
        assertEquals(0, access(readyMarker, F_OK), "warmUpAndSignalReady must create the ready marker.")

        touchFile(goSignal)
        val result = AppleResolvedConflictDecisionLogContentionProof.waitForGoSignalThenAttemptRecord(
            directoryPath = directory,
            goSignalPath = goSignal,
        )
        assertEquals("RECORDED", result.outcome)
        assertEquals(0L, result.persistedVersion)
    }

    @Test
    fun `a second attempt against an already-recorded decision observes AlreadyRecorded`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u)
        val goSignal = "$directory/go.marker"
        touchFile(goSignal)

        val first = AppleResolvedConflictDecisionLogContentionProof.waitForGoSignalThenAttemptRecord(
            directory,
            goSignal,
        )
        assertEquals("RECORDED", first.outcome)

        val second = AppleResolvedConflictDecisionLogContentionProof.waitForGoSignalThenAttemptRecord(
            directory,
            goSignal,
        )
        assertEquals("ALREADY_RECORDED", second.outcome)
        assertEquals(0L, second.persistedVersion, "Exactly one insert, zero compare-and-set overwrites.")
    }

    @Test
    fun `waiting for a go signal that never appears times out rather than hanging`() = runBlocking {
        val directory = uniqueDirectory()
        mkdir(directory, 448u)
        val goSignal = "$directory/never-appears.marker"

        val result = AppleResolvedConflictDecisionLogContentionProof.waitForGoSignalThenAttemptRecord(
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
        NSTemporaryDirectory() + "dataloom-resolved-conflict-decision-contention-proof-" + NSUUID().UUIDString()
}
