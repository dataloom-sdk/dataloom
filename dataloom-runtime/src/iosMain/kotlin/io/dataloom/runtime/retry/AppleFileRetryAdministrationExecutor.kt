package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationExecutionResult
import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.APPLE_QUEUE_LOCK_RETRY_DELAY_MILLISECONDS
import io.dataloom.runtime.queue.APPLE_QUEUE_MAX_RETRY_ADMINISTRATION_RECEIPT_COUNT
import io.dataloom.runtime.queue.AppleFileQueueProvider
import io.dataloom.runtime.queue.AppleQueueEntryLimitException
import io.dataloom.runtime.queue.AppleQueueFileException
import io.dataloom.runtime.queue.AppleQueueFileLimitException
import io.dataloom.runtime.queue.AppleQueueMalformedStateException
import io.dataloom.runtime.queue.AppleQueueReceiptLimitException
import io.dataloom.runtime.queue.AppleQueueSnapshot
import io.dataloom.runtime.queue.AppleQueueStateFileCodec
import io.dataloom.runtime.queue.AppleRetryAdministrationReceipt
import io.dataloom.runtime.queue.appleQueueEnsurePrivateDirectory
import io.dataloom.runtime.queue.appleQueueOpenOwnerOnly
import io.dataloom.runtime.queue.appleQueueReadUtf8FileOrNull
import io.dataloom.runtime.queue.appleQueueValidateDirectoryPath
import io.dataloom.runtime.queue.appleQueueValidateFileName
import io.dataloom.runtime.queue.appleQueueWriteUtf8FileAtomically
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
 * Production Apple executor for authorized administrative retries.
 *
 * The executor must target the same directory and file name as the associated
 * [AppleFileQueueProvider]. It takes the provider's process-shared lock, reads a
 * complete backward-compatible queue snapshot, validates the live terminal
 * failure, mutates the queue entry, and writes an immutable command receipt in
 * one crash-durable file replacement.
 *
 * A repeated identical command returns `Applied` from the receipt without a
 * second queue mutation. A command-id collision with different immutable input,
 * authorization evidence, or effective recoverability is rejected.
 *
 * Retry attempt, retry budget, workflow timeout, synchronization request, and
 * safe metadata are preserved. Manual retry does not extend an accepted
 * workflow deadline; an already expired deadline is rejected.
 */
