package io.dataloom.queue.room

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerScopeKind
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
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
import io.dataloom.queue.room.internal.CircuitBreakerCompareAndSetEntityResult
import io.dataloom.queue.room.internal.CircuitBreakerStateEntity
import io.dataloom.queue.room.internal.DataLoomRoomDatabase

/** Production Android Room implementation of [CircuitBreakerStateStore]. */
public class RoomCircuitBreakerStateStore(
    database: DataLoomRoomDatabase,
) : CircuitBreakerStateStore {
    private val dao = database.circuitBreakerStateDao()

    override suspend fun load(
        scope: CircuitBreakerScope,
    ): ProviderOperationResult<CircuitBreakerLoadResult> = protect {
        val entity = dao.load(circuitBreakerScopeKey(scope))
        if (entity == null) {
            CircuitBreakerLoadResult.Missing
        } else {
            CircuitBreakerLoadResult.Found(entity.toCircuitBreakerRecord())
        }
    }

    override suspend fun compareAndSet(
        request: CircuitBreakerCompareAndSetRequest,
    ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
        if (request.expectedVersion == Long.MAX_VALUE) {
            return ProviderOperationResult.Failure(RoomCircuitStoreError.versionExhausted())
        }
        return protect {
            val nextVersion = request.expectedVersion?.plus(1L) ?: 0L
            val next = request.nextState.toCircuitBreakerEntity(nextVersion)
            when (val result = dao.compareAndSet(request.expectedVersion, next)) {
                is CircuitBreakerCompareAndSetEntityResult.Updated ->
                    CircuitBreakerCompareAndSetResult.Updated(result.entity.toCircuitBreakerRecord())
                is CircuitBreakerCompareAndSetEntityResult.Conflict ->
                    CircuitBreakerCompareAndSetResult.Conflict(
                        result.current?.toCircuitBreakerRecord(),
                    )
            }
        }
    }

    private suspend fun <T> protect(block: suspend () -> T): ProviderOperationResult<T> = try {
        ProviderOperationResult.Success(block())
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
    } catch (_: MalformedCircuitStateException) {
        ProviderOperationResult.Failure(RoomCircuitStoreError.integrityFailure())
    } catch (_: Exception) {
        ProviderOperationResult.Failure(RoomCircuitStoreError.databaseFailure())
    }
}

internal fun CircuitBreakerState.toCircuitBreakerEntity(version: Long): CircuitBreakerStateEntity =
    CircuitBreakerStateEntity(
        scopeKey = circuitBreakerScopeKey(scope),
        scopeKind = scope.kind.name,
        providerId = scope.providerId?.value,
        operation = scope.operation?.value,
        tenantId = scope.tenantId?.value,
        workflowId = scope.workflowId?.value,
        phase = phase.name,
        consecutiveFailures = consecutiveFailures,
        failureWindowStartedAtMs = failureWindowStartedAt?.epochMilliseconds,
        openUntilMs = openUntil?.epochMilliseconds,
        probeGeneration = probeGeneration,
        probeInFlight = probeInFlight,
        probeLeaseUntilMs = probeLeaseUntil?.epochMilliseconds,
        updatedAtMs = updatedAt.epochMilliseconds,
        recordVersion = version,
    )

internal fun CircuitBreakerStateEntity.toCircuitBreakerRecord(): CircuitBreakerStateRecord = try {
    val kind = CircuitBreakerScopeKind.valueOf(scopeKind)
    val scope = CircuitBreakerScope(
        kind = kind,
        providerId = providerId?.let(::ProviderId),
        operation = operation?.let(::RetryOperation),
        tenantId = tenantId?.let(::TenantId),
        workflowId = workflowId?.let(::WorkflowId),
    )
    check(circuitBreakerScopeKey(scope) == scopeKey) {
        "Persisted circuit scope key does not match its fields."
    }
    CircuitBreakerStateRecord(
        state = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.valueOf(phase),
            consecutiveFailures = consecutiveFailures,
            failureWindowStartedAt = failureWindowStartedAtMs?.let(::DataLoomInstant),
            openUntil = openUntilMs?.let(::DataLoomInstant),
            probeGeneration = probeGeneration,
            probeInFlight = probeInFlight,
            updatedAt = DataLoomInstant(updatedAtMs),
            probeLeaseUntil = probeLeaseUntilMs?.let(::DataLoomInstant),
        ),
        version = recordVersion,
    )
} catch (invalid: IllegalArgumentException) {
    throw MalformedCircuitStateException(invalid)
} catch (invalid: IllegalStateException) {
    throw MalformedCircuitStateException(invalid)
}

internal fun circuitBreakerScopeKey(scope: CircuitBreakerScope): String = buildString {
    append(scope.kind.name)
    appendPart(scope.providerId?.value)
    appendPart(scope.operation?.value)
    appendPart(scope.tenantId?.value)
    appendPart(scope.workflowId?.value)
}

private fun StringBuilder.appendPart(value: String?) {
    append('|')
    if (value == null) {
        append('-')
    } else {
        append(value.length)
        append(':')
        append(value)
    }
}

private class MalformedCircuitStateException(cause: Throwable) : Exception(cause)

private object RoomCircuitStoreError {
    fun databaseFailure(): DataLoomError = error(
        code = "CIRCUIT_ROOM_DATABASE_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "A circuit-state database operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "CIRCUIT_ROOM_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted circuit state failed integrity validation.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "CIRCUIT_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The circuit-state record version is exhausted.",
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
