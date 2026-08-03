package io.dataloom.queue.room.internal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.circuitAdministrationScopeKey
import io.dataloom.queue.room.circuitBreakerScopeKey
import io.dataloom.queue.room.toCircuitAdministrationRecord
import io.dataloom.queue.room.toCircuitBreakerEntity
import io.dataloom.queue.room.toCircuitBreakerRecord

/**
 * Atomic Room boundary for applying one authorized circuit-administration command.
 *
 * The circuit mutation and durable command receipt are committed in one SQLite
 * transaction. A repeated `SUCCEEDED` command returns its exact durable result
 * without applying a second mutation.
 */
@Dao
internal abstract class CircuitAdministrationExecutionDao {
    @Query("SELECT * FROM circuit_administration_states WHERE command_id = :commandId LIMIT 1")
    protected abstract suspend fun loadCommand(commandId: String): CircuitAdministrationStateEntity?

    @Query("SELECT * FROM circuit_breaker_states WHERE scope_key = :scopeKey LIMIT 1")
    protected abstract suspend fun loadCircuit(scopeKey: String): CircuitBreakerStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCircuitIfMissing(entity: CircuitBreakerStateEntity): Long

    @Query(
        """
        UPDATE circuit_breaker_states
        SET scope_kind = :scopeKind,
            provider_id = :providerId,
            operation = :operation,
            tenant_id = :tenantId,
            workflow_id = :workflowId,
            phase = :phase,
            consecutive_failures = :consecutiveFailures,
            failure_window_started_at_ms = :failureWindowStartedAtMs,
            open_until_ms = :openUntilMs,
            probe_generation = :probeGeneration,
            probe_in_flight = :probeInFlight,
            probe_lease_until_ms = :probeLeaseUntilMs,
            updated_at_ms = :updatedAtMs,
            record_version = :nextVersion
        WHERE scope_key = :scopeKey AND record_version = :expectedVersion
        """,
    )
    protected abstract suspend fun updateCircuitIfVersionMatches(
        scopeKey: String,
        expectedVersion: Long,
        nextVersion: Long,
        scopeKind: String,
        providerId: String?,
        operation: String?,
        tenantId: String?,
        workflowId: String?,
        phase: String,
        consecutiveFailures: Int,
        failureWindowStartedAtMs: Long?,
        openUntilMs: Long?,
        probeGeneration: Long,
        probeInFlight: Boolean,
        probeLeaseUntilMs: Long?,
        updatedAtMs: Long,
    ): Int

    @Query(
        """
        UPDATE circuit_administration_states
        SET status = 'SUCCEEDED',
            updated_at_ms = :updatedAtMs,
            rejection_reason_code = NULL,
            result_phase = :resultPhase,
            result_consecutive_failures = :resultConsecutiveFailures,
            result_failure_window_started_at_ms = :resultFailureWindowStartedAtMs,
            result_open_until_ms = :resultOpenUntilMs,
            result_probe_generation = :resultProbeGeneration,
            result_probe_in_flight = :resultProbeInFlight,
            result_probe_lease_until_ms = :resultProbeLeaseUntilMs,
            result_updated_at_ms = :resultUpdatedAtMs,
            result_record_version = :resultRecordVersion,
            execution_error_code = NULL,
            execution_error_category = NULL,
            execution_error_severity = NULL,
            execution_error_recoverability = NULL,
            record_version = :nextVersion
        WHERE command_id = :commandId
          AND status = 'AUTHORIZED'
          AND record_version = :expectedVersion
          AND authorization_id = :authorizationId
        """,
    )
    protected abstract suspend fun markCommandSucceeded(
        commandId: String,
        expectedVersion: Long,
        nextVersion: Long,
        authorizationId: String,
        updatedAtMs: Long,
        resultPhase: String,
        resultConsecutiveFailures: Int,
        resultFailureWindowStartedAtMs: Long?,
        resultOpenUntilMs: Long?,
        resultProbeGeneration: Long,
        resultProbeInFlight: Boolean,
        resultProbeLeaseUntilMs: Long?,
        resultUpdatedAtMs: Long,
        resultRecordVersion: Long,
    ): Int

