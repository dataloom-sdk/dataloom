package io.dataloom.api.conflict

import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant

/**
 * Payload-free classification of a [ConflictResolutionDecision] safe to
 * persist durably -- one value per sealed variant.
 */
public enum class ResolvedConflictDecisionKind {
    USE_LOCAL,
    USE_REMOTE,
    MERGE,
    DEFER,
    FAIL,
}

/**
 * Durable `TState` persisted per [ConflictId]: what a [ConflictResolver]
 * actually decided for a detected [SynchronizationConflict], for operator
 * visibility and audit -- never a resumable snapshot of any payload.
 *
 * ## Avoiding the `Merge` payload landmine
 *
 * [ConflictResolutionDecision.Merge] carries an application-supplied
 * [io.dataloom.api.change.ChangeEvent] whose durable persistence is
 * documented (see [DurableUnresolvedConflictLog]) as "a separate, larger
 * design question ... this codebase's durable/audit codecs consistently
 * exclude payload content; losslessly persisting a merge payload would be
 * the first exception." This record does not take on that exception: when
 * [decisionKind] is [ResolvedConflictDecisionKind.MERGE], [mergedChange]
 * captures only the merged change's structural identity via
 * [UnresolvedConflictChangeSummary] (already documented as excluding
 * [io.dataloom.api.change.ChangeEvent.payload]) -- the same "structural
 * identifiers only" posture this record uses for [localChange] and
 * [remoteChange]. No variant of [ConflictResolutionDecision] ever has its
 * payload content durably persisted by this log.
 *
 * @param conflictType the [SynchronizationConflict.type] this decision was
 *   made for.
 * @param entity the [SynchronizationConflict.entity] this decision was made
 *   for.
 * @param localChange payload-free summary of [SynchronizationConflict.localChange].
 * @param remoteChange payload-free summary of [SynchronizationConflict.remoteChange].
 * @param conflictMetadata the [SynchronizationConflict.metadata] this
 *   decision was made for.
 * @param resolverId the [ConflictResolverId] of the resolver that produced
 *   this decision.
 * @param decisionKind which [ConflictResolutionDecision] variant was
 *   returned.
 * @param decisionMetadata the resolved decision's own optional metadata
 *   (for example [ConflictResolutionDecision.UseRemote.metadata]).
 * @param mergedChange non-`null` only when [decisionKind] is
 *   [ResolvedConflictDecisionKind.MERGE]: a payload-free summary of
 *   [ConflictResolutionDecision.Merge.resolvedChange].
 * @param failureErrorCode non-`null` only when [decisionKind] is
 *   [ResolvedConflictDecisionKind.FAIL]: the bounded, non-sensitive
 *   [io.dataloom.api.error.DataLoomError.code] value from
 *   [ConflictResolutionDecision.Fail.error] -- never the error message.
 * @param committedAt when this record was durably committed.
 */
public data class ResolvedConflictDecisionRecord(
    public val conflictType: ConflictType,
    public val entity: EntityReference,
    public val localChange: UnresolvedConflictChangeSummary,
    public val remoteChange: UnresolvedConflictChangeSummary,
    public val conflictMetadata: DataLoomMetadata,
    public val resolverId: ConflictResolverId,
    public val decisionKind: ResolvedConflictDecisionKind,
    public val decisionMetadata: DataLoomMetadata,
    public val mergedChange: UnresolvedConflictChangeSummary? = null,
    public val failureErrorCode: String? = null,
    public val committedAt: DataLoomInstant,
) {
    init {
        require((decisionKind == ResolvedConflictDecisionKind.MERGE) == (mergedChange != null)) {
            "ResolvedConflictDecisionRecord: mergedChange must be non-null if and only if decisionKind is MERGE."
        }
        require((decisionKind == ResolvedConflictDecisionKind.FAIL) == (failureErrorCode != null)) {
            "ResolvedConflictDecisionRecord: failureErrorCode must be non-null if and only if decisionKind is FAIL."
        }
        require(failureErrorCode == null || failureErrorCode.isNotBlank()) {
            "ResolvedConflictDecisionRecord: failureErrorCode must not be blank when present."
        }
    }
}

/** Outcome of one [DurableResolvedConflictDecisionLog.record] call. */
public sealed interface DurableResolvedConflictDecisionRecordOutcome {

    /** [record] was newly recorded -- the first record for its [ConflictId]. */
    public data class Recorded(
        public val record: ResolvedConflictDecisionRecord,
    ) : DurableResolvedConflictDecisionRecordOutcome

    /**
     * A record already existed for this conflict and it agrees with the one
     * being recorded now (every field except
     * [ResolvedConflictDecisionRecord.committedAt] matches) -- an idempotent
     * retry. [record] is the unchanged existing record; nothing new was
     * persisted.
     */
    public data class AlreadyRecorded(
        public val record: ResolvedConflictDecisionRecord,
    ) : DurableResolvedConflictDecisionRecordOutcome

    /**
     * A record already existed for this conflict and it disagrees with the
     * one being recorded now. Nothing was persisted -- the original record
     * is never overwritten. Since [io.dataloom.api.conflict.ConflictResolver.resolve]
     * is documented as deterministic for the same request, a mismatch here
     * generally signals a caller bug (the same [ConflictId] reported with
     * different underlying facts, or resolved twice by two different
     * resolvers) rather than an expected runtime condition.
     */
    public data class Conflict(
        public val existing: ResolvedConflictDecisionRecord,
        public val attempted: ResolvedConflictDecisionRecord,
    ) : DurableResolvedConflictDecisionRecordOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : DurableResolvedConflictDecisionRecordOutcome

