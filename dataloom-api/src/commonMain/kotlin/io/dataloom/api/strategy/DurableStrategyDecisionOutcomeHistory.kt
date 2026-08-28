package io.dataloom.api.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore

/**
 * Durable `TState` persisted per [StrategyDecisionId]: every currently
 * retained per-attempt [StrategyDecisionEvent] for that decision, oldest
 * first, mirroring [io.dataloom.api.asset.AssetManifestHistoryState]'s own
 * "every currently retained value, oldest first" shape for a different
 * domain.
 */
public data class StrategyDecisionOutcomeHistoryState(
    public val retainedAttempts: List<StrategyDecisionEvent>,
)

/** Outcome of one [DurableStrategyDecisionOutcomeHistory.append] call. */
public sealed interface DurableStrategyDecisionOutcomeAppendOutcome {

    /**
     * [event] was durably appended as a new attempt for its
     * [StrategyDecisionId]. [retainedCount] is how many attempts are now
     * retained for that decision, after eviction (never more than the
     * configured `maxRetainedAttempts`).
     */
    public data class Appended(
        public val event: StrategyDecisionEvent,
        public val retainedCount: Int,
    ) : DurableStrategyDecisionOutcomeAppendOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurableStrategyDecisionOutcomeAppendOutcome

    /**
     * [DurableStrategyDecisionOutcomeHistory.maximumStateUpdateAttempts]
     * consecutive compare-and-set attempts all lost the race to concurrent
     * appenders for the same [StrategyDecisionId]. Nothing was persisted;
     * the caller may retry.
     */
    public data object ContentionLimitReached : DurableStrategyDecisionOutcomeAppendOutcome
}

/**
 * Durable, append-only, bounded-retention history of every terminal-outcome
 * attempt recorded for one [StrategyDecisionId], backed by a
 * [DurableStateStore] -- the "full per-attempt outcome history" this
 * codebase's own [DurableStrategyDecisionEventLog] documentation names as "a
 * deliberately separate, larger design question this slice does not answer."
 *
 * ## Why this exists alongside [DurableStrategyDecisionEventLog]
 *
 * [DurableStrategyDecisionEventLog] keeps exactly one mutable slot per
 * [StrategyDecisionId]: the first-recorded terminal outcome, with every
 * later differing outcome reported as
 * [DurableStrategyDecisionRecordOutcome.Conflict] and never persisted (see
 * that class's own "Why outcome mismatches are not treated as a caller bug"
 * documentation). That is deliberately correct for its own job -- a stable,
 * queryable "what is the canonical answer for this decision" record -- but
 * it means a caller who retries the same [StrategyDecisionId] after a
 * transient failure and later succeeds has no durable record that the first
 * attempt ever happened at all once the second is rejected as a conflict at
 * that log.
 *
 * [DurableStrategyDecisionOutcomeHistory] is the append-only counterpart:
 * every attempt -- including ones whose outcome exactly repeats the previous
 * attempt's -- is retained as its own entry, oldest first, up to
 * [maxRetainedAttempts]. It never rejects an attempt as a conflict; there is
 * no "canonical" slot here to protect, only a bounded chronological log.
 * Both types can be configured together against the same
 * [StrategyDecisionId] scope space without interacting -- see
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]'s
 * `recordDecisionEvent`, which durably records into
 * [DurableStrategyDecisionEventLog] first (unchanged), then optionally
 * appends the same [StrategyDecisionEvent] here too.
 *
 * ## List-valued state, not one scope key per attempt
 *
 * Same reasoning [io.dataloom.api.asset.DurableAssetManifestHistory]'s own
 * KDoc documents for its domain: a [DurableStateStore] persists one [TState]
 * value per scope key via atomic load/compare-and-set, so this type keeps a
 * single scope key per [StrategyDecisionId] whose one [TState] value carries
 * every currently retained attempt as a bounded list
 * ([StrategyDecisionOutcomeHistoryState.retainedAttempts]) rather than one
 * scope key per attempt, which would lose "what attempts exist for this
 * decision" queryability without a separate index.
 *
 * ## Bounded retention: count only, no monotonicity check
 *
 * Unlike [io.dataloom.api.asset.DurableAssetManifestHistory] (which rejects a
 * non-monotonic version), every attempt this type is given is accepted --
 * there is no ordering invariant between successive
 * [StrategyDecisionEvent.outcomeKind] values the way there is between
 * successive [io.dataloom.api.asset.AssetManifest.version] values, since a
 * decision can legitimately fail, then fail again, then succeed, in any
 * order a caller's retries actually produce. Only the oldest attempt is
 * evicted once [maxRetainedAttempts] is exceeded, mirroring
 * [io.dataloom.api.asset.DurableAssetManifestHistory]'s own count-based
 * eviction rather than
 * [io.dataloom.api.operational.DurableOperationalEventOutbox]'s count/age
 * pair: a bounded per-decision attempt trail has no independent "how old is
 * too old" requirement distinct from "how many attempts are too many."
 *
 * ## Concurrency
 *
 * Same bounded load-evaluate-compare-and-set retry loop every other
 * [DurableStateStore] adopter in this codebase uses: on a compare-and-set
 * [DurableStateCompareAndSetResult.Conflict], the whole operation re-reads
 * current state and retries, up to [maximumStateUpdateAttempts] times.
 *
 * ## Why diagnostics, not replay
 *
 * Same posture as [DurableStrategyDecisionEventLog] and
 * [io.dataloom.api.asset.DurableAssetManifestHistory]: this is for operator
 * visibility and debugging -- "what did every attempt at decision Y actually
 * terminate with, in order" -- and is never read back by any execution path.
 *
 * @param store durable persistence for this history's
 *   [StrategyDecisionOutcomeHistoryState].
 * @param maxRetainedAttempts the maximum number of attempts kept per
 *   [StrategyDecisionId]. Must be at least `1`. Defaults to `10`, matching
 *   [io.dataloom.api.asset.DurableAssetManifestHistory]'s own default.
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and expects to read.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per [append] call before giving up with
 *   [DurableStrategyDecisionOutcomeAppendOutcome.ContentionLimitReached].
 *   Must be at least `1`.
 */
