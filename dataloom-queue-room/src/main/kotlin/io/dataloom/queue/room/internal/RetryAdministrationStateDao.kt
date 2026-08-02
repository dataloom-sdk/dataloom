package io.dataloom.queue.room.internal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Atomic Room DAO for versioned retry-administration command and audit state. */
@Dao
internal abstract class RetryAdministrationStateDao {
    @Query("SELECT * FROM retry_administration_states WHERE command_id = :commandId LIMIT 1")
    abstract suspend fun load(commandId: String): RetryAdministrationStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfMissing(entity: RetryAdministrationStateEntity): Long

    @Query(
        """
        UPDATE retry_administration_states
        SET status = :status,
            authorization_id = :authorizationId,
            effective_recoverability = :effectiveRecoverability,
            updated_at_ms = :updatedAtMs,
            rejection_reason_code = :rejectionReasonCode,
            execution_error_code = :executionErrorCode,
            execution_error_category = :executionErrorCategory,
            execution_error_severity = :executionErrorSeverity,
            execution_error_recoverability = :executionErrorRecoverability,
            record_version = :nextVersion
        WHERE command_id = :commandId AND record_version = :expectedVersion
        """,
    )
    protected abstract suspend fun updateIfVersionMatches(
        commandId: String,
        expectedVersion: Long,
        nextVersion: Long,
        status: String,
        authorizationId: String?,
        effectiveRecoverability: String?,
        updatedAtMs: Long,
        rejectionReasonCode: String?,
        executionErrorCode: String?,
        executionErrorCategory: String?,
        executionErrorSeverity: String?,
        executionErrorRecoverability: String?,
    ): Int

    @Transaction
    open suspend fun compareAndSet(
        expectedVersion: Long?,
        next: RetryAdministrationStateEntity,
    ): RetryAdministrationCompareAndSetEntityResult {
        if (expectedVersion == null) {
            return if (insertIfMissing(next) != -1L) {
                RetryAdministrationCompareAndSetEntityResult.Updated(next)
            } else {
                RetryAdministrationCompareAndSetEntityResult.Conflict(load(next.commandId))
            }
        }

        val current = load(next.commandId)
            ?: return RetryAdministrationCompareAndSetEntityResult.Conflict(null)
        if (!current.hasSameImmutableCommand(next)) {
            return RetryAdministrationCompareAndSetEntityResult.Conflict(current)
        }

        val nextVersion = expectedVersion + 1L
        val affected = updateIfVersionMatches(
            commandId = next.commandId,
            expectedVersion = expectedVersion,
            nextVersion = nextVersion,
            status = next.status,
            authorizationId = next.authorizationId,
            effectiveRecoverability = next.effectiveRecoverability,
            updatedAtMs = next.updatedAtMs,
            rejectionReasonCode = next.rejectionReasonCode,
            executionErrorCode = next.executionErrorCode,
            executionErrorCategory = next.executionErrorCategory,
            executionErrorSeverity = next.executionErrorSeverity,
            executionErrorRecoverability = next.executionErrorRecoverability,
        )
        return if (affected == 1) {
            RetryAdministrationCompareAndSetEntityResult.Updated(
                next.copy(recordVersion = nextVersion),
            )
        } else {
            RetryAdministrationCompareAndSetEntityResult.Conflict(load(next.commandId))
        }
    }
}

private fun RetryAdministrationStateEntity.hasSameImmutableCommand(
    other: RetryAdministrationStateEntity,
): Boolean =
    commandId == other.commandId &&
        queueEntryId == other.queueEntryId &&
        principalId == other.principalId &&
        requestedAtMs == other.requestedAtMs &&
        action == other.action &&
        reason == other.reason &&
        originalErrorCode == other.originalErrorCode &&
        originalErrorCategory == other.originalErrorCategory &&
        originalErrorSeverity == other.originalErrorSeverity &&
        originalErrorRecoverability == other.originalErrorRecoverability

internal sealed interface RetryAdministrationCompareAndSetEntityResult {
    data class Updated(
        val entity: RetryAdministrationStateEntity,
    ) : RetryAdministrationCompareAndSetEntityResult

    data class Conflict(
        val current: RetryAdministrationStateEntity?,
    ) : RetryAdministrationCompareAndSetEntityResult
}