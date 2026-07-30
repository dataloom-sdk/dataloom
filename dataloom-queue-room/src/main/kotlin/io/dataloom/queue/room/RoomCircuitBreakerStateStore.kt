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
        val entity = dao.load(scopeKey(scope))
        if (entity == null) {
            CircuitBreakerLoadResult.Missing
        } else {
            CircuitBreakerLoadResult.Found(entity.toRecord())
        }
    }

    override suspend fun compareAndSet(
        request: CircuitBreakerCompareAndSetRequest,
    ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> = protect {
        val nextVersion = request.expectedVersion?.let {
            require(it < Long.MAX_VALUE) { "Circuit state version is exhausted." }
            it + 1L
        } ?: 0L
        val next = request.nextState.toEntity(nextVersion)
        when (val result = dao.compareAndSet(request.expectedVersion, next)) {
            is CircuitBreakerCompareAndSetEntityResult.Updated ->
                CircuitBreakerCompareAndSetResult.Updated(result.entity.toRecord())
            is CircuitBreakerCompareAndSetEntityResult.Conflict ->
                CircuitBreakerCompareAndSetResult.Conflict(result.current?.toRecord())
        }
    }

    private suspend fun <T> protect(block: suspend () -> T): ProviderOperationResult<T> = try {
        ProviderOperationResult.Success(block())
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ProviderOperationResult.Failure(RoomCircuitStoreError.databaseFailure())
    }
}

private fun CircuitBreakerState.toEntity(version: Long): CircuitBreakerStateEntity =
    CircuitBreakerStateEntity(
        scopeKey = scopeKey(scope),
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

private fun CircuitBreakerStateEntity.toRecord(): CircuitBreakerStateRecord {
    val kind = CircuitBreakerScopeKind.valueOf(scopeKind)
    val scope = CircuitBreakerScope(
        kind = kind,
        providerId = providerId?.let(::ProviderId),
        operation = operation?.let(::RetryOperation),
        tenantId = tenantId?.let(::TenantId),
        workflowId = workflowId?.let(::WorkflowId),
    )
    check(scopeKey(scope) == scopeKey) { "Persisted circuit scope key does not match its fields." }
    return CircuitBreakerStateRecord(
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
}

private fun scopeKey(scope: CircuitBreakerScope): String = buildString {
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

private object RoomCircuitStoreError {
    fun databaseFailure(): DataLoomError = Error(
        code = ErrorCode("CIRCUIT_ROOM_DATABASE_FAILURE"),
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "A circuit-state database operation failed.",
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