    @Transaction
    open suspend fun execute(
        command: AuthorizedCircuitAdministrationCommand,
        observedAtMs: Long,
    ): CircuitAdministrationExecutionEntityResult {
        val durableEntity = loadCommand(command.request.commandId.value)
            ?: return CircuitAdministrationExecutionEntityResult.Rejected(CIRCUIT_COMMAND_MISSING)
        val durableRecord = try {
            durableEntity.toCircuitAdministrationRecord()
        } catch (invalid: Exception) {
            throw CircuitAdministrationExecutionIntegrityException(invalid)
        }
        val durableState = durableRecord.state
        if (durableState.request != command.request) {
            return CircuitAdministrationExecutionEntityResult.Rejected(CIRCUIT_COMMAND_CONFLICT)
        }

        if (durableState.status == CircuitAdministrationCommandStatus.SUCCEEDED) {
            return if (durableState.authorizationId == command.authorizationId) {
                CircuitAdministrationExecutionEntityResult.Applied(
                    checkNotNull(durableState.resultingRecord),
                )
            } else {
                CircuitAdministrationExecutionEntityResult.Rejected(CIRCUIT_AUTHORIZATION_MISMATCH)
            }
        }
        if (durableState.status != CircuitAdministrationCommandStatus.AUTHORIZED) {
            return CircuitAdministrationExecutionEntityResult.Rejected(CIRCUIT_COMMAND_NOT_AUTHORIZED)
        }
        if (durableState.authorizationId != command.authorizationId) {
            return CircuitAdministrationExecutionEntityResult.Rejected(CIRCUIT_AUTHORIZATION_MISMATCH)
        }
        if (
            observedAtMs < durableState.updatedAt.epochMilliseconds ||
            observedAtMs < command.request.requestedAt.epochMilliseconds
        ) {
            return CircuitAdministrationExecutionEntityResult.ClockRegression
        }
        if (
            command.request.action == CircuitAdministrationAction.OPEN &&
            observedAtMs >= checkNotNull(command.request.openUntil).epochMilliseconds
        ) {
            return CircuitAdministrationExecutionEntityResult.Rejected(CIRCUIT_OPEN_DEADLINE_EXPIRED)
        }
        if (durableRecord.version == Long.MAX_VALUE) {
            throw CircuitAdministrationExecutionVersionExhaustedException()
        }

        val scope = command.request.scope
        val expectedScopeKey = circuitAdministrationScopeKey(scope)
        check(expectedScopeKey == circuitBreakerScopeKey(scope))
        val currentEntity = loadCircuit(expectedScopeKey)
        val current = try {
            currentEntity?.toCircuitBreakerRecord()
        } catch (invalid: Exception) {
            throw CircuitAdministrationExecutionIntegrityException(invalid)
        }
        if (current != null && current.state.scope != scope) {
            throw CircuitAdministrationExecutionIntegrityException()
        }
        if (current != null && observedAtMs < current.state.updatedAt.epochMilliseconds) {
            return CircuitAdministrationExecutionEntityResult.ClockRegression
        }
        if (current?.version == Long.MAX_VALUE) {
            throw CircuitAdministrationExecutionVersionExhaustedException()
        }

        val observedAt = DataLoomInstant(observedAtMs)
        val nextState = nextState(command, current?.state, observedAt)
        val nextCircuitVersion = current?.version?.plus(1L) ?: 0L
        val nextEntity = nextState.toCircuitBreakerEntity(nextCircuitVersion)
        val circuitAffected = if (current == null) {
            if (insertCircuitIfMissing(nextEntity) == -1L) 0 else 1
        } else {
            updateCircuitIfVersionMatches(
                scopeKey = nextEntity.scopeKey,
                expectedVersion = current.version,
                nextVersion = nextCircuitVersion,
                scopeKind = nextEntity.scopeKind,
                providerId = nextEntity.providerId,
                operation = nextEntity.operation,
                tenantId = nextEntity.tenantId,
                workflowId = nextEntity.workflowId,
                phase = nextEntity.phase,
                consecutiveFailures = nextEntity.consecutiveFailures,
                failureWindowStartedAtMs = nextEntity.failureWindowStartedAtMs,
                openUntilMs = nextEntity.openUntilMs,
                probeGeneration = nextEntity.probeGeneration,
                probeInFlight = nextEntity.probeInFlight,
                probeLeaseUntilMs = nextEntity.probeLeaseUntilMs,
                updatedAtMs = nextEntity.updatedAtMs,
            )
        }
        if (circuitAffected != 1) {
            throw CircuitAdministrationExecutionIntegrityException()
        }

        val resultingRecord = CircuitBreakerStateRecord(nextState, nextCircuitVersion)
        val commandAffected = markCommandSucceeded(
            commandId = durableEntity.commandId,
            expectedVersion = durableRecord.version,
            nextVersion = durableRecord.version + 1L,
            authorizationId = command.authorizationId.value,
            updatedAtMs = observedAtMs,
            resultPhase = nextState.phase.name,
            resultConsecutiveFailures = nextState.consecutiveFailures,
            resultFailureWindowStartedAtMs = nextState.failureWindowStartedAt?.epochMilliseconds,
            resultOpenUntilMs = nextState.openUntil?.epochMilliseconds,
            resultProbeGeneration = nextState.probeGeneration,
            resultProbeInFlight = nextState.probeInFlight,
            resultProbeLeaseUntilMs = nextState.probeLeaseUntil?.epochMilliseconds,
            resultUpdatedAtMs = nextState.updatedAt.epochMilliseconds,
            resultRecordVersion = nextCircuitVersion,
        )
        if (commandAffected != 1) {
            throw CircuitAdministrationExecutionIntegrityException()
        }
        return CircuitAdministrationExecutionEntityResult.Applied(resultingRecord)
    }

