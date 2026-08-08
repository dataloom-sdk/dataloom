package io.dataloom.storage.room.internal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.synchronization.ChangeSetAcknowledgement

@Dao
internal abstract class OutboundChangeDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertChangeSetEntity(entity: OutboundChangeSetEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEventEntities(entities: List<OutboundChangeEventEntity>)

    @Query(
        """
        SELECT storage_sequence, change_set_id, metadata_json
        FROM outbound_change_sets
        WHERE EXISTS (
            SELECT 1
            FROM outbound_change_events
            WHERE change_set_id = outbound_change_sets.change_set_id
              AND (acknowledgement_status IS NULL OR acknowledgement_status = 'RETRY')
        )
        ORDER BY storage_sequence ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun findFirstEligibleChangeSet(): EligibleOutboundChangeSet?

    @Query(
        """
        SELECT storage_sequence, change_set_id, metadata_json
        FROM outbound_change_sets
        WHERE EXISTS (
            SELECT 1
            FROM outbound_change_events
            WHERE change_set_id = outbound_change_sets.change_set_id
              AND (acknowledgement_status IS NULL OR acknowledgement_status = 'RETRY')
              AND entity_type IN (:entityTypes)
        )
        ORDER BY storage_sequence ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun findFirstEligibleChangeSet(
        entityTypes: List<String>,
    ): EligibleOutboundChangeSet?

    @Query(
        """
        SELECT COUNT(*)
        FROM outbound_change_events
        WHERE change_set_id = :changeSetId
          AND (acknowledgement_status IS NULL OR acknowledgement_status = 'RETRY')
        """,
    )
    protected abstract suspend fun countEligibleEvents(changeSetId: String): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM outbound_change_events
        WHERE change_set_id = :changeSetId
          AND (acknowledgement_status IS NULL OR acknowledgement_status = 'RETRY')
          AND entity_type IN (:entityTypes)
        """,
    )
    protected abstract suspend fun countEligibleEvents(
        changeSetId: String,
        entityTypes: List<String>,
    ): Int

    @Query(
        """
        SELECT *
        FROM outbound_change_events
        WHERE change_set_id = :changeSetId
          AND (acknowledgement_status IS NULL OR acknowledgement_status = 'RETRY')
        ORDER BY event_index ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun selectEligibleEvents(
        changeSetId: String,
        limit: Int,
    ): List<OutboundChangeEventEntity>

    @Query(
        """
        SELECT *
        FROM outbound_change_events
        WHERE change_set_id = :changeSetId
          AND (acknowledgement_status IS NULL OR acknowledgement_status = 'RETRY')
          AND entity_type IN (:entityTypes)
        ORDER BY event_index ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun selectEligibleEvents(
        changeSetId: String,
        entityTypes: List<String>,
        limit: Int,
    ): List<OutboundChangeEventEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM outbound_change_sets
            WHERE storage_sequence > :storageSequence
              AND EXISTS (
                  SELECT 1
                  FROM outbound_change_events
                  WHERE change_set_id = outbound_change_sets.change_set_id
                    AND (acknowledgement_status IS NULL OR acknowledgement_status = 'RETRY')
              )
        )
        """,
    )
    protected abstract suspend fun hasEligibleEventsAfter(storageSequence: Long): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM outbound_change_sets
            WHERE storage_sequence > :storageSequence
              AND EXISTS (
                  SELECT 1
                  FROM outbound_change_events
                  WHERE change_set_id = outbound_change_sets.change_set_id
                    AND (acknowledgement_status IS NULL OR acknowledgement_status = 'RETRY')
                    AND entity_type IN (:entityTypes)
              )
        )
        """,
    )
    protected abstract suspend fun hasEligibleEventsAfter(
        storageSequence: Long,
        entityTypes: List<String>,
    ): Boolean

    @Query(
        """
        UPDATE outbound_change_events
        SET acknowledgement_status = :status,
            ack_error_code = :errorCode,
            ack_error_category = :errorCategory,
            ack_error_severity = :errorSeverity,
            ack_error_recoverability = :errorRecoverability,
            ack_error_message = :errorMessage,
            ack_metadata_json = :metadataJson
        WHERE change_set_id = :changeSetId
          AND event_id = :eventId
        """,
    )
    protected abstract suspend fun updateAcknowledgement(
        changeSetId: String,
        eventId: String,
        status: String,
        errorCode: String?,
        errorCategory: String?,
        errorSeverity: String?,
        errorRecoverability: String?,
        errorMessage: String?,
        metadataJson: String?,
    ): Int

    @Query(
        """
        SELECT *
        FROM outbound_change_sets
        WHERE change_set_id = :changeSetId
        LIMIT 1
        """,
    )
    internal abstract suspend fun findStoredChangeSet(changeSetId: String): OutboundChangeSetEntity?

    @Query(
        """
        SELECT *
        FROM outbound_change_events
        WHERE change_set_id = :changeSetId
        ORDER BY event_index ASC
        """,
    )
    internal abstract suspend fun findStoredEvents(changeSetId: String): List<OutboundChangeEventEntity>

    @Transaction
    internal open suspend fun appendChangeSet(changeSet: ChangeSet) {
        val entity = changeSet.toOutboundEntity()
        insertChangeSetEntity(entity)
        insertEventEntities(changeSet.toOutboundEventEntities())
    }

    @Transaction
    internal open suspend fun readNextBatch(
        entityTypes: Set<String>,
        maxEvents: Int?,
    ): PersistedOutboundBatch? {
        val filter: List<String>? = entityTypes.takeIf { it.isNotEmpty() }?.toList()
        val changeSet = if (filter == null) {
            findFirstEligibleChangeSet()
        } else {
            findFirstEligibleChangeSet(filter)
        } ?: return null

        val eligibleCount = if (filter == null) {
            countEligibleEvents(changeSet.changeSetId)
        } else {
            countEligibleEvents(changeSet.changeSetId, filter)
        }
        if (eligibleCount <= 0) {
            throw CorruptStorageStateException(
                "Eligible outbound change set must contain at least one readable event.",
            )
        }

        val limit = maxEvents ?: eligibleCount
        val events = if (filter == null) {
            selectEligibleEvents(changeSet.changeSetId, limit)
        } else {
            selectEligibleEvents(changeSet.changeSetId, filter, limit)
        }
        if (events.isEmpty()) {
            throw CorruptStorageStateException(
                "Eligible outbound change set must reconstruct at least one event.",
            )
        }

        val hasMore = if (eligibleCount > events.size) {
            true
        } else if (filter == null) {
            hasEligibleEventsAfter(changeSet.storageSequence)
        } else {
            hasEligibleEventsAfter(changeSet.storageSequence, filter)
        }

        return PersistedOutboundBatch(
            storageSequence = changeSet.storageSequence,
            changeSetId = changeSet.changeSetId,
            metadataJson = changeSet.metadataJson,
            events = events,
            hasMore = hasMore,
        )
    }

    @Transaction
    internal open suspend fun recordAcknowledgement(
        acknowledgement: ChangeSetAcknowledgement,
    ): Boolean {
        for (event in acknowledgement.events) {
            val affectedRows = updateAcknowledgement(
                changeSetId = acknowledgement.changeSetId.value,
                eventId = event.eventId.value,
                status = event.status.name,
                errorCode = event.error?.code?.value,
                errorCategory = event.error?.category?.name,
                errorSeverity = event.error?.severity?.name,
                errorRecoverability = event.error?.recoverability?.name,
                errorMessage = event.error?.message,
                metadataJson = event.metadata.toJsonOrNull(),
            )
            if (affectedRows != 1) {
                return false
            }
        }
        return true
    }
}

@Dao
internal abstract class InboundChangeDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertChangeSetEntity(entity: InboundChangeSetEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEventEntities(entities: List<InboundChangeEventEntity>)

    @Query(
        """
        SELECT *
        FROM inbound_change_sets
        WHERE change_set_id = :changeSetId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findStoredChangeSet(changeSetId: String): InboundChangeSetEntity?

    @Query(
        """
        SELECT *
        FROM inbound_change_events
        WHERE change_set_id = :changeSetId
        ORDER BY event_index ASC
        """,
    )
    protected abstract suspend fun findStoredEvents(changeSetId: String): List<InboundChangeEventEntity>

    @Transaction
    internal open suspend fun applyChangeSet(changeSet: ChangeSet): InboundApplyDisposition {
        val storedEntity = changeSet.toInboundEntity()
        val storedEvents = changeSet.toInboundEventEntities()
        val existingEntity = findStoredChangeSet(changeSet.id.value)
        if (existingEntity != null) {
            val existingEvents = findStoredEvents(changeSet.id.value)
            return if (existingEntity.contentEquals(storedEntity) &&
                existingEvents.contentEquals(storedEvents)
            ) {
                InboundApplyDisposition.STORED
            } else {
                InboundApplyDisposition.CONFLICT
            }
        }

        insertChangeSetEntity(storedEntity)
        insertEventEntities(storedEvents)
        val reloadedEntity = findStoredChangeSet(changeSet.id.value)
            ?: throw CorruptStorageStateException("Stored inbound change set could not be reloaded.")
        val reloadedEvents = findStoredEvents(changeSet.id.value)
        if (!reloadedEntity.contentEquals(storedEntity) ||
            !reloadedEvents.contentEquals(storedEvents)
        ) {
            throw CorruptStorageStateException(
                "Stored inbound change set changed during write verification.",
            )
        }
        return InboundApplyDisposition.STORED
    }

    @Query(
        """
        SELECT *
        FROM inbound_change_sets
        WHERE change_set_id = :changeSetId
        LIMIT 1
        """,
    )
    internal abstract suspend fun readStoredChangeSet(changeSetId: String): InboundChangeSetEntity?

    @Query(
        """
        SELECT *
        FROM inbound_change_events
        WHERE change_set_id = :changeSetId
        ORDER BY event_index ASC
        """,
    )
    internal abstract suspend fun readStoredEvents(changeSetId: String): List<InboundChangeEventEntity>
}

