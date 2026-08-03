package io.dataloom.queue.room.internal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Atomic Room DAO for versioned circuit-administration command and audit state. */
@Dao
internal abstract class CircuitAdministrationStateDao {
    @Query("SELECT * FROM circuit_administration_states WHERE command_id = :commandId LIMIT 1")
    abstract suspend fun load(commandId: String): CircuitAdministrationStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfMissing(entity: CircuitAdministrationStateEntity): Long

    @Query(
        """
        UPDATE circuit_administration_states
        SET status = :status,
            authorization_id = :authorizationId,
            updated_at_ms = :updatedAtMs,
            rejection_reason_code = :rejectionReasonCode,
            result_phase = :resultPhase,
            result_consecutive_failures = :resultConsecutiveFailures,
            result_failure_window_started_at_ms = :resultFailureWindowStartedAtMs,
            result_open_until_ms = :resultOpenUntilMs,
            result_probe_generation = :resultProbeGeneration,
            result_probe_in_flight = :resultProbeInFlight,
            result_probe_lease_until_ms = :resultProbeLeaseUntilMs,
            result_updated_at_ms = :resultUpdatedAtMs,
            result_record_version = :resultRecordVersion,
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
        updatedAtMs: Long,
        rejectionReasonCode: String?,
        resultPhase: String?,
        resultConsecutiveFailures: Int?,
        resultFailureWindowStartedAtMs: Long?,
        resultOpenUntilMs: Long?,
        resultProbeGeneration: Long?,
        resultProbeInFlight: Boolean?,
        resultProbeLeaseUntilMs: Long?,
        resultUpdatedAtMs: Long?,
        resultRecordVersion: Long?,
        executionErrorCode: String?,
        executionErrorCategory: String?,
        executionErrorSeverity: String?,
        executionErrorRecoverability: String?,
    ): Int

    @Transaction
    open suspend fun compareAndSet(
        expectedVersion: Long?,
        next: CircuitAdministrationStateEntity,
    ): CircuitAdministrationCompareAndSetEntityResult {
        if (expectedVersion == null) {
            return if (insertIfMissing(next) != -1L) {
                CircuitAdministrationCompareAndSetEntityResult.Updated(next)
            } else {
                CircuitAdministrationCompareAndSetEntityResult.Conflict(load(next.commandId))
            }
        }

        val current = load(next.commandId)
            ?: return CircuitAdministrationCompareAndSetEntityResult.Conflict(null)
        if (!current.hasSameImmutableCommand(next)) {
            return CircuitAdministrationCompareAndSetEntityResult.Conflict(current)
        }

        val nextVersion = expectedVersion + 1L
        val affected = updateIfVersionMatches(
            commandId = next.commandId,
            expectedVersion = expectedVersion,
            nextVersion = nextVersion,
            status = next.status,
            authorizationId = next.authorizationId,
            updatedAtMs = next.updatedAtMs,
            rejectionReasonCode = next.rejectionReasonCode,
            resultPhase = next.resultPhase,
            resultConsecutiveFailures = next.resultConsecutiveFailures,
            resultFailureWindowStartedAtMs = next.resultFailureWindowStartedAtMs,
            resultOpenUntilMs = next.resultOpenUntilMs,
            resultProbeGeneration = next.resultProbeGeneration,
            resultProbeInFlight = next.resultProbeInFlight,
            resultProbeLeaseUntilMs = next.resultProbeLeaseUntilMs,
            resultUpdatedAtMs = next.resultUpdatedAtMs,
            resultRecordVersion = next.resultRecordVersion,
            executionErrorCode = next.executionErrorCode,
            executionErrorCategory = next.executionErrorCategory,
            executionErrorSeverity = next.executionErrorSeverity,
            executionErrorRecoverability = next.executionErrorRecoverability,
        )
        return if (affected == 1) {
            CircuitAdministrationCompareAndSetEntityResult.Updated(
                next.copy(recordVersion = nextVersion),
            )
        } else {
            CircuitAdministrationCompareAndSetEntityResult.Conflict(load(next.commandId))
        }
    }
}

private fun CircuitAdministrationStateEntity.hasSameImmutableCommand(
    other: CircuitAdministrationStateEntity,
): Boolean =
    commandId == other.commandId &&
        scopeKey == other.scopeKey &&
        scopeKind == other.scopeKind &&
        providerId == other.providerId &&
        operation == other.operation &&
        tenantId == other.tenantId &&
        workflowId == other.workflowId &&
        principalId == other.principalId &&
        requestedAtMs == other.requestedAtMs &&
        action == other.action &&
        reason == other.reason &&
        requestedOpenUntilMs == other.requestedOpenUntilMs

internal sealed interface CircuitAdministrationCompareAndSetEntityResult {
    data class Updated(
        val entity: CircuitAdministrationStateEntity,
    ) : CircuitAdministrationCompareAndSetEntityResult

    data class Conflict(
        val current: CircuitAdministrationStateEntity?,
    ) : CircuitAdministrationCompareAndSetEntityResult
}