public class AppleFileRetryAdministrationExecutor(
    directoryPath: String,
    private val clock: DataLoomClock,
    fileName: String = AppleFileQueueProvider.DEFAULT_FILE_NAME,
) : RetryAdministrationExecutor {
    private val normalizedDirectoryPath: String = appleQueueValidateDirectoryPath(directoryPath)
    private val validatedFileName: String = appleQueueValidateFileName(fileName)
    private val dataFilePath: String = "$normalizedDirectoryPath/$validatedFileName"
    private val lockFilePath: String = "$dataFilePath.lock"
    private val temporaryFilePath: String = "$dataFilePath.tmp"

    override suspend fun execute(
        command: AuthorizedRetryAdministrationCommand,
    ): RetryAdministrationExecutionResult {
        val observedAt = clock.now()
        return appleRetryAdministrationProtect {
            appleRetryAdministrationWithExclusiveLock {
                appleRetryAdministrationExecuteLocked(command, observedAt)
            }
        }
    }

    private suspend fun appleRetryAdministrationExecuteLocked(
        command: AuthorizedRetryAdministrationCommand,
        observedAt: DataLoomInstant,
    ): RetryAdministrationExecutionResult {
        val snapshot = appleRetryAdministrationReadSnapshot()
        val commandId = command.request.commandId.value
        val existingReceipt = snapshot.retryAdministrationReceipts[commandId]
        if (existingReceipt != null) {
            return if (existingReceipt.matches(command)) {
                RetryAdministrationExecutionResult.Applied
            } else {
                RetryAdministrationExecutionResult.Rejected(
                    APPLE_RETRY_ADMIN_COMMAND_CONFLICT,
                )
            }
        }

        if (snapshot.retryAdministrationReceipts.size >=
            APPLE_QUEUE_MAX_RETRY_ADMINISTRATION_RECEIPT_COUNT
        ) {
            throw AppleQueueReceiptLimitException()
        }
        if (command.effectiveRecoverability != Recoverability.RECOVERABLE) {
            return RetryAdministrationExecutionResult.Rejected(
                APPLE_RETRY_ADMIN_EFFECTIVE_RECOVERABILITY_INVALID,
            )
        }
        if (!command.isAppleRetryAdministrationPolicySafe()) {
            return RetryAdministrationExecutionResult.Rejected(
                APPLE_RETRY_ADMIN_RECLASSIFICATION_REQUIRED,
            )
        }
        if (observedAt.epochMilliseconds < command.request.requestedAt.epochMilliseconds) {
            return RetryAdministrationExecutionResult.Failed(
                AppleRetryAdministrationExecutorError.clockRegression(),
            )
        }

        val entry = snapshot.entries[command.request.queueEntryId.value]
            ?: return RetryAdministrationExecutionResult.Rejected(
                APPLE_RETRY_ADMIN_TARGET_MISSING,
            )
        if (entry.state != QueueEntryState.FAILED &&
            entry.state != QueueEntryState.DEAD_LETTER
        ) {
            return RetryAdministrationExecutionResult.Rejected(
                APPLE_RETRY_ADMIN_TARGET_NOT_TERMINAL_FAILURE,
            )
        }
        val durableFailure = entry.lastError
            ?: return RetryAdministrationExecutionResult.Rejected(
                APPLE_RETRY_ADMIN_TARGET_FAILURE_MISSING,
            )
        val originalFailure = command.request.originalFailure
        if (durableFailure.code != originalFailure.code ||
            durableFailure.category != originalFailure.category ||
            durableFailure.severity != originalFailure.severity ||
            durableFailure.recoverability != originalFailure.recoverability
        ) {
            return RetryAdministrationExecutionResult.Rejected(
                APPLE_RETRY_ADMIN_TARGET_FAILURE_MISMATCH,
            )
        }
        if (observedAt.epochMilliseconds < entry.enqueuedAt.epochMilliseconds ||
            observedAt.epochMilliseconds < entry.availableAt.epochMilliseconds ||
            entry.workflowTimeoutState?.let { timeout ->
                observedAt.epochMilliseconds < timeout.startedAt.epochMilliseconds
            } == true
        ) {
            return RetryAdministrationExecutionResult.Failed(
                AppleRetryAdministrationExecutorError.clockRegression(),
            )
        }
        if (entry.workflowTimeoutState?.let { timeout ->
                observedAt.epochMilliseconds >= timeout.deadline.epochMilliseconds
            } == true
        ) {
            return RetryAdministrationExecutionResult.Rejected(
                APPLE_RETRY_ADMIN_TARGET_WORKFLOW_DEADLINE_EXPIRED,
            )
        }

        snapshot.entries[entry.id.value] = entry.copy(
            state = if (entry.retryAttempt == null) {
                QueueEntryState.PENDING
            } else {
                QueueEntryState.RETRY_WAITING
            },
            availableAt = observedAt,
            lease = null,
            lastError = null,
        )
        snapshot.retryAdministrationReceipts[commandId] =
            AppleRetryAdministrationReceipt(
                command = command,
                appliedAt = observedAt,
            )
        currentCoroutineContext().ensureActive()
        appleRetryAdministrationWriteSnapshot(snapshot)
        return RetryAdministrationExecutionResult.Applied
    }

    private suspend fun <T> appleRetryAdministrationWithExclusiveLock(
        block: suspend () -> T,
    ): T {
        appleQueueEnsurePrivateDirectory(normalizedDirectoryPath)
        val descriptor = appleQueueOpenOwnerOnly(lockFilePath, O_RDWR or O_CREAT)
        try {
            appleRetryAdministrationAcquireExclusiveLock(descriptor)
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

    private suspend fun appleRetryAdministrationAcquireExclusiveLock(descriptor: Int) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (flock(descriptor, LOCK_EX or LOCK_NB) == 0) return
            when (errno) {
                EINTR -> Unit
                EAGAIN, EWOULDBLOCK -> delay(APPLE_QUEUE_LOCK_RETRY_DELAY_MILLISECONDS)
                else -> throw AppleQueueFileException()
            }
        }
    }

    private fun appleRetryAdministrationReadSnapshot(): AppleQueueSnapshot {
        val content = appleQueueReadUtf8FileOrNull(dataFilePath) ?: return AppleQueueSnapshot()
        return AppleQueueStateFileCodec.decodeSnapshot(content)
    }

    private fun appleRetryAdministrationWriteSnapshot(snapshot: AppleQueueSnapshot) {
        appleQueueWriteUtf8FileAtomically(
            temporaryPath = temporaryFilePath,
            destinationPath = dataFilePath,
            content = AppleQueueStateFileCodec.encodeSnapshot(snapshot),
        )
    }

    private suspend fun appleRetryAdministrationProtect(
        block: suspend () -> RetryAdministrationExecutionResult,
    ): RetryAdministrationExecutionResult = try {
        currentCoroutineContext().ensureActive()
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AppleQueueReceiptLimitException) {
        RetryAdministrationExecutionResult.Failed(
            AppleRetryAdministrationExecutorError.receiptLimitExceeded(),
        )
    } catch (_: AppleQueueFileLimitException) {
        RetryAdministrationExecutionResult.Failed(
            AppleRetryAdministrationExecutorError.stateLimitExceeded(),
        )
    } catch (_: AppleQueueEntryLimitException) {
        RetryAdministrationExecutionResult.Failed(
            AppleRetryAdministrationExecutorError.stateLimitExceeded(),
        )
    } catch (_: AppleQueueMalformedStateException) {
        RetryAdministrationExecutionResult.Failed(
            AppleRetryAdministrationExecutorError.integrityFailure(),
        )
    } catch (_: AppleQueueFileException) {
        RetryAdministrationExecutionResult.Failed(
            AppleRetryAdministrationExecutorError.fileFailure(),
        )
    } catch (_: Exception) {
        RetryAdministrationExecutionResult.Failed(
            AppleRetryAdministrationExecutorError.fileFailure(),
        )
    }
}

