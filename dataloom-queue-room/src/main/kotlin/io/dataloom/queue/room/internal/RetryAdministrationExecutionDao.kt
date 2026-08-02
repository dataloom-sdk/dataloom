package io.dataloom.queue.room.internal

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationCommandStatus

/**
 * Atomic Room boundary for applying one authorized administrative retry.
 *
 * The queue mutation and durable command receipt are committed in the same
 * SQLite transaction. A repeated command whose durable state is already
 * `SUCCEEDED` returns [RetryAdministrationExecutionEntityResult.Applied]
 * without mutating the queue again.
 */
@Dao
internal abstract class RetryAdministrationExecutionDao {
    @Query("SELECT * FROM retry_administration_states WHERE command_id = :commandId LIMIT 1")
    protected abstract suspend fun loadCommand(commandId: String): RetryAdministrationStateEntity?

    @Query("SELECT * FROM queue_entries WHERE entry_id = :entryId LIMIT 1")
    protected abstract suspend fun loadQueueEntry(entryId: String): QueueEntryEntity?

    @Query(
        """
        UPDATE queue_entries
        SET state = CASE
                WHEN retry_attempt_number IS NULL THEN 'PENDING'
                ELSE 'RETRY_WAITING'
            END,
            available_at_ms = :availableAtMs,
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
          AND state = :expectedState
          AND last_error_code = :expectedErrorCode
          AND last_error_category = :expectedErrorCategory
          AND last_error_severity = :expectedErrorSeverity
          AND last_error_recoverability = :expectedErrorRecoverability
          AND last_error_message = :expectedErrorMessage
        """,
    )
    protected abstract suspend fun requeueTerminalEntry(
        entryId: String,
        expectedState: String,
        expectedErrorCode: String,
        expectedErrorCategory: String,
        expectedErrorSeverity: String,
        expectedErrorRecoverability: String,
        expectedErrorMessage: String,
        availableAtMs: Long,
    ): Int

    @Query(
        """
        UPDATE retry_administration_states
        SET status = 'SUCCEEDED',
            updated_at_ms = :updatedAtMs,
            rejection_reason_code = NULL,
            execution_error_code = NULL,
            execution_error_category = NULL,
            execution_error_severity = NULL,
            execution_error_recoverability = NULL,
            record_version = :nextVersion
        WHERE command_id = :commandId
          AND status = 'AUTHORIZED'
          AND record_version = :expectedVersion
          AND authorization_id = :authorizationId
          AND effective_recoverability = :effectiveRecoverability
        """,
    )
    protected abstract suspend fun markCommandSucceeded(
        commandId: String,
        expectedVersion: Long,
        nextVersion: Long,
        authorizationId: String,
        effectiveRecoverability: String,
        updatedAtMs: Long,
    ): Int

