package io.dataloom.queue.room.internal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single durable queue entry.
 *
 * ## Schema version
 *
 * This entity is part of [DataLoomRoomDatabase] schema version 2.
 *
 * ## State storage
 *
 * [state] is stored as the enum constant name (a `String`), not the ordinal.
 * Ordinal-based storage is fragile because adding or reordering enum values
 * changes the ordinal mapping. Name-based storage is stable.
 *
 * ## SynchronizationRequest storage
 *
 * All fields of `SynchronizationRequest` and its nested `ExecutionContext` are
 * stored as flat columns. No JSON serialization library is required. Enum
 * names (not ordinals) are stored for `direction`, `mode`, and `priority`.
 * The `ExecutionContext.metadata` map is serialized as a compact JSON object
 * using the Android-bundled `org.json.JSONObject`. The provider does not
 * interpret or decode the logical content of the request.
 *
 * ## Lease fields
 *
 * Lease identity is stored in flat nullable columns to avoid a join
 * for acquisition and transition queries:
 * - [leaseId] — unique lease identifier; non-null when the entry is leased.
 * - [leaseConsumerId] — consumer that acquired the lease.
 * - [leaseAcquiredAtMs] — epoch-milliseconds at which the lease was acquired.
 * - [leaseExpiresAtMs] — epoch-milliseconds at which the lease expires.
 *
 * ## Indexes
 *
 * - `idx_state_available_at` — supports the bounded acquisition query that
 *   filters on [state] and [availableAtMs].
 * - `idx_state_lease_expires` — supports the expired-lease recovery query
 *   that filters on [state] and [leaseExpiresAtMs].
 */
@Entity(
    tableName = "queue_entries",
    indices = [
        Index(value = ["state", "available_at_ms"], name = "idx_state_available_at"),
        Index(value = ["state", "lease_expires_at_ms"], name = "idx_state_lease_expires"),
    ],
)
internal data class QueueEntryEntity(

    /** Unique queue entry identifier. Primary key. */
    @PrimaryKey
    @ColumnInfo(name = "entry_id")
    val entryId: String,

    // ── SynchronizationRequest fields ───────────────────────────────────────

    /** WorkflowId.value from SynchronizationRequest. */
    @ColumnInfo(name = "workflow_id")
    val workflowId: String,

    /** SynchronizationSessionId.value from SynchronizationRequest. */
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** SynchronizationDirection enum name from SynchronizationRequest. */
    @ColumnInfo(name = "direction")
    val direction: String,

    /** SynchronizationMode enum name from SynchronizationRequest. */
    @ColumnInfo(name = "mode")
    val mode: String,

    /** WorkflowPriority enum name from SynchronizationRequest. */
    @ColumnInfo(name = "priority")
    val priority: String,

    // ── ExecutionContext fields ──────────────────────────────────────────────

    /** ExecutionId.value from ExecutionContext. */
    @ColumnInfo(name = "exec_execution_id")
    val execExecutionId: String,

    /** CorrelationId.value from ExecutionContext. */
    @ColumnInfo(name = "exec_correlation_id")
    val execCorrelationId: String,

    /** TraceId.value from ExecutionContext; null when absent. */
    @ColumnInfo(name = "exec_trace_id")
    val execTraceId: String?,

    /** RequestId.value from ExecutionContext; null when absent. */
    @ColumnInfo(name = "exec_request_id")
    val execRequestId: String?,

    /** TenantId.value from ExecutionContext; null when absent. */
    @ColumnInfo(name = "exec_tenant_id")
    val execTenantId: String?,

    /** UserId.value from ExecutionContext; null when absent. */
    @ColumnInfo(name = "exec_user_id")
    val execUserId: String?,

    /** LocaleTag.value from ExecutionContext; null when absent. */
    @ColumnInfo(name = "exec_locale_tag")
    val execLocaleTag: String?,

    /** RuntimeVersion.value from ExecutionContext; null when absent. */
    @ColumnInfo(name = "exec_runtime_version")
    val execRuntimeVersion: String?,

    /** ConfigurationVersion.value from ExecutionContext; null when absent. */
    @ColumnInfo(name = "exec_config_version")
    val execConfigVersion: String?,

    /**
     * JSON-serialized ExecutionContext.metadata entries as a flat key–value
     * object. Null when the metadata map is empty.
     *
     * Serialized using `org.json.JSONObject` (Android SDK bundled). The
     * provider does not interpret the content.
     */
    @ColumnInfo(name = "exec_metadata_json")
    val execMetadataJson: String?,

    // ── QueueEntry lifecycle fields ──────────────────────────────────────────

    /**
     * Entry lifecycle state stored as enum constant name.
     *
     * Possible values: PENDING, LEASED, RETRY_WAITING, COMPLETED, FAILED,
     * CANCELLED, DEAD_LETTER.
     */
    @ColumnInfo(name = "state")
    val state: String,

    /** Epoch-milliseconds at which this entry was enqueued. */
    @ColumnInfo(name = "enqueued_at_ms")
    val enqueuedAtMs: Long,

    /** Epoch-milliseconds at which this entry becomes eligible for acquisition. */
    @ColumnInfo(name = "available_at_ms")
    val availableAtMs: Long,

    /** Retry attempt number; null when the entry has not been rescheduled. */
    @ColumnInfo(name = "retry_attempt_number")
    val retryAttemptNumber: Int?,

    /** First genuine retry-budget evaluation instant; null when budgets are disabled. */
    @ColumnInfo(name = "retry_window_started_at_ms")
    val retryWindowStartedAtMs: Long?,

    /** Most recent accepted retry-budget evaluation instant. */
    @ColumnInfo(name = "retry_last_evaluated_at_ms")
    val retryLastEvaluatedAtMs: Long?,

    /** Sum of delays accepted for durable retry transitions. */
    @ColumnInfo(name = "retry_cumulative_delay_ms")
    val retryCumulativeDelayMs: Long?,

    /** Unique lease identifier; non-null only when state = LEASED. */
    @ColumnInfo(name = "lease_id")
    val leaseId: String?,

    /** Consumer identifier that holds the current lease; non-null only when leased. */
    @ColumnInfo(name = "lease_consumer_id")
    val leaseConsumerId: String?,

    /** Epoch-milliseconds at which the current lease was acquired; non-null when leased. */
    @ColumnInfo(name = "lease_acquired_at_ms")
    val leaseAcquiredAtMs: Long?,

    /** Epoch-milliseconds at which the current lease expires; non-null when leased. */
    @ColumnInfo(name = "lease_expires_at_ms")
    val leaseExpiresAtMs: Long?,

    /**
     * Canonical error code from the last processing failure.
     *
     * Non-null for RETRY_WAITING, FAILED, and DEAD_LETTER entries.
     */
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,

    /** ErrorCategory enum name from the last processing failure. */
    @ColumnInfo(name = "last_error_category")
    val lastErrorCategory: String?,

    /** ErrorSeverity enum name from the last processing failure. */
    @ColumnInfo(name = "last_error_severity")
    val lastErrorSeverity: String?,

    /** Recoverability enum name from the last processing failure. */
    @ColumnInfo(name = "last_error_recoverability")
    val lastErrorRecoverability: String?,

    /**
     * Canonical error message from the last processing failure.
     *
     * Sanitized: must not contain raw exception messages, stack traces,
     * credentials, tokens, or sensitive data.
     */
    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String?,

    /**
     * QueueEntry.metadata entries serialized as a flat JSON object.
     * Null when the metadata map is empty.
     */
    @ColumnInfo(name = "entry_metadata_json")
    val entryMetadataJson: String?,
)
