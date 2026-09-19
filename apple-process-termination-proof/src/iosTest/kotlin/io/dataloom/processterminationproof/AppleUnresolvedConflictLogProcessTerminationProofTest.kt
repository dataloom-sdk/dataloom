@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processterminationproof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Pure Kotlin/Native sanity coverage for
 * [AppleUnresolvedConflictLogProcessTerminationProof], exercised **within a
 * single process** -- this does not, and cannot, prove genuine OS-level
 * process kill/relaunch survival by itself. It only confirms the write/read
 * logic this module hands to the real Simulator proof app is correct before
 * that app ever runs, the same role
 * `AppleCircuitBreakerProcessTerminationProofTest`/
 * `AppleRetryBudgetProcessTerminationProofTest` play for their own domains.
 * See the Apple Simulator CI job (`docs/apple/process-termination-proof.md`)
 * for the actual process-kill proof this module exists to support.
 *
 * As with every other `dataloom-runtime`/`apple-process-termination-proof`
 * iosTest suite in this repository, this compiles and klib-verifies from a
 * Windows host via `-Pdataloom.appleKlibCrossCompile=true`, but actually
 * *running* the compiled Kotlin/Native test binary requires a macOS host and
 * has not been performed from this environment.
 */
class AppleUnresolvedConflictLogProcessTerminationProofTest {

    @Test
    fun `missing state reports null`() = runTest {
        val directory = uniqueDirectory()
        assertNull(AppleUnresolvedConflictLogProcessTerminationProof.readPersistedState(directory))
    }

    @Test
    fun `record and persist writes state a fresh store instance confirms`() = runTest {
        val directory = uniqueDirectory()

        val written = AppleUnresolvedConflictLogProcessTerminationProof.recordAndPersist(directory)
        assertEquals("CONCURRENT_CHANGE", written.conflictType)
        assertEquals("note", written.entityType)
        assertEquals("note-apple-termination-proof-1", written.entityId)
        assertEquals("RESOLVER_NOT_CONFIGURED", written.reason)
        assertEquals(20_000L, written.committedAtEpochMillis)
        assertEquals(0L, written.version)

        val read = AppleUnresolvedConflictLogProcessTerminationProof.readPersistedState(directory)
        assertEquals(written, read)
    }

    @Test
    fun `a second recordAndPersist call against the same directory fails loudly`() = runTest {
        val directory = uniqueDirectory()
        AppleUnresolvedConflictLogProcessTerminationProof.recordAndPersist(directory)

        assertFailsWith<IllegalStateException> {
            AppleUnresolvedConflictLogProcessTerminationProof.recordAndPersist(directory)
        }
    }

    private fun uniqueDirectory(): String =
        NSTemporaryDirectory() + "dataloom-unresolved-conflict-termination-proof-" + NSUUID().UUIDString()
}
