package io.dataloom.storage.file

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Canonical [DataLoomError] implementations for [FileStorageProvider].
 *
 * No raw file-system path, exception message, or I/O detail is exposed in
 * [message]. Diagnostics are bounded to an operation classification.
 */
internal object FileStorageErrors {

    /** Raised when the provider directory cannot be created or verified. */
    fun initializationFailure(): DataLoomError = SimpleFileStorageError(
        code = ErrorCode("dataloom.storage.file.initialization_failed"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "FileStorageProvider directory initialization failed.",
    )

    /** Raised when a read operation encounters an unexpected I/O error. */
    fun readFailure(): DataLoomError = SimpleFileStorageError(
        code = ErrorCode("dataloom.storage.file.read_failed"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "FileStorageProvider failed to read from storage.",
    )

    /** Raised when a write operation encounters an unexpected I/O error. */
    fun writeFailure(): DataLoomError = SimpleFileStorageError(
        code = ErrorCode("dataloom.storage.file.write_failed"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "FileStorageProvider failed to write to storage.",
    )

    /** Raised when an acknowledgement operation encounters an unexpected I/O error. */
    fun acknowledgeFailure(): DataLoomError = SimpleFileStorageError(
        code = ErrorCode("dataloom.storage.file.acknowledge_failed"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "FileStorageProvider failed to record acknowledgement.",
    )

    /** Raised when a checkpoint read encounters an unexpected I/O error. */
    fun checkpointReadFailure(): DataLoomError = SimpleFileStorageError(
        code = ErrorCode("dataloom.storage.file.checkpoint_read_failed"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "FileStorageProvider failed to read checkpoint.",
    )

    /** Raised when a checkpoint write encounters an unexpected I/O error. */
    fun checkpointWriteFailure(): DataLoomError = SimpleFileStorageError(
        code = ErrorCode("dataloom.storage.file.checkpoint_write_failed"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "FileStorageProvider failed to write checkpoint.",
    )

    /** Raised when a store-outbound-event operation encounters an unexpected I/O error. */
    fun storeEventFailure(): DataLoomError = SimpleFileStorageError(
        code = ErrorCode("dataloom.storage.file.store_event_failed"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "FileStorageProvider failed to store outbound event.",
    )

    /** Raised when the provider directory is unavailable at health-check time. */
    fun directoryUnavailable(): DataLoomError = SimpleFileStorageError(
        code = ErrorCode("dataloom.storage.file.directory_unavailable"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "FileStorageProvider base directory is not accessible.",
    )
}

private data class SimpleFileStorageError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
