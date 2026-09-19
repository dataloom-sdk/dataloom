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
 * [AppleResolvedConflictDecisionLogProcessTerminationProof], exercised
 * **within a single process** -- the resolved-decision-log twin of
 * [AppleUnresolvedConflictLogProcessTerminationProofTest]. See that test
 * class's own KDoc for the full "why this cannot itself prove process-kill
 * survival" reasoning, identical here.
 */
class AppleResolvedConflictDecisionLogProcessTerminationProofTest {

    @Test
    fun `missing state reports null`() = runTest {
        val directory = uniqueDirectory()
        assertNull(AppleResolvedConflictDecisionLogProcessTerminationProof.readPersistedState(directory))
    }

    @Test
    fun `record and persist writes state a fresh store instance confirms`() = runTest {
        val directory = uniqueDirectory()

        val written = AppleResolvedConflictDecisionLogProcessTerminationProof.recordAndPersist(directory)
        assertEquals("CONCURRENT_CHANGE", written.conflictType)
        assertEquals("note", written.entityType)
        assertEquals("note-apple-termination-proof-resolved-1", written.entityId)
        assertEquals("dataloom.builtin.server-wins", written.resolverId)
        assertEquals("USE_REMOTE", written.decisionKind)
        assertEquals(20_000L, written.committedAtEpochMillis)
        assertEquals(0L, written.version)

        val read = AppleResolvedConflictDecisionLogProcessTerminationProof.readPersistedState(directory)
        assertEquals(written, read)
    }

    @Test
    fun `a second recordAndPersist call against the same directory fails loudly`() = runTest {
        val directory = uniqueDirectory()
        AppleResolvedConflictDecisionLogProcessTerminationProof.recordAndPersist(directory)

        assertFailsWith<IllegalStateException> {
            AppleResolvedConflictDecisionLogProcessTerminationProof.recordAndPersist(directory)
        }
    }

    private fun uniqueDirectory(): String =
        NSTemporaryDirectory() + "dataloom-resolved-conflict-decision-termination-proof-" + NSUUID().UUIDString()
}
