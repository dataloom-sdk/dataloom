package io.dataloom.storage.room.internal

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.payload.PayloadContentType
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import org.json.JSONObject

internal fun ChangeSet.toOutboundEntity(): OutboundChangeSetEntity = OutboundChangeSetEntity(
    changeSetId = id.value,
    metadataJson = metadata.toJsonOrNull(),
)

internal fun ChangeSet.toOutboundEventEntities(): List<OutboundChangeEventEntity> =
    events.mapIndexed { index, event -> event.toOutboundEntity(id.value, index) }

internal fun ChangeEvent.toOutboundEntity(
    changeSetId: String,
    eventIndex: Int,
): OutboundChangeEventEntity = OutboundChangeEventEntity(
    changeSetId = changeSetId,
    eventId = id.value,
    eventIndex = eventIndex,
    entityType = entity.type.value,
    entityId = entity.id.value,
    entityVersion = entity.version?.value,
    operation = operation.name,
    payloadContentType = payload?.contentType?.value,
    payloadBytes = payload?.copyBytes(),
    metadataJson = metadata.toJsonOrNull(),
    acknowledgementStatus = null,
    ackErrorCode = null,
    ackErrorCategory = null,
    ackErrorSeverity = null,
    ackErrorRecoverability = null,
    ackErrorMessage = null,
    ackMetadataJson = null,
)

internal fun ChangeSet.toInboundEntity(): InboundChangeSetEntity = InboundChangeSetEntity(
    changeSetId = id.value,
    metadataJson = metadata.toJsonOrNull(),
)

internal fun ChangeSet.toInboundEventEntities(): List<InboundChangeEventEntity> =
    events.mapIndexed { index, event -> event.toInboundEntity(id.value, index) }

internal fun ChangeEvent.toInboundEntity(
    changeSetId: String,
    eventIndex: Int,
): InboundChangeEventEntity = InboundChangeEventEntity(
    changeSetId = changeSetId,
    eventId = id.value,
    eventIndex = eventIndex,
    entityType = entity.type.value,
    entityId = entity.id.value,
    entityVersion = entity.version?.value,
    operation = operation.name,
    payloadContentType = payload?.contentType?.value,
    payloadBytes = payload?.copyBytes(),
    metadataJson = metadata.toJsonOrNull(),
)

internal fun PersistedOutboundBatch.toDomain(): ChangeSet = ChangeSet(
    id = ChangeSetId(changeSetId),
    events = events.map(OutboundChangeEventEntity::toDomain),
    metadata = metadataJson?.toMetadata() ?: DataLoomMetadata.Empty,
)

internal fun StorageCheckpointEntity.toDomain(): SynchronizationCheckpoint = SynchronizationCheckpoint(
    key = CheckpointKey(checkpointKey),
    token = CheckpointToken(checkpointToken),
    metadata = metadataJson?.toMetadata() ?: DataLoomMetadata.Empty,
)

internal fun SynchronizationCheckpoint.toEntity(): StorageCheckpointEntity = StorageCheckpointEntity(
    checkpointKey = key.value,
    checkpointToken = token.value,
    metadataJson = metadata.toJsonOrNull(),
)

internal fun OutboundChangeEventEntity.toDomain(): ChangeEvent = reconstructChangeEvent(
    eventId = eventId,
    entityType = entityType,
    entityId = entityId,
    entityVersion = entityVersion,
    operation = operation,
    payloadContentType = payloadContentType,
    payloadBytes = payloadBytes,
    metadataJson = metadataJson,
)

private fun reconstructChangeEvent(
    eventId: String,
    entityType: String,
    entityId: String,
    entityVersion: String?,
    operation: String,
    payloadContentType: String?,
    payloadBytes: ByteArray?,
    metadataJson: String?,
): ChangeEvent = try {
    val payload = when {
        payloadContentType == null && payloadBytes == null -> null
        payloadContentType != null && payloadBytes != null -> DataLoomPayload(
            contentType = PayloadContentType(payloadContentType),
            bytes = payloadBytes,
        )
        else -> throw CorruptStorageStateException(
            "Persisted payload columns must be either all null or all non-null.",
        )
    }

    ChangeEvent(
        id = ChangeEventId(eventId),
        entity = EntityReference(
            type = EntityType(entityType),
            id = EntityId(entityId),
            version = entityVersion?.let(::EntityVersion),
        ),
        operation = enumValueOf<ChangeOperation>(operation),
        payload = payload,
        metadata = metadataJson?.toMetadata() ?: DataLoomMetadata.Empty,
    )
} catch (exception: CorruptStorageStateException) {
    throw exception
} catch (exception: Exception) {
    throw CorruptStorageStateException("Stored change-event state is invalid.", exception)
}

internal fun DataLoomMetadata.toJsonOrNull(): String? {
    val entries = this.entries
    if (entries.isEmpty()) {
        return null
    }
    val json = JSONObject()
    entries.forEach { (key, value) -> json.put(key, value) }
    return json.toString()
}

internal fun String.toMetadata(): DataLoomMetadata = try {
    val json = JSONObject(this)
    val map = mutableMapOf<String, String>()
    json.keys().forEach { key -> map[key] = json.getString(key) }
    DataLoomMetadata.of(map)
} catch (exception: Exception) {
    throw CorruptStorageStateException("Stored metadata JSON is invalid.", exception)
}
