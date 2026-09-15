package io.dataloom.storage.sqldelight.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString

internal object SqlDelightStorageProviderError {
    fun databaseFailure(cause: Throwable): DataLoomError = StorageError(
        code = ErrorCode("STORAGE_DATABASE_FAILURE"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "A storage database operation failed.",
        cause = cause,
    )

    fun reconciliationCheckpointMissing(): DataLoomError = StorageError(
        code = ErrorCode("STORAGE_RECONCILIATION_CHECKPOINT_MISSING"),
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Reconciliation evidence reported a completed remote persistence step " +
            "but no checkpoint was found.",
        cause = null,
    )

    private data class StorageError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
