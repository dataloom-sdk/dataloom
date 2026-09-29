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

/**
 * Round-trips [AuditChainState] through [AuditChainStateCodec] and verifies
 * malformed/oversized payloads fail closed, matching this codebase's other
 * [io.dataloom.api.state.DurableStateCodec] test conventions (see
 * [io.dataloom.api.conflict.UnresolvedConflictRecordCodec]'s test suite for the
 * sibling pattern).
 */
class AuditChainStateCodecTest {

    private val codec = AuditChainStateCodec()

    private fun mac(fill: Int): DataLoomMac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { fill.toByte() })

    private fun record(
        sequence: Long,
        details: DataLoomMetadata = DataLoomMetadata.Empty,
    ): AuditRecord = AuditRecord(
        sequence = sequence,
        recordedAt = DataLoomInstant(1_000L + sequence),
        previousMac = if (sequence == 0L) null else mac(sequence.toInt() - 1),
        event = AuditEvent(
            tenantId = TenantId("tenant-one"),
            principalId = PrincipalId("alice"),
            eventType = AuditEventType("access.denied"),
            details = details,
        ),
        mac = mac(sequence.toInt()),
    )

    @Test
    fun anEmptyChainRoundTrips() {
        assertEquals(AuditChainState.Empty, codec.decode(codec.encode(AuditChainState.Empty)))
    }

    @Test
    fun aChainWithRecordsRoundTrips() {
        val state = AuditChainState((0L until 5L).map { record(it) })
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun detailsMetadataRoundTrips() {
        val state = AuditChainState(
            listOf(record(0, DataLoomMetadata.of(mapOf("reason" to "manual review", "n" to "1")))),
        )
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun tenantAndPrincipalIdentifiersContainingSeparatorCharactersRoundTrip() {
        val event = AuditEvent(
            tenantId = TenantId("tenant|with,odd:chars;here"),
            principalId = PrincipalId("alice|bob,carol:dave;eve"),
            eventType = AuditEventType("access.denied"),
        )
        val state = AuditChainState(listOf(AuditRecord(0L, DataLoomInstant(1L), null, event, mac(0))))
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun decodingRejectsAWrongHeader() {
        assertFailsWith<IllegalArgumentException> { codec.decode("NOT_THE_RIGHT_HEADER|1|0") }
    }

    @Test
    fun decodingRejectsAnUnsupportedFormatVersion() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("DATALOOM_GOVERNANCE_DURABLE_AUDIT_CHAIN|99|0")
        }
    }

    @Test
    fun decodingRejectsARecordCountThatDoesNotMatchTheFieldCount() {
        val encoded = codec.encode(AuditChainState(listOf(record(0))))
        val withWrongCount = encoded.replaceFirst("|1|1|", "|1|2|")
        assertFailsWith<IllegalArgumentException> { codec.decode(withWrongCount) }
    }

    @Test
    fun decodingRejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { codec.decode("") }
        assertFailsWith<IllegalArgumentException> { codec.decode("not even close") }
    }

    @Test
    fun decodingRejectsARecordCountAboveTheBoundedLimit() {
        val encoded = "DATALOOM_GOVERNANCE_DURABLE_AUDIT_CHAIN|1|${AuditChainState.MAX_RECORD_COUNT + 1}"
        assertFailsWith<IllegalArgumentException> { codec.decode(encoded) }
    }
}