public class DurableStrategyDecisionOutcomeHistory(
    private val store: DurableStateStore<StrategyDecisionId, StrategyDecisionOutcomeHistoryState>,
    private val maxRetainedAttempts: Int = DEFAULT_MAX_RETAINED_ATTEMPTS,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maxRetainedAttempts >= 1) {
            "maxRetainedAttempts must be at least 1, but was $maxRetainedAttempts."
        }
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /**
     * Every retained attempt for [decisionId], oldest first, bounded by
     * [maxRetainedAttempts]. Empty when [append] has never succeeded for
     * this [decisionId].
     */
    public suspend fun history(
        decisionId: StrategyDecisionId,
    ): ProviderOperationResult<List<StrategyDecisionEvent>> =
        when (val loaded = store.load(decisionId)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                loaded.value.stateOrEmpty().retainedAttempts,
            )
        }

    /**
     * Appends [event] as a new attempt for [decisionId], evicting the oldest
     * retained attempt once [maxRetainedAttempts] is exceeded. Unlike
     * [DurableStrategyDecisionEventLog.record], this never rejects [event] as
     * a conflict -- every attempt is retained as its own entry.
     */
    public suspend fun append(
        decisionId: StrategyDecisionId,
        event: StrategyDecisionEvent,
    ): DurableStrategyDecisionOutcomeAppendOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(decisionId)) {
                is ProviderOperationResult.Failure -> return DurableStrategyDecisionOutcomeAppendOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val expectedVersion = loaded.versionOrNull()
            val currentState = loaded.stateOrEmpty()
            val nextRetained = (currentState.retainedAttempts + event).let { retained ->
                if (retained.size > maxRetainedAttempts) {
                    retained.subList(retained.size - maxRetainedAttempts, retained.size)
                } else {
                    retained
                }
            }
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = decisionId,
                        expectedVersion = expectedVersion,
                        nextState = StrategyDecisionOutcomeHistoryState(nextRetained),
                        nextSchemaVersion = schemaVersion,
                    ),
                )
            ) {
                is ProviderOperationResult.Failure ->
                    return DurableStrategyDecisionOutcomeAppendOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> when (result.value) {
                    is DurableStateCompareAndSetResult.Conflict -> Unit // lost the race; reload and retry
                    is DurableStateCompareAndSetResult.Updated ->
                        return DurableStrategyDecisionOutcomeAppendOutcome.Appended(event, nextRetained.size)
                }
            }
        }
        return DurableStrategyDecisionOutcomeAppendOutcome.ContentionLimitReached
    }

    private fun DurableStateLoadResult<StrategyDecisionOutcomeHistoryState>.stateOrEmpty(): StrategyDecisionOutcomeHistoryState =
        when (this) {
            is DurableStateLoadResult.Missing -> StrategyDecisionOutcomeHistoryState(emptyList())
            is DurableStateLoadResult.Found -> record.state
        }

    private fun DurableStateLoadResult<StrategyDecisionOutcomeHistoryState>.versionOrNull(): Long? =
        when (this) {
            is DurableStateLoadResult.Missing -> null
            is DurableStateLoadResult.Found -> record.version
        }

    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder] for [StrategyDecisionId].
         * [StrategyDecisionId.value] is already validated non-blank and is
         * the entire scope identity, so no escaping/composition is needed --
         * the same reasoning [DurableStrategyDecisionEventLog.KeyEncoder]
         * documents for its own reused-identifier scope. Attached here
         * rather than to [StrategyDecisionId] itself: Kotlin cannot add a
         * companion member to an existing class from a different file.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<StrategyDecisionId> = DurableStateScopeKeyEncoder { it.value }

        private const val DEFAULT_MAX_RETAINED_ATTEMPTS: Int = 10
        private const val DEFAULT_SCHEMA_VERSION: Int = 1
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
