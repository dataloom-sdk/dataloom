package io.dataloom.storage.room.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Canonical DataLoom error values emitted by [io.dataloom.storage.room.RoomStorageProvider].
 *
 * All messages are sanitized. No raw SQL details, exception messages, database
 * paths, metadata content, or payload bytes are included.
 */
internal object StorageProviderError {

    fun databaseFailure(): DataLoomError = error(
        code = "STORAGE_ROOM_DATABASE_FAILURE",
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "A storage database operation failed.",
    )

    fun stateCorrupt(): DataLoomError = error(
        code = "STORAGE_ROOM_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Stored storage-provider state failed validation.",
    )

    fun acknowledgementTargetMissing(): DataLoomError = error(
        code = "STORAGE_OUTBOUND_ACKNOWLEDGEMENT_TARGET_MISSING",
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.WARNING,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The outbound acknowledgement target was not found.",
    )

    fun inboundConflict(): DataLoomError = error(
        code = "STORAGE_INBOUND_CHANGESET_CONFLICT",
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The inbound change set conflicts with existing durable state.",
    )

    private fun error(
        code: String,
        category: ErrorCategory,
        severity: ErrorSeverity,
        recoverability: Recoverability,
        message: String,
        cause: Throwable? = null,
    ): DataLoomError = StorageError(
        code = ErrorCode(code),
        category = category,
        severity = severity,
        recoverability = recoverability,
        message = message,
        cause = cause,
    )

    private data class StorageError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}

internal class CorruptStorageStateException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
