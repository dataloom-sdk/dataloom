package io.dataloom.queue.room.internal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Flat, bounded Room representation of one durable circuit-breaker state record. */
@Entity(tableName = "circuit_breaker_states")
internal data class CircuitBreakerStateEntity(
    @PrimaryKey
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
    @ColumnInfo(name = "phase")
    val phase: String,
    @ColumnInfo(name = "consecutive_failures")
    val consecutiveFailures: Int,
    @ColumnInfo(name = "failure_window_started_at_ms")
    val failureWindowStartedAtMs: Long?,
    @ColumnInfo(name = "open_until_ms")
    val openUntilMs: Long?,
    @ColumnInfo(name = "probe_generation")
    val probeGeneration: Long,
    @ColumnInfo(name = "probe_in_flight")
    val probeInFlight: Boolean,
    @ColumnInfo(name = "probe_lease_until_ms")
    val probeLeaseUntilMs: Long?,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "record_version")
    val recordVersion: Long,
)