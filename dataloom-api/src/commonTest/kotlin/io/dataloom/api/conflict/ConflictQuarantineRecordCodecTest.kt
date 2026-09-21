package io.dataloom.api.conflict

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConflictQuarantineRecordCodecTest {

    private val codec = ConflictQuarantineRecordCodec()

    private val counting = ConflictQuarantineRecord(
        status = ConflictQuarantineStatus.COUNTING,
        occurrenceCount = 2,
        firstSeenAt = DataLoomInstant(1_000L),
        lastSeenAt = DataLoomInstant(2_000L),
        lastConflictId = ConflictId("conflict-1"),
        lastResolverId = ConflictResolverId("dataloom.builtin.server-wins"),
        quarantinedAt = null,
    )

    private val release = ConflictQuarantineRelease(
        commandId = ConflictAdministrationCommandId("cmd|with|pipes"),
        principalId = ConflictAdministrationPrincipalId("operator:ü"),
        authorizationId = ConflictAdministrationAuthorizationId("auth-1"),
        reason = ConflictAdministrationReason("fixed upstream; verified | ok"),
        releasedAt = DataLoomInstant(5_000L),
    )

    @Test
    fun roundTripsACountingRecord() {
        assertEquals(counting, codec.decode(codec.encode(counting)))
    }

    @Test
    fun roundTripsAQuarantinedRecordWithNoResolver() {
        val record = counting.copy(
            status = ConflictQuarantineStatus.QUARANTINED,
            occurrenceCount = 5,
            lastResolverId = null,
            quarantinedAt = DataLoomInstant(3_000L),
        )
        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsReleaseEvidenceWithSeparatorsAndNonAsciiInValues() {
        val record = counting.copy(occurrenceCount = 0, releaseCount = 3, lastRelease = release)
        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun encodingIsDeterministic() {
        assertEquals(codec.encode(counting), codec.encode(counting.copy()))
    }

    @Test
    fun encodedFormContainsNoRawIdentifiers() {
        val encoded = codec.encode(counting.copy(occurrenceCount = 0, releaseCount = 1, lastRelease = release))
        // Identifiers are hex-encoded, so a raw separator inside a value cannot shift a field.
        assertEquals(15, encoded.split('|').size)
    }

    @Test
    fun malformedPayloadsAreRejected() {
        val valid = codec.encode(counting)
        val cases = listOf(
            "",
            "garbage",
            valid.replace("DATALOOM_CONFLICT_QUARANTINE_RECORD", "OTHER_HEADER"),
            valid.replaceFirst("|1|", "|2|"),
            valid.replace("COUNTING", "UNKNOWN_STATUS"),
            valid + "|extra",
            valid.replace("|2|1000|", "|not-a-number|1000|"),
            // Release fields must be all present or all absent.
            codec.encode(counting.copy(occurrenceCount = 0, releaseCount = 1, lastRelease = release))
                .split('|').toMutableList().also { it[12] = "-" }.joinToString("|"),
            // Record invariants are re-checked on decode: QUARANTINED needs quarantinedAt.
            valid.replace("COUNTING", "QUARANTINED"),
        )
        for (payload in cases) {
            assertFailsWith<IllegalArgumentException>(payload) { codec.decode(payload) }
        }
    }

    @Test
    fun oversizedRecordsAreRejectedOnEncode() {
        val huge = counting.copy(
            occurrenceCount = 0,
            releaseCount = 1,
            lastRelease = release.copy(reason = ConflictAdministrationReason("x".repeat(512))),
            lastConflictId = ConflictId("y".repeat(40_000)),
        )
        assertFailsWith<IllegalArgumentException> { codec.encode(huge) }
    }
}
