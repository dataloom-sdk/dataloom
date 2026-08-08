package io.dataloom.storage.datastore.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Canonical [DataLoomError] values emitted by
 * [io.dataloom.storage.datastore.DataStoreStorageProvider].
 *
 * All messages are sanitized. No raw DataStore exception messages, file paths,
 * or stack traces are included.
 */
internal object DataStoreStorageProviderError {

    /**
     * The outbound event queue is at capacity.
     *
     * Returned by [io.dataloom.storage.datastore.DataStoreStorageProvider.enqueueOutboundChanges]
     * when adding the requested events would exceed [io.dataloom.storage.datastore.DataStoreStorageProvider.MAX_OUTBOUND_EVENTS].
     *
     * DataStore is designed for small, bounded key-value synchronization state. For large-scale
     * or relationally structured sync data, use `dataloom-queue-room` instead.
     */
    fun outboundLimitExceeded(
        currentCount: Int,
        requested: Int,
        limit: Int,
    ): DataLoomError = error(
        code = "DATASTORE_OUTBOUND_LIMIT_EXCEEDED",
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "DataStore outbound event limit exceeded. " +
            "Current: $currentCount, requested: $requested, limit: $limit. " +
            "DataStore is suitable only for small, bounded key-value sync data. " +
            "Consider dataloom-queue-room for larger volumes.",
    )

    /**
     * A DataStore IO operation failed.
     *
     * Returned when a DataStore read or write fails with an unexpected IO exception.
     * Raw exception messages and file paths are not included.
     */
    fun ioFailure(cause: Throwable? = null): DataLoomError = error(
        code = "DATASTORE_IO_FAILURE",
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "A DataStore IO operation failed.",
        cause = cause,
    )

    // ── Internal factory ─────────────────────────────────────────────────────

    private fun error(
        code: String,
        category: ErrorCategory,
        severity: ErrorSeverity,
        recoverability: Recoverability,
        message: String,
        cause: Throwable? = null,
    ): DataLoomError = DataStoreError(
        code = ErrorCode(code),
        category = category,
        severity = severity,
        recoverability = recoverability,
        message = message,
        cause = cause,
    )

    private data class DataStoreError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