@Dao
internal interface StorageCheckpointDao {
    @Query(
        """
        SELECT *
        FROM storage_checkpoints
        WHERE checkpoint_key = :checkpointKey
        LIMIT 1
        """,
    )
    suspend fun findByKey(checkpointKey: String): StorageCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StorageCheckpointEntity)
}

private fun InboundChangeSetEntity.contentEquals(other: InboundChangeSetEntity): Boolean =
    changeSetId == other.changeSetId &&
        metadataJson.toMetadataOrEmpty() == other.metadataJson.toMetadataOrEmpty()

private fun List<InboundChangeEventEntity>.contentEquals(other: List<InboundChangeEventEntity>): Boolean =
    size == other.size && indices.all { index -> this[index].contentEquals(other[index]) }

private fun InboundChangeEventEntity.contentEquals(other: InboundChangeEventEntity): Boolean =
    changeSetId == other.changeSetId &&
        eventId == other.eventId &&
        eventIndex == other.eventIndex &&
        entityType == other.entityType &&
        entityId == other.entityId &&
        entityVersion == other.entityVersion &&
        operation == other.operation &&
        payloadContentType == other.payloadContentType &&
        payloadBytes.contentEqualsNullable(other.payloadBytes) &&
        metadataJson.toMetadataOrEmpty() == other.metadataJson.toMetadataOrEmpty()

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) {
        this == null && other == null
    } else {
        contentEquals(other)
    }

private fun String?.toMetadataOrEmpty() = this?.toMetadata()
    ?: io.dataloom.api.context.DataLoomMetadata.Empty