private fun AuthorizedRetryAdministrationCommand.isAppleRetryAdministrationPolicySafe(): Boolean =
    when (request.action) {
        RetryAdministrationAction.RECLASSIFY_AND_REQUEUE -> true
        RetryAdministrationAction.REQUEUE ->
            request.originalFailure.recoverability == Recoverability.RECOVERABLE &&
                !request.originalFailure.category.isAppleRetryAdministrationProtectedCategory()
    }

private fun ErrorCategory.isAppleRetryAdministrationProtectedCategory(): Boolean = when (this) {
    ErrorCategory.AUTHENTICATION,
    ErrorCategory.AUTHORIZATION,
    ErrorCategory.SERIALIZATION,
    ErrorCategory.VALIDATION,
    ErrorCategory.CONFIGURATION,
    ErrorCategory.POLICY,
    ErrorCategory.CONFLICT,
    ErrorCategory.SECURITY,
    -> true

    ErrorCategory.NETWORK,
    ErrorCategory.STORAGE,
    ErrorCategory.QUEUE,
    ErrorCategory.SCHEDULER,
    ErrorCategory.STATE,
    ErrorCategory.PROVIDER,
    ErrorCategory.PLUGIN,
    ErrorCategory.INTERNAL,
    -> false
}

private object AppleRetryAdministrationExecutorError {
    fun fileFailure(): DataLoomError = error(
        code = "RETRY_ADMIN_APPLE_QUEUE_FILE_IO_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "An Apple administrative retry queue-file operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "RETRY_ADMIN_APPLE_QUEUE_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple queue or retry receipt state failed integrity validation.",
    )

    fun stateLimitExceeded(): DataLoomError = error(
        code = "RETRY_ADMIN_APPLE_QUEUE_STATE_LIMIT_EXCEEDED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple queue state exceeds its bounded limit.",
    )

    fun receiptLimitExceeded(): DataLoomError = error(
        code = "RETRY_ADMIN_APPLE_RECEIPT_LIMIT_EXCEEDED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple administrative retry receipts reached their bounded limit.",
    )

    fun clockRegression(): DataLoomError = error(
        code = "RETRY_ADMIN_EXECUTION_CLOCK_REGRESSION",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Administrative retry execution observed wall-clock regression.",
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
    ) : DataLoomError
}

private const val APPLE_RETRY_ADMIN_COMMAND_CONFLICT: String =
    "RETRY_ADMIN_COMMAND_CONFLICT"
private const val APPLE_RETRY_ADMIN_EFFECTIVE_RECOVERABILITY_INVALID: String =
    "RETRY_ADMIN_EFFECTIVE_RECOVERABILITY_INVALID"
private const val APPLE_RETRY_ADMIN_RECLASSIFICATION_REQUIRED: String =
    "RETRY_RECLASSIFICATION_REQUIRED"
private const val APPLE_RETRY_ADMIN_TARGET_MISSING: String =
    "RETRY_ADMIN_TARGET_MISSING"
private const val APPLE_RETRY_ADMIN_TARGET_NOT_TERMINAL_FAILURE: String =
    "RETRY_ADMIN_TARGET_NOT_TERMINAL_FAILURE"
private const val APPLE_RETRY_ADMIN_TARGET_FAILURE_MISSING: String =
    "RETRY_ADMIN_TARGET_FAILURE_MISSING"
private const val APPLE_RETRY_ADMIN_TARGET_FAILURE_MISMATCH: String =
    "RETRY_ADMIN_TARGET_FAILURE_MISMATCH"
private const val APPLE_RETRY_ADMIN_TARGET_WORKFLOW_DEADLINE_EXPIRED: String =
    "RETRY_ADMIN_TARGET_WORKFLOW_DEADLINE_EXPIRED"
