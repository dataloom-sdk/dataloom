package io.dataloom.storage.room.internal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "outbound_change_sets",
    indices = [Index(value = ["change_set_id"], unique = true)],
)
internal data class OutboundChangeSetEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "storage_sequence")
    val storageSequence: Long = 0,
    @ColumnInfo(name = "change_set_id")
    val changeSetId: String,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String?,
)

@Entity(
    tableName = "outbound_change_events",
    primaryKeys = ["change_set_id", "event_id"],
    indices = [
        Index(value = ["change_set_id", "event_index"], name = "idx_outbound_change_events_order"),
        Index(value = ["acknowledgement_status"], name = "idx_outbound_ack_status"),
        Index(value = ["entity_type"], name = "idx_outbound_entity_type"),
    ],
)
internal data class OutboundChangeEventEntity(
    @ColumnInfo(name = "change_set_id")
    val changeSetId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "event_index")
    val eventIndex: Int,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "entity_version")
    val entityVersion: String?,
    @ColumnInfo(name = "operation")
    val operation: String,
    @ColumnInfo(name = "payload_content_type")
    val payloadContentType: String?,
    @ColumnInfo(name = "payload_bytes")
    val payloadBytes: ByteArray?,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String?,
    @ColumnInfo(name = "acknowledgement_status")
    val acknowledgementStatus: String?,
    @ColumnInfo(name = "ack_error_code")
    val ackErrorCode: String?,
    @ColumnInfo(name = "ack_error_category")
    val ackErrorCategory: String?,
    @ColumnInfo(name = "ack_error_severity")
    val ackErrorSeverity: String?,
    @ColumnInfo(name = "ack_error_recoverability")
    val ackErrorRecoverability: String?,
    @ColumnInfo(name = "ack_error_message")
    val ackErrorMessage: String?,
    @ColumnInfo(name = "ack_metadata_json")
    val ackMetadataJson: String?,
)

@Entity(
    tableName = "inbound_change_sets",
    indices = [Index(value = ["change_set_id"], unique = true)],
)
internal data class InboundChangeSetEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "storage_sequence")
    val storageSequence: Long = 0,
    @ColumnInfo(name = "change_set_id")
    val changeSetId: String,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String?,
)

@Entity(
    tableName = "inbound_change_events",
    primaryKeys = ["change_set_id", "event_id"],
    indices = [
        Index(value = ["change_set_id", "event_index"], name = "idx_inbound_change_events_order"),
        Index(value = ["entity_type"], name = "idx_inbound_entity_type"),
    ],
)
internal data class InboundChangeEventEntity(
    @ColumnInfo(name = "change_set_id")
    val changeSetId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "event_index")
    val eventIndex: Int,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "entity_version")
    val entityVersion: String?,
    @ColumnInfo(name = "operation")
    val operation: String,
    @ColumnInfo(name = "payload_content_type")
    val payloadContentType: String?,
    @ColumnInfo(name = "payload_bytes")
    val payloadBytes: ByteArray?,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String?,
)

@Entity(tableName = "storage_checkpoints")
internal data class StorageCheckpointEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "checkpoint_key")
    val checkpointKey: String,
    @ColumnInfo(name = "checkpoint_token")
    val checkpointToken: String,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String?,
)

internal data class EligibleOutboundChangeSet(
    @ColumnInfo(name = "storage_sequence")
    val storageSequence: Long,
    @ColumnInfo(name = "change_set_id")
    val changeSetId: String,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String?,
)

internal data class PersistedOutboundBatch(
    val storageSequence: Long,
    val changeSetId: String,
    val metadataJson: String?,
    val events: List<OutboundChangeEventEntity>,
    val hasMore: Boolean,
)

internal enum class InboundApplyDisposition {
    STORED,
    CONFLICT,
}