    /**
     * [DurableResolvedConflictDecisionLog.maximumStateUpdateAttempts]
     * consecutive compare-and-set attempts all lost the insert race to
     * concurrent recorders for the same conflict. Nothing was persisted; the
     * caller may retry.
     */
    public data object ContentionLimitReached : DurableResolvedConflictDecisionRecordOutcome
}

/**
 * Durable, commit-once log of *resolved* [SynchronizationConflict] decisions,
 * backed by a [DurableStateStore] -- the sixth real domain adoption of the
 * [DurableStateStore] contract, alongside
 * [io.dataloom.api.configuration.DurableConfigurationHistory],
 * [io.dataloom.api.policy.DurablePolicyDecisionLog],
 * [DurableUnresolvedConflictLog],
 * [io.dataloom.api.operational.DurableOperationalEventOutbox], and
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog].
 *
 * ## Why resolved decisions, not just unresolved ones
 *
 * [DurableUnresolvedConflictLog] durably records conflicts detection could
 * not automatically resolve. It deliberately leaves genuinely *resolved*
 * decisions out of scope, naming their durability "a separate, larger design
 * question given Merge's payload." This log is that follow-up, scoped to
 * avoid the exact landmine named there -- see
 * [ResolvedConflictDecisionRecord]'s own "Avoiding the Merge payload
 * landmine" documentation for how.
 *
 * ## Commit-once, not versioned
 *
 * Same posture as [DurableUnresolvedConflictLog] and
 * [io.dataloom.api.policy.DurablePolicyDecisionLog]: [record] is
 * insert-if-absent; the first record for a [ConflictId] wins and is never
 * overwritten.
 *
 * ## Idempotency
 *
 * A caller retrying "detect, resolve, record" after a crash or a duplicate
 * delivery reproduces the same facts for the same [ConflictId], so [record]
 * reports [DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded]
 * rather than failing. A
 * [DurableResolvedConflictDecisionRecordOutcome.Conflict] -- the same
 * [ConflictId], different facts -- is returned distinctly, matching
 * [DurableUnresolvedConflictLog]'s own posture.
 *
 * ## Concurrency
 *
 * Follows the same bounded load-evaluate-compare-and-set retry loop
 * [DurableUnresolvedConflictLog] and
 * [io.dataloom.api.policy.DurablePolicyDecisionLog] already establish.
 *
 * @param store durable persistence for this log's [ResolvedConflictDecisionRecord].
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and expects to read.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per [record] call before giving up with
 *   [DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached].
 *   Must be at least `1`.
 */
public class DurableResolvedConflictDecisionLog(
    private val store: DurableStateStore<ConflictId, ResolvedConflictDecisionRecord>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /** The recorded resolved-decision facts for [conflictId], or `null` if [record] has never succeeded for it. */
    public suspend fun current(conflictId: ConflictId): ProviderOperationResult<ResolvedConflictDecisionRecord?> =
        when (val loaded = store.load(conflictId)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                (loaded.value as? DurableStateLoadResult.Found)?.record?.state,
            )
        }

    /**
     * Records [record] for [conflictId] if no record has been made for it
     * yet. If one already has, this call never overwrites it -- it reports
     * whether [record] agrees with what is already recorded instead.
     */
    public suspend fun record(
        conflictId: ConflictId,
        record: ResolvedConflictDecisionRecord,
    ): DurableResolvedConflictDecisionRecordOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(conflictId)) {
                is ProviderOperationResult.Failure -> return DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            when (loaded) {
                is DurableStateLoadResult.Found -> {
                    val existing = loaded.record.state
                    return if (existing.matchesIgnoringCommittedAt(record)) {
                        DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded(existing)
                    } else {
                        DurableResolvedConflictDecisionRecordOutcome.Conflict(existing, record)
                    }
                }
                is DurableStateLoadResult.Missing -> {
                    when (
                        val result = store.compareAndSet(
                            DurableStateCompareAndSetRequest(
                                scope = conflictId,
                                expectedVersion = null,
                                nextState = record,
                                nextSchemaVersion = schemaVersion,
                            ),
                        )
                    ) {
                        is ProviderOperationResult.Failure ->
                            return DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure(result.error)
                        is ProviderOperationResult.Success -> when (result.value) {
                            is DurableStateCompareAndSetResult.Updated ->
                                return DurableResolvedConflictDecisionRecordOutcome.Recorded(record)
                            // Lost the insert race; reload and re-evaluate against whatever won it.
                            is DurableStateCompareAndSetResult.Conflict -> Unit
                        }
                    }
                }
            }
        }
        return DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached
    }

    private fun ResolvedConflictDecisionRecord.matchesIgnoringCommittedAt(
        other: ResolvedConflictDecisionRecord,
    ): Boolean =
        conflictType == other.conflictType &&
            entity == other.entity &&
            localChange == other.localChange &&
            remoteChange == other.remoteChange &&
            conflictMetadata == other.conflictMetadata &&
            resolverId == other.resolverId &&
            decisionKind == other.decisionKind &&
            decisionMetadata == other.decisionMetadata &&
            mergedChange == other.mergedChange &&
            failureErrorCode == other.failureErrorCode

    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder] for [ConflictId]. Mirrors
         * [DurableUnresolvedConflictLog.KeyEncoder]'s own reasoning: this log
         * is a second, independent adopter of [ConflictId] as a scope, so it
         * gets its own [DurableStateScopeKeyEncoder] instance rather than
         * sharing one -- Kotlin cannot add a companion member to an existing
         * class from a different file, and different namespaces would make
         * sharing a single encoder instance across two unrelated
         * [DurableStateStore] adoptions confusing regardless.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<ConflictId> = DurableStateScopeKeyEncoder { it.value }

        private const val DEFAULT_SCHEMA_VERSION: Int = 1
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