    private fun nextState(
        command: AuthorizedCircuitAdministrationCommand,
        current: CircuitBreakerState?,
        observedAt: DataLoomInstant,
    ): CircuitBreakerState {
        val probeGeneration = current?.probeGeneration ?: 0L
        val preserveClosedFailures =
            command.request.action == CircuitAdministrationAction.CLOSE &&
                current?.phase == CircuitBreakerPhase.CLOSED
        return when (command.request.action) {
            CircuitAdministrationAction.OPEN -> CircuitBreakerState(
                scope = command.request.scope,
                phase = CircuitBreakerPhase.OPEN,
                consecutiveFailures = 0,
                failureWindowStartedAt = null,
                openUntil = checkNotNull(command.request.openUntil),
                probeGeneration = probeGeneration,
                probeInFlight = false,
                updatedAt = observedAt,
            )
            CircuitAdministrationAction.CLOSE,
            CircuitAdministrationAction.RESET,
            -> CircuitBreakerState(
                scope = command.request.scope,
                phase = CircuitBreakerPhase.CLOSED,
                consecutiveFailures = if (preserveClosedFailures) {
                    checkNotNull(current).consecutiveFailures
                } else {
                    0
                },
                failureWindowStartedAt = if (preserveClosedFailures) {
                    checkNotNull(current).failureWindowStartedAt
                } else {
                    null
                },
                openUntil = null,
                probeGeneration = probeGeneration,
                probeInFlight = false,
                updatedAt = observedAt,
            )
        }
    }
}

internal sealed interface CircuitAdministrationExecutionEntityResult {
    data class Applied(
        val record: CircuitBreakerStateRecord,
    ) : CircuitAdministrationExecutionEntityResult

    data object ClockRegression : CircuitAdministrationExecutionEntityResult

    data class Rejected(
        val reasonCode: String,
    ) : CircuitAdministrationExecutionEntityResult
}

internal class CircuitAdministrationExecutionIntegrityException(
    cause: Throwable? = null,
) : Exception(cause)

internal class CircuitAdministrationExecutionVersionExhaustedException : Exception()

private const val CIRCUIT_COMMAND_MISSING: String = "CIRCUIT_ADMIN_COMMAND_MISSING"
private const val CIRCUIT_COMMAND_CONFLICT: String = "CIRCUIT_ADMIN_COMMAND_CONFLICT"
private const val CIRCUIT_COMMAND_NOT_AUTHORIZED: String = "CIRCUIT_ADMIN_COMMAND_NOT_AUTHORIZED"
private const val CIRCUIT_AUTHORIZATION_MISMATCH: String =
    "CIRCUIT_ADMIN_AUTHORIZATION_MISMATCH"
private const val CIRCUIT_OPEN_DEADLINE_EXPIRED: String =
    "CIRCUIT_ADMINISTRATION_OPEN_DEADLINE_EXPIRED"
