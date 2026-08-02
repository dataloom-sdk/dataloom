package io.dataloom.queue.room.internal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Flat, bounded Room representation of one retry-administration audit record. */
@Entity(tableName = "retry_administration_states")
internal data class RetryAdministrationStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "queue_entry_id")
    val queueEntryId: String,
    @ColumnInfo(name = "principal_id")
    val principalId: String,
    @ColumnInfo(name = "requested_at_ms")
    val requestedAtMs: Long,
    @ColumnInfo(name = "action")
    val action: String,
    @ColumnInfo(name = "reason")
    val reason: String,
    @ColumnInfo(name = "original_error_code")
    val originalErrorCode: String,
    @ColumnInfo(name = "original_error_category")
    val originalErrorCategory: String,
    @ColumnInfo(name = "original_error_severity")
    val originalErrorSeverity: String,
    @ColumnInfo(name = "original_error_recoverability")
    val originalErrorRecoverability: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "authorization_id")
    val authorizationId: String?,
    @ColumnInfo(name = "effective_recoverability")
    val effectiveRecoverability: String?,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "rejection_reason_code")
    val rejectionReasonCode: String?,
    @ColumnInfo(name = "execution_error_code")
    val executionErrorCode: String?,
    @ColumnInfo(name = "execution_error_category")
    val executionErrorCategory: String?,
    @ColumnInfo(name = "execution_error_severity")
    val executionErrorSeverity: String?,
    @ColumnInfo(name = "execution_error_recoverability")
    val executionErrorRecoverability: String?,
    @ColumnInfo(name = "record_version")
    val recordVersion: Long,
)