package io.dataloom.queue.room

import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCommandState
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationFailureSnapshot
import io.dataloom.api.circuit.CircuitAdministrationLoadResult
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitAdministrationStateRecord
import io.dataloom.api.circuit.CircuitAdministrationStateStore
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerScopeKind
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.CircuitAdministrationCompareAndSetEntityResult
import io.dataloom.queue.room.internal.CircuitAdministrationStateEntity
import io.dataloom.queue.room.internal.DataLoomRoomDatabase

/** Production Android Room implementation of [CircuitAdministrationStateStore]. */
public class RoomCircuitAdministrationStateStore(
    database: DataLoomRoomDatabase,
) : CircuitAdministrationStateStore {
    private val dao = database.circuitAdministrationStateDao()

    override suspend fun load(
        commandId: CircuitAdministrationCommandId,
    ): ProviderOperationResult<CircuitAdministrationLoadResult> = protect {
        val entity = dao.load(commandId.value)
        if (entity == null) {
            CircuitAdministrationLoadResult.Missing
        } else {
            CircuitAdministrationLoadResult.Found(entity.toCircuitAdministrationRecord())
        }
    }

    override suspend fun compareAndSet(
        request: CircuitAdministrationCompareAndSetRequest,
    ): ProviderOperationResult<CircuitAdministrationCompareAndSetResult> {
        if (request.expectedVersion == Long.MAX_VALUE) {
            return ProviderOperationResult.Failure(RoomCircuitAdministrationStoreError.versionExhausted())
        }
        return protect {
            val nextVersion = request.expectedVersion?.plus(1L) ?: 0L
            val next = request.nextState.toCircuitAdministrationEntity(nextVersion)
            when (val result = dao.compareAndSet(request.expectedVersion, next)) {
                is CircuitAdministrationCompareAndSetEntityResult.Updated ->
                    CircuitAdministrationCompareAndSetResult.Updated(
                        result.entity.toCircuitAdministrationRecord(),
                    )
                is CircuitAdministrationCompareAndSetEntityResult.Conflict ->
                    CircuitAdministrationCompareAndSetResult.Conflict(
                        result.current?.toCircuitAdministrationRecord(),
                    )
            }
        }
    }

    private suspend fun <T> protect(block: suspend () -> T): ProviderOperationResult<T> = try {
        ProviderOperationResult.Success(block())
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
    } catch (_: MalformedCircuitAdministrationStateException) {
        ProviderOperationResult.Failure(RoomCircuitAdministrationStoreError.integrityFailure())
    } catch (_: Exception) {
        ProviderOperationResult.Failure(RoomCircuitAdministrationStoreError.databaseFailure())
    }
}

internal fun CircuitAdministrationCommandState.toCircuitAdministrationEntity(
    version: Long,
): CircuitAdministrationStateEntity {
    val scope = request.scope
    val result = resultingRecord
    val execution = executionFailure
    return CircuitAdministrationStateEntity(
        commandId = request.commandId.value,
        scopeKey = circuitAdministrationScopeKey(scope),
        scopeKind = scope.kind.name,
        providerId = scope.providerId?.value,
        operation = scope.operation?.value,
        tenantId = scope.tenantId?.value,
        workflowId = scope.workflowId?.value,
        principalId = request.principalId.value,
        requestedAtMs = request.requestedAt.epochMilliseconds,
        action = request.action.name,
        reason = request.reason.value,
        requestedOpenUntilMs = request.openUntil?.epochMilliseconds,
        status = status.name,
        authorizationId = authorizationId?.value,
        updatedAtMs = updatedAt.epochMilliseconds,
        rejectionReasonCode = rejectionReasonCode,
        resultPhase = result?.state?.phase?.name,
        resultConsecutiveFailures = result?.state?.consecutiveFailures,
        resultFailureWindowStartedAtMs = result?.state?.failureWindowStartedAt?.epochMilliseconds,
        resultOpenUntilMs = result?.state?.openUntil?.epochMilliseconds,
        resultProbeGeneration = result?.state?.probeGeneration,
        resultProbeInFlight = result?.state?.probeInFlight,
        resultProbeLeaseUntilMs = result?.state?.probeLeaseUntil?.epochMilliseconds,
        resultUpdatedAtMs = result?.state?.updatedAt?.epochMilliseconds,
        resultRecordVersion = result?.version,
        executionErrorCode = execution?.code?.value,
        executionErrorCategory = execution?.category?.name,
        executionErrorSeverity = execution?.severity?.name,
        executionErrorRecoverability = execution?.recoverability?.name,
        recordVersion = version,
    )
}

