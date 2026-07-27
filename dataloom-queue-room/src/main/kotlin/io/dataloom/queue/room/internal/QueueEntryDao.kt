package io.dataloom.queue.room.internal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.dataloom.api.queue.QueueEntry

/** Room DAO for bounded, lease-aware durable queue persistence. */
@Dao
internal abstract class QueueEntryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(entity: QueueEntryEntity)

    @Query(
        """
        SELECT * FROM queue_entries
        WHERE state IN ('PENDING', 'RETRY_WAITING')
          AND available_at_ms <= :nowMs
        ORDER BY available_at_ms ASC, enqueued_at_ms ASC, entry_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun selectEligibleEntries(
        nowMs: Long,
        limit: Int,
    ): List<QueueEntryEntity>

    @Query(
        """
        UPDATE queue_entries
        SET state = 'LEASED',
            lease_id = :leaseId,
            lease_consumer_id = :consumerId,
            lease_acquired_at_ms = :acquiredAtMs,
            lease_expires_at_ms = :expiresAtMs,
            last_error_code = NULL,
            last_error_category = NULL,
            last_error_severity = NULL,
            last_error_recoverability = NULL,
            last_error_message = NULL
        WHERE entry_id = :entryId
          AND state IN ('PENDING', 'RETRY_WAITING')
          AND available_at_ms <= :acquiredAtMs
        """,
    )
    protected abstract suspend fun updateToLeased(
        entryId: String,
        leaseId: String,
        consumerId: String,
        acquiredAtMs: Long,
        expiresAtMs: Long,
    ): Int

    /** Selects and leases at most [limit] entries in one SQLite transaction. */
    @Transaction
    open suspend fun acquireEntries(
        nowMs: Long,
        leaseId: String,
        consumerId: String,
        acquiredAtMs: Long,
        expiresAtMs: Long,
        limit: Int,
    ): List<QueueEntry> {
        val eligible = selectEligibleEntries(nowMs, limit)
        if (eligible.isEmpty()) return emptyList()

        return buildList(eligible.size) {
            eligible.forEach { entity ->
                val affected = updateToLeased(
                    entryId = entity.entryId,
                    leaseId = leaseId,
                    consumerId = consumerId,
                    acquiredAtMs = acquiredAtMs,
                    expiresAtMs = expiresAtMs,
                )
                if (affected == 1) {
                    add(
                        entity.copy(
                            state = "LEASED",
                            leaseId = leaseId,
                            leaseConsumerId = consumerId,
                            leaseAcquiredAtMs = acquiredAtMs,
                            leaseExpiresAtMs = expiresAtMs,
                            lastErrorCode = null,
                            lastErrorCategory = null,
                            lastErrorSeverity = null,
                            lastErrorRecoverability = null,
                            lastErrorMessage = null,
                        ).toDomain(),
                    )
                }
            }
        }
    }

    @Query(
        """
        UPDATE queue_entries
        SET state = 'COMPLETED',
            lease_id = NULL,
            lease_consumer_id = NULL,
            lease_acquired_at_ms = NULL,
            lease_expires_at_ms = NULL,
            last_error_code = NULL,
            last_error_category = NULL,
            last_error_severity = NULL,
            last_error_recoverability = NULL,
            last_error_message = NULL
        WHERE entry_id = :entryId
          AND state = 'LEASED'
          AND lease_id = :leaseId
        """,
    )
    abstract suspend fun completeEntry(
        entryId: String,
        leaseId: String,
    ): Int

    @Query(
        """
        UPDATE queue_entries
        SET state = 'RETRY_WAITING',
            available_at_ms = :availableAtMs,
            retry_attempt_number = :retryAttemptNumber,
            last_error_code = :errorCode,
            last_error_category = :errorCategory,
            last_error_severity = :errorSeverity,
            last_error_recoverability = :errorRecoverability,
            last_error_message = :errorMessage,
            lease_id = NULL,
            lease_consumer_id = NULL,
            lease_acquired_at_ms = NULL,
            lease_expires_at_ms = NULL
        WHERE entry_id = :entryId
          AND state = 'LEASED'
          AND lease_id = :leaseId
        """,
    )
    abstract suspend fun rescheduleEntry(
        entryId: String,
        leaseId: String,
        availableAtMs: Long,
        retryAttemptNumber: Int,
        errorCode: String,
        errorCategory: String,
        errorSeverity: String,
        errorRecoverability: String,
        errorMessage: String,
    ): Int

    @Query(
        """
        UPDATE queue_entries
        SET state = :targetState,
            last_error_code = :errorCode,
            last_error_category = :errorCategory,
            last_error_severity = :errorSeverity,
            last_error_recoverability = :errorRecoverability,
            last_error_message = :errorMessage,
            lease_id = NULL,
            lease_consumer_id = NULL,
            lease_acquired_at_ms = NULL,
            lease_expires_at_ms = NULL
        WHERE entry_id = :entryId
          AND state = 'LEASED'
          AND lease_id = :leaseId
        """,
    )
    abstract suspend fun failEntry(
        entryId: String,
        leaseId: String,
        targetState: String,
        errorCode: String,
        errorCategory: String,
        errorSeverity: String,
        errorRecoverability: String,
        errorMessage: String,
    ): Int

    @Query(
        """
        UPDATE queue_entries
        SET state = 'CANCELLED',
            last_error_code = NULL,
            last_error_category = NULL,
            last_error_severity = NULL,
            last_error_recoverability = NULL,
            last_error_message = NULL
        WHERE entry_id = :entryId
          AND state IN ('PENDING', 'RETRY_WAITING')
        """,
    )
    abstract suspend fun cancelEntry(entryId: String): Int

    @Query(
        """
        UPDATE queue_entries
        SET state = 'PENDING',
            retry_attempt_number = NULL,
            lease_id = NULL,
            lease_consumer_id = NULL,
            lease_acquired_at_ms = NULL,
            lease_expires_at_ms = NULL,
            last_error_code = NULL,
            last_error_category = NULL,
            last_error_severity = NULL,
            last_error_recoverability = NULL,
            last_error_message = NULL
        WHERE state = 'LEASED'
          AND lease_expires_at_ms < :nowMs
        """,
    )
    abstract suspend fun recoverExpiredLeases(nowMs: Long): Int
}
