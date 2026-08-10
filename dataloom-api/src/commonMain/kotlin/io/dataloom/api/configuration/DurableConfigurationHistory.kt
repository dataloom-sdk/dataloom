package io.dataloom.api.configuration

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateStore
import kotlin.jvm.JvmInline

/**
 * Identifies which durable configuration history a [DurableConfigurationHistory]
 * call addresses — for example, one per application, tenant, or configuration
 * domain sharing a single [DurableStateStore].
 *
 * Deliberately the same flat, single-field shape used throughout
 * `io.dataloom.api.identifier` and `io.dataloom.api.configuration.ConfigurationKey`.
 */
@JvmInline
public value class ConfigurationHistoryScope(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConfigurationHistoryScope must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Durable [TState] persisted per [ConfigurationHistoryScope] — every
 * currently retained [ConfigurationSnapshot], oldest first, mirroring
 * [DataLoomConfigurationHistory]'s in-memory retention window.
 */
public data class ConfigurationHistoryState(
    public val retainedSnapshots: List<ConfigurationSnapshot>,
)

/** Outcome of one [DurableConfigurationHistory.apply] call. */
public sealed interface DurableConfigurationApplyOutcome {

    /** [snapshot] became the new current snapshot for its scope. */
    public data class Applied(public val snapshot: ConfigurationSnapshot) : DurableConfigurationApplyOutcome

    /**
     * [snapshot] was rejected because its [ConfigurationSnapshot.version] did
     * not strictly exceed [currentVersion]. Nothing was persisted.
     */
    public data class VersionNotMonotonic(
        public val snapshot: ConfigurationSnapshot,
        public val currentVersion: Long,
    ) : DurableConfigurationApplyOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurableConfigurationApplyOutcome

    /**
     * [DurableConfigurationHistory.maximumStateUpdateAttempts] consecutive
     * compare-and-set attempts all lost to concurrent writers for the same
     * scope. Nothing was persisted; the caller may retry.
     */
    public data object ContentionLimitReached : DurableConfigurationApplyOutcome
}

/** Outcome of one [DurableConfigurationHistory.rollbackToLastKnownGood] call. */
public sealed interface DurableConfigurationRollbackOutcome {

    /** The snapshot applied immediately before the prior current snapshot was restored. */
    public data class RolledBack(public val snapshot: ConfigurationSnapshot) : DurableConfigurationRollbackOutcome

    /** There is no earlier retained snapshot for this scope to roll back to. */
    public data object NoEarlierSnapshot : DurableConfigurationRollbackOutcome

    /** The underlying [DurableStateStore] failed. Nothing changed. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurableConfigurationRollbackOutcome

    /** All compare-and-set attempts lost to concurrent writers. Nothing changed; the caller may retry. */
    public data object ContentionLimitReached : DurableConfigurationRollbackOutcome
}

/**
 * Durable, transactional, versioned history of applied [ConfigurationSnapshot]
 * instances, backed by a [DurableStateStore].
 *
 * ## Relationship to [DataLoomConfigurationHistory]
 *
 * Same monotonic-version and bounded-retention semantics as the in-memory
 * [DataLoomConfigurationHistory] — a candidate snapshot is only accepted when
 * its version strictly exceeds the current one, and only the most recent
 * [maxRetainedVersions] snapshots are kept — but every [apply] and
 * [rollbackToLastKnownGood] call is persisted through [store] rather than
 * held in a process-local field, so history survives a process restart and
 * stays consistent under concurrent writers via the store's compare-and-set.
 *
 * [DataLoomConfigurationHistory] is not superseded by this type: an
 * in-process-only caller with no durability requirement can keep using it.
 *
 * ## Concurrency
 *
 * Every mutation follows the same bounded load-evaluate-compare-and-set
 * retry loop [io.dataloom.runtime.retry.CircuitBreakerCoordinator] already
 * established for [io.dataloom.api.circuit.CircuitBreakerStateStore]: on a
 * compare-and-set [DurableStateCompareAndSetResult.Conflict], the whole
 * operation re-reads current state and retries, up to
 * [maximumStateUpdateAttempts] times, rather than failing on the first race.
 *
 * @param store durable persistence for this history's [ConfigurationHistoryState].
 * @param maxRetainedVersions the maximum number of applied snapshots kept per
 *   scope, including the current one. Must be at least `1`.
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and expects to read. A domain that changes how
 *   [ConfigurationHistoryState] is encoded should increase this and supply a
 *   [io.dataloom.api.state.DurableStateMigration] at the store boundary.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per [apply] or [rollbackToLastKnownGood] call before giving up with
 *   [DurableConfigurationApplyOutcome.ContentionLimitReached] /
 *   [DurableConfigurationRollbackOutcome.ContentionLimitReached]. Must be at
 *   least `1`.
 */
public class DurableConfigurationHistory(
    private val store: DurableStateStore<ConfigurationHistoryScope, ConfigurationHistoryState>,
    private val maxRetainedVersions: Int = DEFAULT_MAX_RETAINED_VERSIONS,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maxRetainedVersions >= 1) {
            "maxRetainedVersions must be at least 1, but was $maxRetainedVersions."
        }
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /** The currently applied snapshot for [scope], or `null` if [apply] has never succeeded for it. */
    public suspend fun current(scope: ConfigurationHistoryScope): ProviderOperationResult<ConfigurationSnapshot?> =
        when (val loaded = store.load(scope)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                loaded.value.stateOrEmpty().retainedSnapshots.lastOrNull(),
            )
        }

    /** Every retained version for [scope], oldest first, bounded by [maxRetainedVersions]. */
    public suspend fun retainedVersions(scope: ConfigurationHistoryScope): ProviderOperationResult<List<Long>> =
        when (val loaded = store.load(scope)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                loaded.value.stateOrEmpty().retainedSnapshots.map { it.version },
            )
        }

    /**
     * Applies [snapshot] to [scope] if its version strictly exceeds the
     * scope's current version, persisting it and discarding the oldest
     * retained snapshot once [maxRetainedVersions] is exceeded.
     */
    public suspend fun apply(
        scope: ConfigurationHistoryScope,
        snapshot: ConfigurationSnapshot,
    ): DurableConfigurationApplyOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return DurableConfigurationApplyOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val expectedVersion = loaded.versionOrNull()
            val currentState = loaded.stateOrEmpty()
            val currentVersion = currentState.retainedSnapshots.lastOrNull()?.version
            if (currentVersion != null && snapshot.version <= currentVersion) {
                return DurableConfigurationApplyOutcome.VersionNotMonotonic(snapshot, currentVersion)
            }
            val nextRetained = (currentState.retainedSnapshots + snapshot).let { retained ->
                if (retained.size > maxRetainedVersions) {
                    retained.subList(retained.size - maxRetainedVersions, retained.size)
                } else {
                    retained
                }
            }
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = scope,
                        expectedVersion = expectedVersion,
                        nextState = ConfigurationHistoryState(nextRetained),
                        nextSchemaVersion = schemaVersion,
                    ),
                )
            ) {
                is ProviderOperationResult.Failure ->
                    return DurableConfigurationApplyOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> when (result.value) {
                    is DurableStateCompareAndSetResult.Conflict -> Unit // lost the race; reload and retry
                    is DurableStateCompareAndSetResult.Updated ->
                        return DurableConfigurationApplyOutcome.Applied(snapshot)
                }
            }
        }
        return DurableConfigurationApplyOutcome.ContentionLimitReached
    }

    /**
     * Restores the snapshot applied immediately before the current one for
     * [scope] and drops the current snapshot from history.
     */
    public suspend fun rollbackToLastKnownGood(
        scope: ConfigurationHistoryScope,
    ): DurableConfigurationRollbackOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return DurableConfigurationRollbackOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val expectedVersion = loaded.versionOrNull()
            val currentState = loaded.stateOrEmpty()
            if (currentState.retainedSnapshots.size < 2) {
                return DurableConfigurationRollbackOutcome.NoEarlierSnapshot
            }
            val nextRetained = currentState.retainedSnapshots.subList(0, currentState.retainedSnapshots.size - 1)
            val restored = nextRetained.last()
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = scope,
                        expectedVersion = expectedVersion,
                        nextState = ConfigurationHistoryState(nextRetained),
                        nextSchemaVersion = schemaVersion,
                    ),
                )
            ) {
                is ProviderOperationResult.Failure ->
                    return DurableConfigurationRollbackOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> when (result.value) {
                    is DurableStateCompareAndSetResult.Conflict -> Unit // lost the race; reload and retry
                    is DurableStateCompareAndSetResult.Updated ->
                        return DurableConfigurationRollbackOutcome.RolledBack(restored)
                }
            }
        }
        return DurableConfigurationRollbackOutcome.ContentionLimitReached
    }

    private fun DurableStateLoadResult<ConfigurationHistoryState>.stateOrEmpty(): ConfigurationHistoryState =
        when (this) {
            is DurableStateLoadResult.Missing -> ConfigurationHistoryState(emptyList())
            is DurableStateLoadResult.Found -> record.state
        }

    private fun DurableStateLoadResult<ConfigurationHistoryState>.versionOrNull(): Long? =
        when (this) {
            is DurableStateLoadResult.Missing -> null
            is DurableStateLoadResult.Found -> record.version
        }

    private companion object {
        const val DEFAULT_MAX_RETAINED_VERSIONS: Int = 10
        const val DEFAULT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
