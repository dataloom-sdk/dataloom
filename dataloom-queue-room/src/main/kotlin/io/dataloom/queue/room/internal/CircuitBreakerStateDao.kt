package io.dataloom.queue.room.internal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Atomic Room DAO for versioned circuit state. */
@Dao
internal abstract class CircuitBreakerStateDao {
    @Query("SELECT * FROM circuit_breaker_states WHERE scope_key = :scopeKey LIMIT 1")
    abstract suspend fun load(scopeKey: String): CircuitBreakerStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfMissing(entity: CircuitBreakerStateEntity): Long

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
    protected abstract suspend fun updateIfVersionMatches(
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

    @Transaction
    open suspend fun compareAndSet(
        expectedVersion: Long?,
        next: CircuitBreakerStateEntity,
    ): CircuitBreakerCompareAndSetEntityResult {
        if (expectedVersion == null) {
            return if (insertIfMissing(next) != -1L) {
                CircuitBreakerCompareAndSetEntityResult.Updated(next)
            } else {
                CircuitBreakerCompareAndSetEntityResult.Conflict(load(next.scopeKey))
            }
        }
        val nextVersion = expectedVersion + 1L
        val affected = updateIfVersionMatches(
            scopeKey = next.scopeKey,
            expectedVersion = expectedVersion,
            nextVersion = nextVersion,
            scopeKind = next.scopeKind,
            providerId = next.providerId,
            operation = next.operation,
            tenantId = next.tenantId,
            workflowId = next.workflowId,
            phase = next.phase,
            consecutiveFailures = next.consecutiveFailures,
            failureWindowStartedAtMs = next.failureWindowStartedAtMs,
            openUntilMs = next.openUntilMs,
            probeGeneration = next.probeGeneration,
            probeInFlight = next.probeInFlight,
            probeLeaseUntilMs = next.probeLeaseUntilMs,
            updatedAtMs = next.updatedAtMs,
        )
        return if (affected == 1) {
            CircuitBreakerCompareAndSetEntityResult.Updated(next.copy(recordVersion = nextVersion))
        } else {
            CircuitBreakerCompareAndSetEntityResult.Conflict(load(next.scopeKey))
        }
    }
}

internal sealed interface CircuitBreakerCompareAndSetEntityResult {
    data class Updated(val entity: CircuitBreakerStateEntity) : CircuitBreakerCompareAndSetEntityResult
    data class Conflict(val current: CircuitBreakerStateEntity?) : CircuitBreakerCompareAndSetEntityResult
}