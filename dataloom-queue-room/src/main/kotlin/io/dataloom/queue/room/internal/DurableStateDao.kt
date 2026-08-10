package io.dataloom.queue.room.internal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Atomic Room DAO for generic, namespaced versioned durable state. */
@Dao
internal abstract class DurableStateDao {
    @Query("SELECT * FROM durable_states WHERE namespace = :namespace AND scope_key = :scopeKey LIMIT 1")
    abstract suspend fun load(namespace: String, scopeKey: String): DurableStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfMissing(entity: DurableStateEntity): Long

    @Query(
        """
        UPDATE durable_states
        SET state_payload = :statePayload,
            schema_version = :schemaVersion,
            record_version = :nextVersion
        WHERE namespace = :namespace AND scope_key = :scopeKey AND record_version = :expectedVersion
        """,
    )
    protected abstract suspend fun updateIfVersionMatches(
        namespace: String,
        scopeKey: String,
        expectedVersion: Long,
        nextVersion: Long,
        statePayload: String,
        schemaVersion: Int,
    ): Int

    @Transaction
    open suspend fun compareAndSet(
        expectedVersion: Long?,
        next: DurableStateEntity,
    ): DurableStateCompareAndSetEntityResult {
        if (expectedVersion == null) {
            return if (insertIfMissing(next) != -1L) {
                DurableStateCompareAndSetEntityResult.Updated(next)
            } else {
                DurableStateCompareAndSetEntityResult.Conflict(load(next.namespace, next.scopeKey))
            }
        }
        val nextVersion = expectedVersion + 1L
        val affected = updateIfVersionMatches(
            namespace = next.namespace,
            scopeKey = next.scopeKey,
            expectedVersion = expectedVersion,
            nextVersion = nextVersion,
            statePayload = next.statePayload,
            schemaVersion = next.schemaVersion,
        )
        return if (affected == 1) {
            DurableStateCompareAndSetEntityResult.Updated(next.copy(recordVersion = nextVersion))
        } else {
            DurableStateCompareAndSetEntityResult.Conflict(load(next.namespace, next.scopeKey))
        }
    }
}

internal sealed interface DurableStateCompareAndSetEntityResult {
    data class Updated(val entity: DurableStateEntity) : DurableStateCompareAndSetEntityResult
    data class Conflict(val current: DurableStateEntity?) : DurableStateCompareAndSetEntityResult
}
