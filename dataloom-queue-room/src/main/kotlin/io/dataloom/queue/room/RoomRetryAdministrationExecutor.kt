package io.dataloom.queue.room

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationExecutionResult
import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.time.DataLoomClock
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.RetryAdministrationExecutionEntityResult
import io.dataloom.queue.room.internal.RetryAdministrationExecutionIntegrityException
import io.dataloom.queue.room.internal.RetryAdministrationExecutionVersionExhaustedException

/**
 * Android Room executor for authorized administrative retry commands.
 *
 * The target queue entry and retry-administration command must live in the same
 * [DataLoomRoomDatabase]. One Room transaction validates the immutable command,
 * authorization evidence, policy-safe effective classification, terminal queue
 * state, and exact canonical failure snapshot. It then requeues the entry and
 * advances the durable command to `SUCCEEDED` atomically.
 *
 * Retry attempt, retry-budget, workflow-deadline, synchronization request, and
 * queue metadata are preserved. The command execution does not evaluate normal
 * retry delay policy and does not extend an accepted workflow deadline.
 *
 * A repeated command whose durable receipt is already `SUCCEEDED` returns
 * [RetryAdministrationExecutionResult.Applied] without a second queue mutation.
 */
public class RoomRetryAdministrationExecutor(
    database: DataLoomRoomDatabase,
    private val clock: DataLoomClock,
) : RetryAdministrationExecutor {
    private val dao = database.retryAdministrationExecutionDao()

    override suspend fun execute(
        command: AuthorizedRetryAdministrationCommand,
    ): RetryAdministrationExecutionResult {
        val observedAt = clock.now()
        return try {
            when (val result = dao.execute(command, observedAt.epochMilliseconds)) {
                RetryAdministrationExecutionEntityResult.Applied ->
                    RetryAdministrationExecutionResult.Applied
                RetryAdministrationExecutionEntityResult.ClockRegression ->
                    RetryAdministrationExecutionResult.Failed(
                        RoomRetryAdministrationExecutorError.clockRegression(),
                    )
                is RetryAdministrationExecutionEntityResult.Rejected ->
                    RetryAdministrationExecutionResult.Rejected(result.reasonCode)
            }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (_: RetryAdministrationExecutionVersionExhaustedException) {
            RetryAdministrationExecutionResult.Failed(
                RoomRetryAdministrationExecutorError.versionExhausted(),
            )
        } catch (_: RetryAdministrationExecutionIntegrityException) {
            RetryAdministrationExecutionResult.Failed(
                RoomRetryAdministrationExecutorError.integrityFailure(),
            )
        } catch (_: Exception) {
            RetryAdministrationExecutionResult.Failed(
                RoomRetryAdministrationExecutorError.databaseFailure(),
            )
        }
    }
}

private object RoomRetryAdministrationExecutorError {
    fun databaseFailure(): DataLoomError = error(
        code = "RETRY_ADMIN_ROOM_EXECUTOR_DATABASE_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "An administrative retry database transaction failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "RETRY_ADMIN_ROOM_EXECUTOR_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Durable administrative retry or queue state failed integrity validation.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "RETRY_ADMIN_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The retry-administration record version is exhausted.",
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