    @Transaction
    open suspend fun execute(
        command: AuthorizedRetryAdministrationCommand,
        observedAtMs: Long,
    ): RetryAdministrationExecutionEntityResult {
        val durableEntity = loadCommand(command.request.commandId.value)
            ?: return RetryAdministrationExecutionEntityResult.Rejected(COMMAND_MISSING)
        val durableRecord = try {
            durableEntity.toRecord()
        } catch (invalid: Exception) {
            throw RetryAdministrationExecutionIntegrityException(invalid)
        }

        if (durableRecord.state.request != command.request) {
            return RetryAdministrationExecutionEntityResult.Rejected(COMMAND_CONFLICT)
        }

        val durableAuthorizationId = durableRecord.state.authorizationId
        val durableEffectiveRecoverability = durableRecord.state.effectiveRecoverability
        if (durableRecord.state.status == RetryAdministrationCommandStatus.SUCCEEDED) {
            return if (
                durableAuthorizationId == command.authorizationId &&
                durableEffectiveRecoverability == command.effectiveRecoverability
            ) {
                RetryAdministrationExecutionEntityResult.Applied
            } else {
                RetryAdministrationExecutionEntityResult.Rejected(AUTHORIZATION_MISMATCH)
            }
        }
        if (durableRecord.state.status != RetryAdministrationCommandStatus.AUTHORIZED) {
            return RetryAdministrationExecutionEntityResult.Rejected(COMMAND_NOT_AUTHORIZED)
        }
        if (
            durableAuthorizationId != command.authorizationId ||
            durableEffectiveRecoverability != command.effectiveRecoverability
        ) {
            return RetryAdministrationExecutionEntityResult.Rejected(AUTHORIZATION_MISMATCH)
        }
        if (command.effectiveRecoverability != Recoverability.RECOVERABLE) {
            return RetryAdministrationExecutionEntityResult.Rejected(EFFECTIVE_RECOVERABILITY_INVALID)
        }
        if (!command.isPolicySafe()) {
            return RetryAdministrationExecutionEntityResult.Rejected(RECLASSIFICATION_REQUIRED)
        }
        if (
            observedAtMs < durableRecord.state.updatedAt.epochMilliseconds ||
            observedAtMs < command.request.requestedAt.epochMilliseconds
        ) {
            return RetryAdministrationExecutionEntityResult.ClockRegression
        }
        if (durableRecord.version == Long.MAX_VALUE) {
            throw RetryAdministrationExecutionVersionExhaustedException()
        }

        val queueEntity = loadQueueEntry(command.request.queueEntryId.value)
            ?: return RetryAdministrationExecutionEntityResult.Rejected(TARGET_MISSING)
        val queueEntry = try {
            queueEntity.toDomain()
        } catch (invalid: Exception) {
            throw RetryAdministrationExecutionIntegrityException(invalid)
        }
        if (
            queueEntry.state != QueueEntryState.FAILED &&
            queueEntry.state != QueueEntryState.DEAD_LETTER
        ) {
            return RetryAdministrationExecutionEntityResult.Rejected(TARGET_NOT_TERMINAL_FAILURE)
        }
        val durableFailure = queueEntry.lastError
            ?: return RetryAdministrationExecutionEntityResult.Rejected(TARGET_FAILURE_MISSING)
        val originalFailure = command.request.originalFailure
        if (
            durableFailure.code != originalFailure.code ||
            durableFailure.category != originalFailure.category ||
            durableFailure.severity != originalFailure.severity ||
            durableFailure.recoverability != originalFailure.recoverability
        ) {
            return RetryAdministrationExecutionEntityResult.Rejected(TARGET_FAILURE_MISMATCH)
        }
        if (observedAtMs < queueEntry.enqueuedAt.epochMilliseconds) {
            return RetryAdministrationExecutionEntityResult.ClockRegression
        }

        val queueAffected = requeueTerminalEntry(
            entryId = queueEntity.entryId,
            expectedState = queueEntity.state,
            expectedErrorCode = checkNotNull(queueEntity.lastErrorCode),
            expectedErrorCategory = checkNotNull(queueEntity.lastErrorCategory),
            expectedErrorSeverity = checkNotNull(queueEntity.lastErrorSeverity),
            expectedErrorRecoverability = checkNotNull(queueEntity.lastErrorRecoverability),
            expectedErrorMessage = checkNotNull(queueEntity.lastErrorMessage),
            availableAtMs = observedAtMs,
        )
        if (queueAffected != 1) {
            throw RetryAdministrationExecutionIntegrityException()
        }

        val commandAffected = markCommandSucceeded(
            commandId = durableEntity.commandId,
            expectedVersion = durableRecord.version,
            nextVersion = durableRecord.version + 1L,
            authorizationId = command.authorizationId.value,
            effectiveRecoverability = command.effectiveRecoverability.name,
            updatedAtMs = observedAtMs,
        )
        if (commandAffected != 1) {
            throw RetryAdministrationExecutionIntegrityException()
        }
        return RetryAdministrationExecutionEntityResult.Applied
    }
}

private fun AuthorizedRetryAdministrationCommand.isPolicySafe(): Boolean = when (request.action) {
    RetryAdministrationAction.RECLASSIFY_AND_REQUEUE -> true
    RetryAdministrationAction.REQUEUE ->
        request.originalFailure.recoverability == Recoverability.RECOVERABLE &&
            !request.originalFailure.category.isProtectedFromAutomaticRetry()
}

private fun ErrorCategory.isProtectedFromAutomaticRetry(): Boolean = when (this) {
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

internal sealed interface RetryAdministrationExecutionEntityResult {
    data object Applied : RetryAdministrationExecutionEntityResult
    data object ClockRegression : RetryAdministrationExecutionEntityResult
    data class Rejected(val reasonCode: String) : RetryAdministrationExecutionEntityResult
}

internal class RetryAdministrationExecutionIntegrityException(
    cause: Throwable? = null,
) : Exception(cause)

internal class RetryAdministrationExecutionVersionExhaustedException : Exception()

internal const val COMMAND_MISSING: String = "RETRY_ADMIN_COMMAND_MISSING"
internal const val COMMAND_CONFLICT: String = "RETRY_ADMIN_COMMAND_CONFLICT"
internal const val COMMAND_NOT_AUTHORIZED: String = "RETRY_ADMIN_COMMAND_NOT_AUTHORIZED"
internal const val AUTHORIZATION_MISMATCH: String = "RETRY_ADMIN_AUTHORIZATION_MISMATCH"
internal const val EFFECTIVE_RECOVERABILITY_INVALID: String = "RETRY_ADMIN_EFFECTIVE_RECOVERABILITY_INVALID"
internal const val RECLASSIFICATION_REQUIRED: String = "RETRY_RECLASSIFICATION_REQUIRED"
internal const val TARGET_MISSING: String = "RETRY_ADMIN_TARGET_MISSING"
internal const val TARGET_NOT_TERMINAL_FAILURE: String = "RETRY_ADMIN_TARGET_NOT_TERMINAL_FAILURE"
internal const val TARGET_FAILURE_MISSING: String = "RETRY_ADMIN_TARGET_FAILURE_MISSING"
internal const val TARGET_FAILURE_MISMATCH: String = "RETRY_ADMIN_TARGET_FAILURE_MISMATCH"
