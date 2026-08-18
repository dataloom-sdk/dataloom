package io.dataloom.api.conflict

import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolvedConflictDecisionRecordCodecTest {

    private val codec = ResolvedConflictDecisionRecordCodec()

    private val entity = EntityReference(EntityType("note"), EntityId("note-1"))

    @Test
    fun roundTripsAUseRemoteRecordWithNoEntityVersionAndEmptyMetadata() {
        val record = ResolvedConflictDecisionRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = entity,
            localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.DELETE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            resolverId = ConflictResolverId("resolver-1"),
            decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
            decisionMetadata = DataLoomMetadata.Empty,
            committedAt = DataLoomInstant(1_000L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsAnEntityVersionAndMetadataOnEveryLevel() {
        val record = ResolvedConflictDecisionRecord(
            conflictType = ConflictType.VERSION_MISMATCH,
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
            resolverId = ConflictResolverId("resolver-2"),
            decisionKind = ResolvedConflictDecisionKind.USE_LOCAL,
            decisionMetadata = DataLoomMetadata.of(mapOf("policy" to "manual-override")),
            committedAt = DataLoomInstant(42_000L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsAMergeDecisionWithAMergedChangeSummary() {
        val record = ResolvedConflictDecisionRecord(
            conflictType = ConflictType.CUSTOM,
            entity = entity,
            localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            resolverId = ConflictResolverId("resolver-3"),
            decisionKind = ResolvedConflictDecisionKind.MERGE,
            decisionMetadata = DataLoomMetadata.Empty,
            mergedChange = UnresolvedConflictChangeSummary(
                ChangeEventId("merged-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.of(mapOf("merge-strategy" to "field-level")),
            ),
            committedAt = DataLoomInstant(7_000L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsAFailDecisionWithABoundedErrorCode() {
        val record = ResolvedConflictDecisionRecord(
            conflictType = ConflictType.CREATE_COLLISION,
            entity = entity,
            localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.CREATE, DataLoomMetadata.Empty),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.CREATE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            resolverId = ConflictResolverId("resolver-4"),
            decisionKind = ResolvedConflictDecisionKind.FAIL,
            decisionMetadata = DataLoomMetadata.Empty,
            failureErrorCode = "POLICY_REJECTED",
            committedAt = DataLoomInstant(9_000L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsValuesContainingSeparatorCharacters() {
        val record = ResolvedConflictDecisionRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("no|te"), EntityId("id;1")),
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("l:1"),
                ChangeOperation.CREATE,
                DataLoomMetadata.of(mapOf("a|b" to "c:d;e")),
            ),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("r|1"), ChangeOperation.CREATE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            resolverId = ConflictResolverId("res|olver"),
            decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
            decisionMetadata = DataLoomMetadata.Empty,
            committedAt = DataLoomInstant(1L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun decodeRejectsAnUnrecognizedHeader() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("NOT_A_RESOLVED_CONFLICT_DECISION_RECORD|1")
        }
    }

    @Test
    fun decodeRejectsAWrongFieldCount() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("DATALOOM_RESOLVED_CONFLICT_DECISION_RECORD|1|CONCURRENT_CHANGE")
        }
    }

    @Test
    fun encodeRejectsAPayloadBeyondTheBoundedLimit() {
        val record = ResolvedConflictDecisionRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = entity,
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("local-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.of(mapOf("k" to "x".repeat(200_000))),
            ),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            resolverId = ConflictResolverId("resolver-1"),
            decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
            decisionMetadata = DataLoomMetadata.Empty,
            committedAt = DataLoomInstant(1L),
        )
        assertFailsWith<IllegalArgumentException> {
            codec.encode(record)
        }
    }
}
