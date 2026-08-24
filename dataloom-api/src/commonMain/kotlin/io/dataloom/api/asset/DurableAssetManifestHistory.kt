package io.dataloom.api.asset

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore

/**
 * Durable `TState` persisted per [AssetId]: every currently retained
 * [AssetManifest] revision for that asset, oldest first, mirroring
 * [io.dataloom.api.configuration.ConfigurationHistoryState]'s own
 * "every currently retained value, oldest first" shape for a different
 * domain.
 */
public data class AssetManifestHistoryState(
    public val retainedManifests: List<AssetManifest>,
)

/** Outcome of one [DurableAssetManifestHistory.apply] call. */
public sealed interface DurableAssetManifestApplyOutcome {

    /** [manifest] became the new current revision for its [AssetId]. */
    public data class Applied(public val manifest: AssetManifest) : DurableAssetManifestApplyOutcome

    /**
     * [manifest] was rejected because its [AssetManifest.version] did not
     * strictly exceed [currentVersion]. Nothing was persisted.
     */
    public data class VersionNotMonotonic(
        public val manifest: AssetManifest,
        public val currentVersion: Long,
    ) : DurableAssetManifestApplyOutcome

    /**
     * [manifest] was rejected because its [AssetManifest.assetId] does not
     * equal the [AssetId] scope it was applied to. Nothing was persisted.
     * This can only happen if a caller passes a manifest under the wrong
     * scope — [AssetManifest] itself does not enforce this, since it has no
     * knowledge of which scope a [DurableStateStore] call will use.
     */
    public data class AssetIdMismatch(
        public val scope: AssetId,
        public val manifest: AssetManifest,
    ) : DurableAssetManifestApplyOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurableAssetManifestApplyOutcome

    /**
     * [DurableAssetManifestHistory.maximumStateUpdateAttempts] consecutive
     * compare-and-set attempts all lost the race to concurrent writers for
     * the same [AssetId]. Nothing was persisted; the caller may retry.
     */
    public data object ContentionLimitReached : DurableAssetManifestApplyOutcome
}

/**
 * Durable, transactional, versioned history of an asset's applied
 * [AssetManifest] revisions, backed by a [DurableStateStore].
 *
 * ## Why this exists, and what it adds over [AssetManifest] alone
 *
 * [AssetManifest] is deliberately permissive: it documents that "nothing
 * here enforces monotonicity across revisions" and leaves durable history as
 * "a future durable-history concern, not this type's" (see its own KDoc).
 * [DurableAssetManifestHistory] is that future concern, arriving now: it is
 * the ordering-and-durability discipline layered *above* the permissive
 * value type, exactly the same split
 * [io.dataloom.api.configuration.ConfigurationSnapshot] (permissive) versus
 * [io.dataloom.api.configuration.DurableConfigurationHistory] (durable,
 * monotonic, retained) already establishes for a different domain. This type
 * does not change [AssetManifest] itself — direct construction of an
 * [AssetManifest] with a non-monotonic [AssetManifest.version] remains legal
 * and unaffected; the discipline only applies to callers that choose to
 * route revisions through this history.
 *
 * ## Scope: [AssetId] reused directly
 *
 * Unlike [io.dataloom.api.configuration.ConfigurationHistoryScope] and
 * [io.dataloom.api.policy.PolicyDecisionScope] (which had to compose a new
 * scope type because neither domain's own value type carried a natural
 * single-field identity), [AssetManifest] already carries exactly the right
 * shape in [AssetManifest.assetId] — an [AssetId] identifies one logical
 * asset across its whole version history by design (see [AssetId]'s own
 * KDoc). So `TScope` here is plain [AssetId], reused directly, the same way
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] reuses `ConflictId`
 * and [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] reuses
 * `StrategyDecisionId` rather than inventing a wrapper.
 *
 * ## List-valued state, not one scope key per revision
 *
 * A [DurableStateStore] persists one [TState] value per scope key via
 * atomic load/compare-and-set — there is no native multi-row-per-scope
 * concept. Two shapes could give this domain multi-revision history: one
 * scope key per `(assetId, version)` pair (loses "what is the latest
 * revision" queryability without a separate index that itself would need to
 * be kept consistent under concurrent writers), or a single scope key per
 * [AssetId] whose one [TState] value carries every currently retained
 * revision as a bounded list. This type takes the second shape —
 * [AssetManifestHistoryState.retainedManifests] — the same choice
 * [io.dataloom.api.configuration.ConfigurationHistoryState] and
 * [io.dataloom.api.operational.OperationalEventOutboxState] already made for
 * "a bounded list of historical records in one CAS-written value," so one
 * atomic compare-and-set both appends the new revision and evicts whatever
 * [maxRetainedVersions] no longer allows, with no separate index to keep
 * consistent.
 *
 * ## Monotonicity: enforced here, not by [AssetManifest]
 *
 * A candidate [AssetManifest] is only accepted by [apply] when its
 * [AssetManifest.version] strictly exceeds the scope's current version —
 * the same rule [io.dataloom.api.configuration.DurableConfigurationHistory.apply]
 * already enforces for [io.dataloom.api.configuration.ConfigurationSnapshot].
 * [AssetManifest] itself never enforces this (see its own KDoc's
 * "Versioning" section); this is exactly the "the value type is permissive,
 * the durable log adds ordering discipline" split that section anticipates.
 *
 * ## Bounded retention
 *
 * Only the most recent [maxRetainedVersions] revisions are kept per
 * [AssetId], oldest evicted first once the bound is exceeded — mirroring
 * [io.dataloom.api.configuration.DurableConfigurationHistory]'s own
 * `maxRetainedVersions` rather than
 * [io.dataloom.api.operational.DurableOperationalEventOutbox]'s count/age
 * retention pair: an asset manifest revision is a versioned snapshot of a
 * single logical thing over time (like a configuration snapshot), not an
 * open-ended event stream, so a simple count bound is the better fit.
 *
 * ## Concurrency
 *
 * Same bounded load-evaluate-compare-and-set retry loop every other
 * [DurableStateStore] adopter in this codebase uses: on a compare-and-set
 * [DurableStateCompareAndSetResult.Conflict], the whole operation re-reads
 * current state and retries, up to [maximumStateUpdateAttempts] times.
 *
 * @param store durable persistence for this history's [AssetManifestHistoryState].
 * @param maxRetainedVersions the maximum number of applied revisions kept per
 *   [AssetId], including the current one. Must be at least `1`.
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and expects to read.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per [apply] call before giving up with
 *   [DurableAssetManifestApplyOutcome.ContentionLimitReached]. Must be at
 *   least `1`.
 */
