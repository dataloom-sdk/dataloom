@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processterminationproof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Pure Kotlin/Native sanity coverage for [AppleCircuitBreakerProcessTerminationProof],
 * exercised **within a single process** -- this does not, and cannot, prove
 * genuine OS-level process kill/relaunch survival by itself. It only confirms
 * the write/read logic this module hands to the real Simulator proof app is
 * correct before that app ever runs. See the Apple Simulator CI job
 * (`docs/apple/process-termination-proof.md`) for the actual process-kill
 * proof this module exists to support.
 *
 * As with every other `dataloom-runtime`/`dataloom-scheduler-bgtask` iosTest
 * suite in this repository, this compiles and klib-verifies from a Windows
 * host via `-Pdataloom.appleKlibCrossCompile=true`, but actually *running*
 * the compiled Kotlin/Native test binary requires a macOS host and has not
 * been performed from this environment.
 */
class AppleCircuitBreakerProcessTerminationProofTest {

    @Test
    fun `missing state reads back null`() = runTest {
        val directory = uniqueDirectory()
        assertNull(AppleCircuitBreakerProcessTerminationProof.readPersistedState(directory))
    }

    @Test
    fun `open circuit and persist writes an open record a fresh store instance can read back`() = runTest {
        val directory = uniqueDirectory()

        val written = AppleCircuitBreakerProcessTerminationProof.openCircuitAndPersist(directory)
        assertEquals("OPEN", written.phase)
        assertEquals(0, written.consecutiveFailures)
        assertEquals(0L, written.probeGeneration)
        assertEquals(1L, written.version)

        val reread = AppleCircuitBreakerProcessTerminationProof.readPersistedState(directory)
        assertEquals(written.phase, reread?.phase)
        assertEquals(written.consecutiveFailures, reread?.consecutiveFailures)
        assertEquals(written.openUntilEpochMillis, reread?.openUntilEpochMillis)
        assertEquals(written.probeGeneration, reread?.probeGeneration)
        assertEquals(written.version, reread?.version)
    }

    private fun uniqueDirectory(): String =
        NSTemporaryDirectory() + "dataloom-process-termination-proof-" + NSUUID().UUIDString()
}
