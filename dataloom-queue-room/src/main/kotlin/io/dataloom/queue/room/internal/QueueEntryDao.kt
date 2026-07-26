package io.dataloom.queue.room.internal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Room DAO for durable queue entry persistence operations.
 *
 * ## Thread safety
 *
 * All operations are `suspend` functions that run on the Room-managed executor.
 * The provider dispatches calls to [kotlinx.coroutines.Dispatchers.IO].
 *
 * ## Transactional acquisition
 *
 * [acquireEntries] uses `@Transaction` to atomically select eligible entries
 * and update them to LEASED state. No eligible entry is visible to a second
 * concurrent acquisition until the transaction commits.
 *
 * ## Guarded transitions
 *
 * All transition operations ([completeEntry], [rescheduleEntry],
 * [failEntry], [cancelEntry]) include the lease identifier in the `WHERE`
 * clause. If the lease is stale or mismatched, the `affected rows` count is
 * zero, and the provider returns a canonical stale-lease error.
 */
@Dao
internal abstract class QueueEntryDao {

    // ── Enqueue ──────────────────────────────────────────────────────────────

    /**
     * Inserts a new queue entry. Fails with a SQL exception if an entry with
     * the same primary key already exists ([OnConflictStrategy.ABORT]).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(entity: QueueEntryEntity)

    // ── Acquisition helpers ──────────────────────────────────────────────────

    /**
     * Selects eligible entries (PENDING or RETRY_WAITING state where
     * available_at_ms is not later than [nowMs]) ordered by
     * `available_at_ms ASC`, limited to [limit] rows.
     *
     * This private query is called inside [acquireEntries].
     */
    @Query(
        """
        SELECT * FROM queue_entries
        WHERE state IN ('PENDING', 'RETRY_WAITING')
          AND available_at_ms <= :nowMs
        ORDER BY available_at_ms ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun selectEligibleEntries(
        nowMs: Long,
        limit: Int,
    ): List<QueueEntryEntity>

    /**
     * Transitions a single entry to LEASED state.
     *
     * The `WHERE` clause matches on both `entry_id` and the current `state`
     * to prevent a double-acquisition race when two concurrent transactions
     * select the same entry.
     *
     * Returns the number of affected rows (0 or 1).
     */
    @Query(
        """
        UPDATE queue_entries
        SET state = 'LEASED',
            lease_id = :leaseId,
            lease_consumer_id = :consumerId,
            lease_acquired_at_ms = :acquiredAtMs,
            lease_expires_at_ms = :expiresAtMs
        WHERE entry_id = :entryId
          AND state IN ('PENDING', 'RETRY_WAITING')
        """,
    )
    protected abstract suspend fun updateToLeased(
        entryId: String,
        leaseId: String,
        consumerId: String,
        acquiredAtMs: Long,
        expiresAtMs: Long,
    ): Int

    /**
     * Atomically selects eligible entries and transitions them to LEASED state.
     *
     * The `@Transaction` annotation ensures that the SELECT and all UPDATE
     * operations run inside a single SQLite transaction. No eligible entry is
     * visible to a second concurrent acquisition before this transaction
     * commits.
     *
     * Returns the entities as they appear after the LEASED transition.
     * Entries for which [updateToLeased] returns 0 are excluded (concurrent
     * acquisition by another consumer won the race for that specific row).
     */
    @Transaction
    open suspend fun acquireEntries(
        nowMs: Long,
        leaseId: String,
        consumerId: String,
        acquiredAtMs: Long,
        expiresAtMs: Long,
        limit: Int,
    ): List<QueueEntryEntity> {
        val eligible = selectEligibleEntries(nowMs, limit)
        if (eligible.isEmpty()) return emptyList()

        val acquired = mutableListOf<QueueEntryEntity>()
        for (entity in eligible) {
            val affected = updateToLeased(
                entryId = entity.entryId,
                leaseId = leaseId,
                consumerId = consumerId,
                acquiredAtMs = acquiredAtMs,
                expiresAtMs = expiresAtMs,
            )
            if (affected > 0) {
                acquired.add(
                    entity.copy(
                        state = "LEASED",
                        leaseId = leaseId,
                        leaseConsumerId = consumerId,
                        leaseAcquiredAtMs = acquiredAtMs,
                        leaseExpiresAtMs = expiresAtMs,
                    ),
                )
            }
        }
        return acquired
    }

    // ── Completion ───────────────────────────────────────────────────────────

    /**
     * Transitions a LEASED entry to COMPLETED state.
     *
     * The `WHERE` clause matches on both [entryId] and [leaseId] to reject
     * stale or mismatched leases. Returns the number of affected rows (0 or 1).
     */
    @Query(
        """
        UPDATE queue_entries
        SET state = 'COMPLETED',
            lease_id = NULL,
            lease_consumer_id = NULL,
            lease_acquired_at_ms = NULL,
            lease_expires_at_ms = NULL
        WHERE entry_id = :entryId
          AND lease_id = :leaseId
        """,
    )
    abstract suspend fun completeEntry(
        entryId: String,
        leaseId: String,
    ): Int

    // ── Reschedule ───────────────────────────────────────────────────────────

    /**
     * Transitions a LEASED entry to RETRY_WAITING state.
     *
     * The `WHERE` clause matches on both [entryId] and [leaseId] to reject
     * stale or mismatched leases. Returns the number of affected rows (0 or 1).
     */
    @Query(
        """
        UPDATE queue_entries
        SET state = 'RETRY_WAITING',
            available_at_ms = :availableAtMs,
            retry_attempt_number = :retryAttemptNumber,
            last_error_code = :errorCode,
            last_error_message = :errorMessage,
            lease_id = NULL,
            lease_consumer_id = NULL,
            lease_acquired_at_ms = NULL,
            lease_expires_at_ms = NULL
        WHERE entry_id = :entryId
          AND lease_id = :leaseId
        """,
    )
    abstract suspend fun rescheduleEntry(
        entryId: String,
        leaseId: String,
        availableAtMs: Long,
        retryAttemptNumber: Int,
        errorCode: String,
        errorMessage: String?,
    ): Int

    // ── Failure ──────────────────────────────────────────────────────────────

    /**
     * Transitions a LEASED entry to [targetState] (FAILED or DEAD_LETTER).
     *
     * The `WHERE` clause matches on both [entryId] and [leaseId] to reject
     * stale or mismatched leases. Returns the number of affected rows (0 or 1).
     */
    @Query(
        """
        UPDATE queue_entries
        SET state = :targetState,
            last_error_code = :errorCode,
            last_error_message = :errorMessage,
            lease_id = NULL,
            lease_consumer_id = NULL,
            lease_acquired_at_ms = NULL,
            lease_expires_at_ms = NULL
        WHERE entry_id = :entryId
          AND lease_id = :leaseId
        """,
    )
    abstract suspend fun failEntry(
        entryId: String,
        leaseId: String,
        targetState: String,
        errorCode: String,
        errorMessage: String?,
    ): Int

    // ── Cancellation ─────────────────────────────────────────────────────────

    /**
     * Transitions a non-terminal, non-leased entry to CANCELLED state.
     *
     * Cancellation of a LEASED entry is refused by this query (state NOT IN
     * terminal or LEASED set). Returns the number of affected rows (0 or 1).
     */
    @Query(
        """
        UPDATE queue_entries
        SET state = 'CANCELLED'
        WHERE entry_id = :entryId
          AND state IN ('PENDING', 'RETRY_WAITING')
        """,
    )
    abstract suspend fun cancelEntry(entryId: String): Int

    // ── Expired-lease recovery ───────────────────────────────────────────────

    /**
     * Atomically recovers entries with expired leases.
     *
     * Entries in LEASED state whose [leaseExpiresAtMs] is strictly less than
     * [nowMs] are transitioned back to PENDING state with all lease columns
     * cleared.
     *
     * Returns the count of recovered entries.
     */
    @Query(
        """
        UPDATE queue_entries
        SET state = 'PENDING',
            lease_id = NULL,
            lease_consumer_id = NULL,
            lease_acquired_at_ms = NULL,
            lease_expires_at_ms = NULL
        WHERE state = 'LEASED'
          AND lease_expires_at_ms < :nowMs
        """,
    )
    abstract suspend fun recoverExpiredLeases(nowMs: Long): Int
}