public class DurableAssetManifestHistory(
    private val store: DurableStateStore<AssetId, AssetManifestHistoryState>,
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

    /** The currently applied manifest for [assetId], or `null` if [apply] has never succeeded for it. */
    public suspend fun current(assetId: AssetId): ProviderOperationResult<AssetManifest?> =
        when (val loaded = store.load(assetId)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                loaded.value.stateOrEmpty().retainedManifests.lastOrNull(),
            )
        }

    /** Every retained revision's version for [assetId], oldest first, bounded by [maxRetainedVersions]. */
    public suspend fun retainedVersions(assetId: AssetId): ProviderOperationResult<List<Long>> =
        when (val loaded = store.load(assetId)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                loaded.value.stateOrEmpty().retainedManifests.map { it.version },
            )
        }

    /**
     * Every retained [AssetManifest] for [assetId], oldest first, bounded by
     * [maxRetainedVersions]. Unlike [retainedVersions], this returns the full
     * manifests, not only their version numbers — the actual "retrieve
     * history" capability this type exists to provide.
     */
    public suspend fun history(assetId: AssetId): ProviderOperationResult<List<AssetManifest>> =
        when (val loaded = store.load(assetId)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                loaded.value.stateOrEmpty().retainedManifests,
            )
        }

    /**
     * Applies [manifest] to [assetId] if [AssetManifest.assetId] matches
     * [assetId] and [AssetManifest.version] strictly exceeds the scope's
     * current version, persisting it and discarding the oldest retained
     * revision once [maxRetainedVersions] is exceeded.
     */
    public suspend fun apply(
        assetId: AssetId,
        manifest: AssetManifest,
    ): DurableAssetManifestApplyOutcome {
        if (manifest.assetId != assetId) {
            return DurableAssetManifestApplyOutcome.AssetIdMismatch(assetId, manifest)
        }
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(assetId)) {
                is ProviderOperationResult.Failure -> return DurableAssetManifestApplyOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val expectedVersion = loaded.versionOrNull()
            val currentState = loaded.stateOrEmpty()
            val currentVersion = currentState.retainedManifests.lastOrNull()?.version
            if (currentVersion != null && manifest.version <= currentVersion) {
                return DurableAssetManifestApplyOutcome.VersionNotMonotonic(manifest, currentVersion)
            }
            val nextRetained = (currentState.retainedManifests + manifest).let { retained ->
                if (retained.size > maxRetainedVersions) {
                    retained.subList(retained.size - maxRetainedVersions, retained.size)
                } else {
                    retained
                }
            }
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = assetId,
                        expectedVersion = expectedVersion,
                        nextState = AssetManifestHistoryState(nextRetained),
                        nextSchemaVersion = schemaVersion,
                    ),
                )
            ) {
                is ProviderOperationResult.Failure ->
                    return DurableAssetManifestApplyOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> when (result.value) {
                    is DurableStateCompareAndSetResult.Conflict -> Unit // lost the race; reload and retry
                    is DurableStateCompareAndSetResult.Updated ->
                        return DurableAssetManifestApplyOutcome.Applied(manifest)
                }
            }
        }
        return DurableAssetManifestApplyOutcome.ContentionLimitReached
    }

    private fun DurableStateLoadResult<AssetManifestHistoryState>.stateOrEmpty(): AssetManifestHistoryState =
        when (this) {
            is DurableStateLoadResult.Missing -> AssetManifestHistoryState(emptyList())
            is DurableStateLoadResult.Found -> record.state
        }

    private fun DurableStateLoadResult<AssetManifestHistoryState>.versionOrNull(): Long? =
        when (this) {
            is DurableStateLoadResult.Missing -> null
            is DurableStateLoadResult.Found -> record.version
        }

    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder] for [AssetId]. [AssetId.value]
         * is already validated non-blank and is the entire scope identity, so no
         * escaping/composition is needed — the same reasoning
         * [io.dataloom.api.conflict.DurableUnresolvedConflictLog.KeyEncoder]
         * documents for its own reused-identifier scope. Attached here rather
         * than to [AssetId] itself: [AssetId] is a pre-existing shared
         * identifier this type did not introduce, and Kotlin cannot add a
         * companion member to an existing class from a different file.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<AssetId> = DurableStateScopeKeyEncoder { it.value }

        private const val DEFAULT_MAX_RETAINED_VERSIONS: Int = 10
        private const val DEFAULT_SCHEMA_VERSION: Int = 1
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
