package io.dataloom.api.state

import io.dataloom.api.provider.ProviderOperationResult

/**
 * Domain-agnostic durable-state persistence contract.
 *
 * ## Purpose
 *
 * [CircuitBreakerStateStore][io.dataloom.api.circuit.CircuitBreakerStateStore]
 * proved this exact shape — atomic load/compare-and-set, optimistic
 * concurrency via a version counter — for one domain (retry/circuit state).
 * [DurableStateStore] generalizes it so other domains that need durable,
 * transactional, versioned state (unresolved conflicts, event outbox/audit,
 * asset sessions, administrative commands, configuration snapshot history,
 * policy decision history, and so on) can adopt the same proven pattern
 * instead of inventing a parallel one per domain.
 *
 * [CircuitBreakerStateStore][io.dataloom.api.circuit.CircuitBreakerStateStore]
 * itself is intentionally left as-is rather than retrofitted onto this
 * contract — it already has multiple real platform implementations in
 * production, and retrofitting it here would risk destabilizing working,
 * tested persistence for no behavioral gain. This contract is for domains
 * adopting durable state *now*, going forward.
 *
 * ## Scope
 *
 * [TScope] identifies *which* piece of state a domain is addressing (for
 * example, a configuration key, a policy set ID, or a conflict ID).
 * [TState] is the domain's own state shape. Neither type parameter is
 * constrained beyond ordinary value semantics (`equals`/`hashCode`), so a
 * domain's existing types can be used directly without wrapping.
 *
 * ## Atomicity
 *
 * Implementations must make [compareAndSet] atomic per [TScope]. A null
 * [DurableStateCompareAndSetRequest.expectedVersion] means no record may
 * already exist for that scope — the same "insert if absent" semantics
 * [CircuitBreakerStateStore][io.dataloom.api.circuit.CircuitBreakerStateStore]
 * already established. Cancellation must propagate; implementations must not
 * swallow [kotlinx.coroutines.CancellationException].
 *
 * ## Schema versions and migration
 *
 * [DurableStateRecord.schemaVersion] is independent from
 * [DurableStateRecord.version]: [DurableStateRecord.version] is the
 * optimistic-concurrency generation counter for compare-and-set;
 * [DurableStateRecord.schemaVersion] identifies which shape of [TState] a
 * persisted record was written as, so a domain can evolve [TState] over time
 * without breaking already-persisted records. [DurableStateMigration]
 * carries an optional upcast function a domain supplies to
 * [applyMigration] when loading a record written under an older schema
 * version. A store implementation is not required to call it automatically —
 * callers apply it explicitly so migration timing and failure handling stay
 * visible at the call site rather than hidden inside a persistence
 * implementation.
 */
public interface DurableStateStore<TScope : Any, TState : Any> {
    public suspend fun load(
        scope: TScope,
    ): ProviderOperationResult<DurableStateLoadResult<TState>>

    public suspend fun compareAndSet(
        request: DurableStateCompareAndSetRequest<TScope, TState>,
    ): ProviderOperationResult<DurableStateCompareAndSetResult<TState>>
}

/**
 * Versioned durable-state record returned by a [DurableStateStore].
 *
 * @param state the domain's own persisted state value.
 * @param version optimistic-concurrency generation counter for
 *   [DurableStateStore.compareAndSet]. Non-negative; increases by exactly
 *   one on every successful [DurableStateCompareAndSetResult.Updated].
 * @param schemaVersion identifies which shape of [TState] this record was
 *   persisted as. Non-negative. See "Schema versions and migration" on
 *   [DurableStateStore].
 */
public data class DurableStateRecord<TState : Any>(
    public val state: TState,
    public val version: Long,
    public val schemaVersion: Int,
) {
    init {
        require(version >= 0L) { "DurableStateRecord version must be zero or greater." }
        require(schemaVersion >= 0) { "DurableStateRecord schemaVersion must be zero or greater." }
    }
}

/** Result of loading one durable-state scope. */
public sealed interface DurableStateLoadResult<out TState : Any> {
    public data object Missing : DurableStateLoadResult<Nothing>

    public data class Found<TState : Any>(
        public val record: DurableStateRecord<TState>,
    ) : DurableStateLoadResult<TState>
}

/** Atomic compare-and-set request for one durable-state scope. */
public data class DurableStateCompareAndSetRequest<TScope : Any, TState : Any>(
    public val scope: TScope,
    public val expectedVersion: Long?,
    public val nextState: TState,
    public val nextSchemaVersion: Int,
) {
    init {
        require(expectedVersion == null || expectedVersion >= 0L) {
            "DurableStateCompareAndSetRequest expectedVersion must be null or non-negative."
        }
        require(nextSchemaVersion >= 0) {
            "DurableStateCompareAndSetRequest nextSchemaVersion must be zero or greater."
        }
    }
}

/** Result of an atomic durable-state compare-and-set operation. */
public sealed interface DurableStateCompareAndSetResult<out TState : Any> {
    public data class Updated<TState : Any>(
        public val record: DurableStateRecord<TState>,
    ) : DurableStateCompareAndSetResult<TState>

    public data class Conflict<TState : Any>(
        public val current: DurableStateRecord<TState>?,
    ) : DurableStateCompareAndSetResult<TState>
}

/**
 * Optional schema-upcast hook a domain supplies to [applyMigration].
 *
 * [migrate] receives the schema version a record was actually persisted
 * under and the record's raw (possibly outdated) state, and returns the
 * state upcast to the domain's current shape. Implementations must be pure
 * and total for every schema version the domain has ever shipped — a
 * migration that cannot proceed is a domain bug, not a runtime failure
 * mode this contract models.
 */
public fun interface DurableStateMigration<TState : Any> {
    public fun migrate(fromSchemaVersion: Int, state: TState): TState
}

/**
 * Applies [migration] to [record] when it was persisted under an older
 * schema version than [currentSchemaVersion], returning [record] unchanged
 * when it already matches.
 *
 * This is a plain helper, not part of [DurableStateStore] itself — callers
 * apply it explicitly after [DurableStateStore.load] so migration timing
 * and failure handling stay visible at the call site.
 */
public fun <TState : Any> applyMigration(
    record: DurableStateRecord<TState>,
    currentSchemaVersion: Int,
    migration: DurableStateMigration<TState>,
): DurableStateRecord<TState> {
    require(currentSchemaVersion >= 0) {
        "applyMigration currentSchemaVersion must be zero or greater."
    }
    if (record.schemaVersion == currentSchemaVersion) return record
    require(record.schemaVersion < currentSchemaVersion) {
        "applyMigration cannot downgrade a record from schemaVersion " +
            "${record.schemaVersion} to an older schemaVersion $currentSchemaVersion."
    }
    return record.copy(
        state = migration.migrate(record.schemaVersion, record.state),
        schemaVersion = currentSchemaVersion,
    )
}
