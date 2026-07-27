package io.dataloom.queue.room.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Canonical DataLoom error values emitted by [io.dataloom.queue.room.RoomQueueProvider].
 *
 * All messages are sanitized. No raw SQL details, exception messages, database
 * paths, or stack traces are included.
 */
internal object QueueProviderError {

    /**
     * A queue entry with the same identifier already exists.
     *
     * Returned by [io.dataloom.queue.room.RoomQueueProvider.enqueue] when a
     * duplicate entry identifier is detected.
     */
    fun duplicateEntry(entryId: String): DataLoomError = error(
        code = "QUEUE_DUPLICATE_ENTRY",
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "A queue entry with id '$entryId' already exists.",
    )

    /**
     * A queue database operation failed.
     *
     * Returned when a Room/SQLite operation fails with an unexpected exception.
     * Raw exception messages, SQL details, and database paths are not included.
     */
    fun databaseFailure(): DataLoomError = error(
        code = "QUEUE_DATABASE_FAILURE",
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "A database operation failed.",
    )

    /**
     * The supplied lease identifier did not match the currently active lease.
     *
     * Returned by transition operations ([io.dataloom.queue.room.RoomQueueProvider.complete],
     * [io.dataloom.queue.room.RoomQueueProvider.reschedule],
     * [io.dataloom.queue.room.RoomQueueProvider.fail]) when the lease guard
     * rejects the update (zero affected rows).
     */
    fun staleLease(entryId: String): DataLoomError = error(
        code = "QUEUE_STALE_LEASE",
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Lease mismatch or stale lease for entry '$entryId'.",
    )

    /**
     * The cancellation request could not be applied because the entry was in a
     * terminal or leased state.
     */
    fun cancellationRejected(entryId: String): DataLoomError = error(
        code = "QUEUE_CANCELLATION_REJECTED",
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.WARNING,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Entry '$entryId' cannot be cancelled in its current state.",
    )

    // ── Internal factory ─────────────────────────────────────────────────────

    private fun error(
        code: String,
        category: ErrorCategory,
        severity: ErrorSeverity,
        recoverability: Recoverability,
        message: String,
        cause: Throwable? = null,
    ): DataLoomError = QueueError(
        code = ErrorCode(code),
        category = category,
        severity = severity,
        recoverability = recoverability,
        message = message,
        cause = cause,
    )

    private data class QueueError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