internal fun CircuitAdministrationStateEntity.toCircuitAdministrationRecord():
    CircuitAdministrationStateRecord = try {
    val scope = CircuitBreakerScope(
        kind = CircuitBreakerScopeKind.valueOf(scopeKind),
        providerId = providerId?.let(::ProviderId),
        operation = operation?.let(::RetryOperation),
        tenantId = tenantId?.let(::TenantId),
        workflowId = workflowId?.let(::WorkflowId),
    )
    check(circuitAdministrationScopeKey(scope) == scopeKey) {
        "Persisted circuit-administration scope key does not match its fields."
    }

    val requiredResultColumns = listOf(
        resultPhase,
        resultConsecutiveFailures,
        resultProbeGeneration,
        resultProbeInFlight,
        resultUpdatedAtMs,
        resultRecordVersion,
    )
    check(requiredResultColumns.all { it == null } || requiredResultColumns.all { it != null }) {
        "Persisted circuit-administration result is only partially populated."
    }
    val hasResult = requiredResultColumns.all { it != null }
    check(hasResult ||
        (resultFailureWindowStartedAtMs == null &&
            resultOpenUntilMs == null &&
            resultProbeLeaseUntilMs == null)
    ) {
        "Persisted circuit-administration result has orphaned optional fields."
    }
    val resultingRecord = if (!hasResult) {
        null
    } else {
        CircuitBreakerStateRecord(
            state = CircuitBreakerState(
                scope = scope,
                phase = CircuitBreakerPhase.valueOf(checkNotNull(resultPhase)),
                consecutiveFailures = checkNotNull(resultConsecutiveFailures),
                failureWindowStartedAt = resultFailureWindowStartedAtMs?.let(::DataLoomInstant),
                openUntil = resultOpenUntilMs?.let(::DataLoomInstant),
                probeGeneration = checkNotNull(resultProbeGeneration),
                probeInFlight = checkNotNull(resultProbeInFlight),
                updatedAt = DataLoomInstant(checkNotNull(resultUpdatedAtMs)),
                probeLeaseUntil = resultProbeLeaseUntilMs?.let(::DataLoomInstant),
            ),
            version = checkNotNull(resultRecordVersion),
        )
    }

    val executionColumns = listOf(
        executionErrorCode,
        executionErrorCategory,
        executionErrorSeverity,
        executionErrorRecoverability,
    )
    check(executionColumns.all { it == null } || executionColumns.all { it != null }) {
        "Persisted circuit-administration execution failure is only partially populated."
    }
    val executionFailure = if (executionColumns.all { it == null }) {
        null
    } else {
        CircuitAdministrationFailureSnapshot(
            code = ErrorCode(checkNotNull(executionErrorCode)),
            category = ErrorCategory.valueOf(checkNotNull(executionErrorCategory)),
            severity = ErrorSeverity.valueOf(checkNotNull(executionErrorSeverity)),
            recoverability = Recoverability.valueOf(checkNotNull(executionErrorRecoverability)),
        )
    }

    CircuitAdministrationStateRecord(
        state = CircuitAdministrationCommandState(
            request = CircuitAdministrationRequest(
                commandId = CircuitAdministrationCommandId(commandId),
                scope = scope,
                principalId = CircuitAdministrationPrincipalId(principalId),
                requestedAt = DataLoomInstant(requestedAtMs),
                action = CircuitAdministrationAction.valueOf(action),
                reason = CircuitAdministrationReason(reason),
                openUntil = requestedOpenUntilMs?.let(::DataLoomInstant),
            ),
            status = CircuitAdministrationCommandStatus.valueOf(status),
            authorizationId = authorizationId?.let(::CircuitAdministrationAuthorizationId),
            updatedAt = DataLoomInstant(updatedAtMs),
            rejectionReasonCode = rejectionReasonCode,
            resultingRecord = resultingRecord,
            executionFailure = executionFailure,
        ),
        version = recordVersion,
    )
} catch (invalid: IllegalArgumentException) {
    throw MalformedCircuitAdministrationStateException(invalid)
} catch (invalid: IllegalStateException) {
    throw MalformedCircuitAdministrationStateException(invalid)
}

internal fun circuitAdministrationScopeKey(scope: CircuitBreakerScope): String = buildString {
    append(scope.kind.name)
    appendCircuitAdministrationScopePart(scope.providerId?.value)
    appendCircuitAdministrationScopePart(scope.operation?.value)
    appendCircuitAdministrationScopePart(scope.tenantId?.value)
    appendCircuitAdministrationScopePart(scope.workflowId?.value)
}

private fun StringBuilder.appendCircuitAdministrationScopePart(value: String?) {
    append('|')
    if (value == null) {
        append('-')
    } else {
        append(value.length)
        append(':')
        append(value)
    }
}

internal class MalformedCircuitAdministrationStateException(cause: Throwable) : Exception(cause)

private object RoomCircuitAdministrationStoreError {
    fun databaseFailure(): DataLoomError = error(
        code = "CIRCUIT_ADMIN_ROOM_DATABASE_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "A circuit-administration database operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "CIRCUIT_ADMIN_ROOM_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted circuit-administration state failed integrity validation.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "CIRCUIT_ADMIN_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The circuit-administration record version is exhausted.",
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
