package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.governance.rbac.PrincipalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Verifies [AuditChainState]'s construction-time structural invariants: the
 * same "does this record extend the previous one" check
 * [InMemoryAuditStore.append] performs one record at a time, applied here to
 * the whole persisted list at once, plus the bounded record-count safety
 * limit that stands in for retention (see [AuditChainState]'s own
 * documentation for why there is no retention/pruning).
 */
class AuditChainStateTest {

    private fun event(n: Int) = AuditEvent(
        tenantId = TenantId("tenant-one"),
        principalId = PrincipalId("alice"),
        eventType = AuditEventType("access.denied"),
        details = DataLoomMetadata.of(mapOf("n" to n.toString())),
    )

    private fun mac(fill: Int): DataLoomMac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { fill.toByte() })

    private fun record(sequence: Long): AuditRecord = AuditRecord(
        sequence = sequence,
        recordedAt = DataLoomInstant(sequence),
        previousMac = if (sequence == 0L) null else mac(sequence.toInt() - 1),
        event = event(sequence.toInt()),
        mac = mac(sequence.toInt()),
    )

    @Test
    fun theEmptyChainHasNoHead() {
        assertNull(AuditChainState.Empty.head)
        assertEquals(emptyList(), AuditChainState.Empty.records)
    }

    @Test
    fun aWellFormedChainConstructsAndReportsItsHead() {
        val state = AuditChainState((0L until 5L).map { record(it) })
        assertEquals(record(4), state.head)
    }

    @Test
    fun aGapInSequenceIsRejected() {
        assertFailsWith<IllegalArgumentException> { AuditChainState(listOf(record(0), record(2))) }
    }

    @Test
    fun startingAtANonZeroSequenceIsRejected() {
        assertFailsWith<IllegalArgumentException> { AuditChainState(listOf(record(1))) }
    }

    @Test
    fun aBrokenPreviousMacLinkIsRejected() {
        val broken = record(1).copy(previousMac = mac(999))
        assertFailsWith<IllegalArgumentException> { AuditChainState(listOf(record(0), broken)) }
    }

    @Test
    fun duplicatedSequencesAreRejected() {
        assertFailsWith<IllegalArgumentException> { AuditChainState(listOf(record(0), record(0))) }
    }

    @Test
    fun reorderedRecordsAreRejected() {
        assertFailsWith<IllegalArgumentException> { AuditChainState(listOf(record(1), record(0))) }
    }

    @Test
    fun exactlyTheMaximumRecordCountIsAccepted() {
        val state = AuditChainState((0L until AuditChainState.MAX_RECORD_COUNT.toLong()).map { record(it) })
        assertEquals(AuditChainState.MAX_RECORD_COUNT, state.records.size)
    }

    @Test
    fun exceedingTheMaximumRecordCountIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AuditChainState((0L..AuditChainState.MAX_RECORD_COUNT.toLong()).map { record(it) })
        }
    }
}
