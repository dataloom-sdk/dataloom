@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryAdministrationCompareAndSetRequest
import io.dataloom.api.retry.RetryAdministrationCompareAndSetResult
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationLoadResult
import io.dataloom.api.retry.RetryAdministrationStateRecord
import io.dataloom.api.retry.RetryAdministrationStateStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.flock

/**
 * Production Apple file-backed [RetryAdministrationStateStore].
 *
 * The caller supplies an absolute application-private directory, normally a
 * dedicated child of Application Support. Construction validates paths but
 * performs no I/O. Every load and compare-and-set uses one process-shared file
 * lock and one bounded complete snapshot.
 *
 * Successful mutations fsync an owner-only temporary file, atomically rename
 * it over the previous snapshot, and fsync the parent directory before
 * returning success. The persisted record contains only immutable command,
 * authorization, policy, rejection, and redacted execution-failure evidence.
 */
public class AppleFileRetryAdministrationStateStore(
    directoryPath: String,
    fileName: String = DEFAULT_FILE_NAME,
) : RetryAdministrationStateStore {
    private val normalizedDirectoryPath: String =
        appleRetryAdminValidateDirectoryPath(directoryPath)
    private val validatedFileName: String = appleRetryAdminValidateFileName(fileName)
    private val dataFilePath: String = "$normalizedDirectoryPath/$validatedFileName"
    private val lockFilePath: String = "$dataFilePath.lock"
    private val temporaryFilePath: String = "$dataFilePath.tmp"

    override suspend fun load(
        commandId: RetryAdministrationCommandId,
    ): ProviderOperationResult<RetryAdministrationLoadResult> = appleRetryAdminProtect {
        appleRetryAdminWithExclusiveLock {
            val record = appleRetryAdminReadRecords()[commandId.value]
            if (record == null) {
                RetryAdministrationLoadResult.Missing
            } else {
                RetryAdministrationLoadResult.Found(record)
            }
        }
    }

    override suspend fun compareAndSet(
        request: RetryAdministrationCompareAndSetRequest,
    ): ProviderOperationResult<RetryAdministrationCompareAndSetResult> {
        if (request.expectedVersion == Long.MAX_VALUE) {
            return ProviderOperationResult.Failure(
                AppleRetryAdministrationStoreError.versionExhausted(),
            )
        }
        return appleRetryAdminProtect {
            appleRetryAdminWithExclusiveLock {
                val records = appleRetryAdminReadRecords()
                val key = request.commandId.value
                val current = records[key]
                val versionMatches = when (val expected = request.expectedVersion) {
                    null -> current == null
                    else -> current?.version == expected
                }
                val immutableRequestMatches = current == null ||
                    current.state.request == request.nextState.request
                if (!versionMatches || !immutableRequestMatches) {
                    RetryAdministrationCompareAndSetResult.Conflict(current)
                } else {
                    if (current == null && records.size >= APPLE_RETRY_ADMIN_MAX_RECORD_COUNT) {
                        throw AppleRetryAdministrationRecordLimitException()
                    }
                    val nextVersion = request.expectedVersion?.plus(1L) ?: 0L
                    val nextRecord = RetryAdministrationStateRecord(
                        state = request.nextState,
                        version = nextVersion,
                    )
                    records[key] = nextRecord
                    currentCoroutineContext().ensureActive()
                    appleRetryAdminWriteRecords(records)
                    RetryAdministrationCompareAndSetResult.Updated(nextRecord)
                }
            }
        }
    }

    private suspend fun <T> appleRetryAdminProtect(
        block: suspend () -> T,
    ): ProviderOperationResult<T> = try {
        currentCoroutineContext().ensureActive()
        ProviderOperationResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AppleRetryAdministrationStateLimitException) {
        ProviderOperationResult.Failure(
            AppleRetryAdministrationStoreError.stateLimitExceeded(),
        )
    } catch (_: AppleRetryAdministrationRecordLimitException) {
        ProviderOperationResult.Failure(
            AppleRetryAdministrationStoreError.stateLimitExceeded(),
        )
    } catch (_: MalformedAppleRetryAdministrationStateException) {
        ProviderOperationResult.Failure(
            AppleRetryAdministrationStoreError.integrityFailure(),
        )
    } catch (_: AppleRetryAdministrationFileException) {
        ProviderOperationResult.Failure(
            AppleRetryAdministrationStoreError.fileFailure(),
        )
    } catch (_: Exception) {
        ProviderOperationResult.Failure(
            AppleRetryAdministrationStoreError.fileFailure(),
        )
    }

    private suspend fun <T> appleRetryAdminWithExclusiveLock(
        block: suspend () -> T,
    ): T {
        appleRetryAdminEnsurePrivateDirectory(normalizedDirectoryPath)
        val descriptor = appleRetryAdminOpenOwnerOnly(lockFilePath, O_RDWR or O_CREAT)
        try {
            appleRetryAdminAcquireExclusiveLock(descriptor)
            return try {
                currentCoroutineContext().ensureActive()
                block()
            } finally {
                flock(descriptor, LOCK_UN)
            }
        } finally {
            close(descriptor)
        }
    }

    private suspend fun appleRetryAdminAcquireExclusiveLock(descriptor: Int) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (flock(descriptor, LOCK_EX or LOCK_NB) == 0) return
            when (errno) {
                EINTR -> Unit
                EAGAIN, EWOULDBLOCK -> delay(APPLE_RETRY_ADMIN_LOCK_RETRY_DELAY_MILLISECONDS)
                else -> throw AppleRetryAdministrationFileException()
            }
        }
    }

    private fun appleRetryAdminReadRecords(): MutableMap<String, RetryAdministrationStateRecord> {
        val content = appleRetryAdminReadUtf8FileOrNull(dataFilePath) ?: return linkedMapOf()
        return AppleRetryAdministrationStateFileCodec.decode(content)
    }

    private fun appleRetryAdminWriteRecords(
        records: Map<String, RetryAdministrationStateRecord>,
    ) {
        val content = AppleRetryAdministrationStateFileCodec.encode(records)
        appleRetryAdminWriteUtf8FileAtomically(
            temporaryPath = temporaryFilePath,
            destinationPath = dataFilePath,
            content = content,
        )
    }

    public companion object {
        public const val DEFAULT_FILE_NAME: String =
            "dataloom-retry-administration-state-v1.tsv"
    }
}

private object AppleRetryAdministrationStoreError {
    fun fileFailure(): DataLoomError = error(
        code = "RETRY_ADMIN_APPLE_FILE_IO_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "An Apple retry-administration file operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "RETRY_ADMIN_APPLE_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple retry-administration state failed integrity validation.",
    )

    fun stateLimitExceeded(): DataLoomError = error(
        code = "RETRY_ADMIN_APPLE_STATE_LIMIT_EXCEEDED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple retry-administration state exceeds its bounded limit.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "RETRY_ADMIN_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The retry-administration state record version is exhausted.",
    )

    private fun error(
        code: String,
        category: ErrorCategory,
        recoverability: Recoverability,
        message: String,
    ): DataLoomError = Error(
        code = ErrorCode(code),
        category = category,
        severity = ErrorSeverity.ERROR,
        recoverability = recoverability,
        message = message,
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
