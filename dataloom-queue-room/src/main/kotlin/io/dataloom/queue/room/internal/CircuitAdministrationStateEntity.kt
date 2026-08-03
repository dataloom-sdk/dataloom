package io.dataloom.queue.room.internal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Flat, bounded Room representation of one circuit-administration audit record. */
@Entity(tableName = "circuit_administration_states")
internal data class CircuitAdministrationStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "scope_key")
    val scopeKey: String,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String?,
    @ColumnInfo(name = "operation")
    val operation: String?,
    @ColumnInfo(name = "tenant_id")
    val tenantId: String?,
    @ColumnInfo(name = "workflow_id")
    val workflowId: String?,
    @ColumnInfo(name = "principal_id")
    val principalId: String,
    @ColumnInfo(name = "requested_at_ms")
    val requestedAtMs: Long,
    @ColumnInfo(name = "action")
    val action: String,
    @ColumnInfo(name = "reason")
    val reason: String,
    @ColumnInfo(name = "requested_open_until_ms")
    val requestedOpenUntilMs: Long?,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "authorization_id")
    val authorizationId: String?,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "rejection_reason_code")
    val rejectionReasonCode: String?,
    @ColumnInfo(name = "result_phase")
    val resultPhase: String?,
    @ColumnInfo(name = "result_consecutive_failures")
    val resultConsecutiveFailures: Int?,
    @ColumnInfo(name = "result_failure_window_started_at_ms")
    val resultFailureWindowStartedAtMs: Long?,
    @ColumnInfo(name = "result_open_until_ms")
    val resultOpenUntilMs: Long?,
    @ColumnInfo(name = "result_probe_generation")
    val resultProbeGeneration: Long?,
    @ColumnInfo(name = "result_probe_in_flight")
    val resultProbeInFlight: Boolean?,
    @ColumnInfo(name = "result_probe_lease_until_ms")
    val resultProbeLeaseUntilMs: Long?,
    @ColumnInfo(name = "result_updated_at_ms")
    val resultUpdatedAtMs: Long?,
    @ColumnInfo(name = "result_record_version")
    val resultRecordVersion: Long?,
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
