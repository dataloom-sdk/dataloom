package io.dataloom.api.conflict

import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnresolvedConflictRecordCodecTest {

    private val codec = UnresolvedConflictRecordCodec()

    @Test
    fun roundTripsARecordWithNoEntityVersionAndEmptyMetadata() {
        val record = UnresolvedConflictRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("note"), EntityId("note-1")),
            localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.DELETE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
            committedAt = DataLoomInstant(1_000L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsAnEntityVersionAndMetadataOnEveryLevel() {
        val record = UnresolvedConflictRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("note"), EntityId("note-1"), EntityVersion("v7")),
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("local-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.of(mapOf("source" to "mobile")),
            ),
            remoteChange = UnresolvedConflictChangeSummary(
                ChangeEventId("remote-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.of(mapOf("source" to "web", "region" to "eu")),
            ),
            conflictMetadata = DataLoomMetadata.of(mapOf("detectorId" to "d-1")),
            reason = UnresolvedConflictReason.RESOLVER_NOT_FOUND,
            committedAt = DataLoomInstant(42_000L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsValuesContainingSeparatorCharacters() {
        val record = UnresolvedConflictRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("no|te"), EntityId("id;1")),
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("l:1"),
                ChangeOperation.CREATE,
                DataLoomMetadata.of(mapOf("a|b" to "c:d;e")),
            ),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("r|1"), ChangeOperation.CREATE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
            committedAt = DataLoomInstant(1L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun decodeRejectsAnUnrecognizedHeader() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("NOT_AN_UNRESOLVED_CONFLICT_RECORD|1")
        }
    }

    @Test
    fun decodeRejectsAWrongFieldCount() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("DATALOOM_UNRESOLVED_CONFLICT_RECORD|1|CONCURRENT_CHANGE")
        }
    }

    @Test
    fun encodeRejectsAPayloadBeyondTheBoundedLimit() {
        val record = UnresolvedConflictRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("note"), EntityId("note-1")),
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("local-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.of(mapOf("k" to "x".repeat(200_000))),
            ),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
            committedAt = DataLoomInstant(1L),
        )
        assertFailsWith<IllegalArgumentException> {
            codec.encode(record)
        }
    }
}
