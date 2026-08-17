package io.dataloom.api.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant

/**
 * Payload-free terminal-outcome vocabulary for a [StrategyDecisionEvent],
 * mirroring the sealed
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult]
 * variants a real caller observes -- defined here, in `dataloom-api`, rather
 * than reused directly, the same way [UnresolvedConflictReason] mirrors
 * `ConflictOrchestrationStatus`'s unresolved variants instead of `dataloom-api`
 * depending on `dataloom-runtime`.
 */
public enum class StrategyDecisionOutcomeKind {
    EXECUTED,
    SERVED_FROM_CACHE,
    FAILED,
    FALLBACK_ACTIVATED,
    FALLBACK_UNAVAILABLE,
    CANCELLED,
    DEFERRED,
    DURABLY_ENQUEUED,
    ACCEPTED_LOCALLY,
    REJECTED,
}

/**
 * Durable `TState` persisted per [StrategyDecisionId]: a payload-free
 * diagnostic summary of one strategy admission/execution decision -- what was
 * requested, what plan was actually admitted, and how it terminated -- for
 * operator visibility and debugging, never for replay or continuation.
 *
 * Deliberately excludes [io.dataloom.api.strategy.StrategyTransportOutput],
 * [io.dataloom.api.strategy.StrategyOperationInput], and any provider or
 * error-message content, matching this codebase's consistent "durable/audit
 * codecs carry identity and outcome, never payload content" convention (see
 * [io.dataloom.api.conflict.UnresolvedConflictChangeSummary] and
 * [io.dataloom.api.policy.PolicyDecisionRecord] for the same posture in their
 * own domains). [outcomeDetail], when present, is a short, stable,
 * non-sensitive code -- a rejection-reason name or a
 * [io.dataloom.api.error.DataLoomError.code] value, never an exception
 * message.
 *
 * @param reasonCodes the exact [StrategyEvaluationResult.reasonCodes] for the
 *   evaluation this event records. Already documented as safe for durable
 *   decision records.
 */
public data class StrategyDecisionEvent(
    public val planId: StrategyPlanId,
    public val requestedStrategy: BuiltInSynchronizationStrategy,
    public val effectiveStrategy: BuiltInSynchronizationStrategy,
    public val effectiveProfileId: StrategyProfileId,
    public val configurationVersion: StrategyConfigurationVersion,
    public val direction: SynchronizationDirection,
    public val mode: SynchronizationMode,
    public val disposition: StrategyDisposition,
    public val reasonCodes: List<String>,
    public val outcomeKind: StrategyDecisionOutcomeKind,
    public val outcomeDetail: String?,
    public val committedAt: DataLoomInstant,
) {
    private val reasonCodesSnapshot: List<String> = reasonCodes.toList()

    init {
        require(reasonCodesSnapshot.isNotEmpty()) {
            "StrategyDecisionEvent requires at least one reason code."
        }
        require(outcomeDetail == null || outcomeDetail.isNotBlank()) {
            "StrategyDecisionEvent outcomeDetail must not be blank when present."
        }
    }

    public val reasonCodesList: List<String>
        get() = reasonCodesSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        other is StrategyDecisionEvent &&
            planId == other.planId &&
            requestedStrategy == other.requestedStrategy &&
            effectiveStrategy == other.effectiveStrategy &&
            effectiveProfileId == other.effectiveProfileId &&
            configurationVersion == other.configurationVersion &&
            direction == other.direction &&
            mode == other.mode &&
            disposition == other.disposition &&
            reasonCodesSnapshot == other.reasonCodesSnapshot &&
            outcomeKind == other.outcomeKind &&
            outcomeDetail == other.outcomeDetail &&
            committedAt == other.committedAt

    override fun hashCode(): Int {
        var result = planId.hashCode()
        result = 31 * result + requestedStrategy.hashCode()
        result = 31 * result + effectiveStrategy.hashCode()
        result = 31 * result + effectiveProfileId.hashCode()
        result = 31 * result + configurationVersion.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + disposition.hashCode()
        result = 31 * result + reasonCodesSnapshot.hashCode()
        result = 31 * result + outcomeKind.hashCode()
        result = 31 * result + (outcomeDetail?.hashCode() ?: 0)
        result = 31 * result + committedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "StrategyDecisionEvent(planId=$planId, requestedStrategy=$requestedStrategy, " +
            "effectiveStrategy=$effectiveStrategy, effectiveProfileId=$effectiveProfileId, " +
            "configurationVersion=$configurationVersion, direction=$direction, mode=$mode, " +
            "disposition=$disposition, reasonCodes=$reasonCodesSnapshot, " +
            "outcomeKind=$outcomeKind, outcomeDetail=$outcomeDetail, committedAt=$committedAt)"
}

/** Outcome of one [DurableStrategyDecisionEventLog.record] call. */
public sealed interface DurableStrategyDecisionRecordOutcome {

    /** [event] was newly recorded -- the first record for its [StrategyDecisionId]. */
    public data class Recorded(public val event: StrategyDecisionEvent) : DurableStrategyDecisionRecordOutcome

    /**
     * A record already existed for this decision and it agrees with the one
     * being recorded now (every field except [StrategyDecisionEvent.committedAt]
     * matches, including [StrategyDecisionEvent.outcomeKind]) -- an
     * idempotent retry. [event] is the unchanged existing record; nothing new
     * was persisted.
     */
    public data class AlreadyRecorded(
        public val event: StrategyDecisionEvent,
    ) : DurableStrategyDecisionRecordOutcome

    /**
     * A record already existed for this decision and it does not [equals]
     * the one being recorded now -- in any field, including
     * [StrategyDecisionEvent.outcomeKind]/[StrategyDecisionEvent.outcomeDetail].
     * Nothing was persisted -- the original record is never overwritten.
     *
     * Unlike this codebase's other commit-once durable logs, this does
     * *not* necessarily point at a caller bug: a mismatch confined to
     * [StrategyDecisionEvent.outcomeKind]/[StrategyDecisionEvent.outcomeDetail]
     * with every other field identical is the expected shape of "the same
     * decision was retried and terminated differently the second time" --
     * see [DurableStrategyDecisionEventLog]'s "Why outcome mismatches are
     * not treated as a caller bug" documentation. A mismatch in the
     * evaluated-plan fields themselves (requestedStrategy, effectiveStrategy,
     * disposition, and so on) is still the same "same identifier, different
     * facts" caller-bug signal this codebase's other commit-once logs use
     * [Conflict] for, since [SynchronizationStrategyEvaluator.evaluate] is
     * deterministic for a given [StrategyDecisionId]. This type does not
     * distinguish the two cases structurally; a caller inspecting [existing]
     * vs. [attempted] can tell them apart by comparing every field except
     * [StrategyDecisionEvent.outcomeKind]/[StrategyDecisionEvent.outcomeDetail].
     */
    public data class Conflict(
        public val existing: StrategyDecisionEvent,
        public val attempted: StrategyDecisionEvent,
    ) : DurableStrategyDecisionRecordOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurableStrategyDecisionRecordOutcome

    /**
     * [DurableStrategyDecisionEventLog.maximumStateUpdateAttempts]
     * consecutive compare-and-set attempts all lost the insert race to
     * concurrent recorders for the same decision. Nothing was persisted; the
     * caller may retry.
     */
    public data object ContentionLimitReached : DurableStrategyDecisionRecordOutcome
}

/**
 * Durable, commit-once diagnostic/audit log of past strategy
 * admission/execution decisions, backed by a [DurableStateStore] -- a bounded
 * first slice of the DL-039B six-strategy-engine gate's "durable strategy
 * events/diagnostics" requirement.
 *
 * ## Why diagnostics, not replay
 *
 * This is deliberately distinct from the strategy engine's existing durable
 * *continuation* state ([io.dataloom.api.strategy.PersistedStrategyDecision]
 * and [io.dataloom.api.strategy.StrategyDurableContinuationPlan], persisted
 * via the queue provider for durable-admission replay): that state exists so
 * queued work can resume without re-evaluating current policy. This log
 * exists purely for operator visibility and debugging -- "strategy X was
 * evaluated for decision Y, admitted plan Z, and terminated with outcome W"
 * -- and is never read back by any execution path.
 *
 * ## Why outcome mismatches are not treated as a caller bug
 *
 * Every other commit-once durable log this codebase has adopted so far
 * ([io.dataloom.api.policy.DurablePolicyDecisionLog],
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog]) backs a
 * genuinely deterministic or immutable fact: the same inputs always evaluate
 * to the same decision, so a same-scope mismatch can only mean a caller bug
 * (the same identifier reused for two different facts). [StrategyDecisionId]
 * shares that property for the *evaluated plan* -- [SynchronizationStrategyEvaluator.evaluate]
 * is documented as deterministic -- but not for the *terminal execution
 * outcome* this log also records: a caller may legitimately retry the same
 * [StrategyDecisionId] after a transient failure and observe a different
 * [StrategyDecisionEvent.outcomeKind] the second time (for example `FAILED`
 * then `EXECUTED`). Recording only the plan would lose the operationally
 * interesting half of "what actually happened"; silently overwriting the
 * first outcome would break this log's own commit-once/audit-trail
 * guarantee every other adoption relies on.
 *
 * This bounded first slice resolves that tension by keeping the proven
 * commit-once shape -- [record] never overwrites an existing entry -- but
 * comparing only the deterministic plan fields when deciding
 * [DurableStrategyDecisionRecordOutcome.AlreadyRecorded] vs.
 * [DurableStrategyDecisionRecordOutcome.Conflict]: a retry with the same plan
 * fields and a *different* outcome is still reported as
 * [DurableStrategyDecisionRecordOutcome.Conflict] here, but that is a known,
 * expected occurrence for this domain, not necessarily a caller bug the way
 * it is for policy decisions or unresolved conflicts. Capturing a full
 * per-attempt outcome history for one [StrategyDecisionId] (an append-only
 * shape, rather than one mutable slot) is a deliberately separate, larger
 * design question this slice does not answer -- see this module's
 * documentation for the explicit call-out.
 *
 * ## Concurrency
 *
 * Follows the same bounded load-evaluate-compare-and-set retry loop
 * [io.dataloom.api.policy.DurablePolicyDecisionLog] and
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] already establish.
 *
 * @param store durable persistence for this log's [StrategyDecisionEvent].
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and expects to read.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per [record] call before giving up with
 *   [DurableStrategyDecisionRecordOutcome.ContentionLimitReached]. Must be at
 *   least `1`.
 */
public class DurableStrategyDecisionEventLog(
    private val store: DurableStateStore<StrategyDecisionId, StrategyDecisionEvent>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /** The recorded decision event for [decisionId], or `null` if [record] has never succeeded for it. */
    public suspend fun current(decisionId: StrategyDecisionId): ProviderOperationResult<StrategyDecisionEvent?> =
        when (val loaded = store.load(decisionId)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                (loaded.value as? DurableStateLoadResult.Found)?.record?.state,
            )
        }

    /**
     * Records [event] for [decisionId] if no record has been made for it yet.
     * If one already has, this call never overwrites it -- it reports whether
     * [event] agrees with what is already recorded instead. See this class's
     * "Why outcome mismatches are not treated as a caller bug" documentation
     * for exactly which fields are compared.
     */
    public suspend fun record(
        decisionId: StrategyDecisionId,
        event: StrategyDecisionEvent,
    ): DurableStrategyDecisionRecordOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(decisionId)) {
                is ProviderOperationResult.Failure -> return DurableStrategyDecisionRecordOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            when (loaded) {
                is DurableStateLoadResult.Found -> {
                    val existing = loaded.record.state
                    return if (existing.matchesIgnoringCommittedAt(event)) {
                        DurableStrategyDecisionRecordOutcome.AlreadyRecorded(existing)
                    } else {
                        DurableStrategyDecisionRecordOutcome.Conflict(existing, event)
                    }
                }
                is DurableStateLoadResult.Missing -> {
                    when (
                        val result = store.compareAndSet(
                            DurableStateCompareAndSetRequest(
                                scope = decisionId,
                                expectedVersion = null,
                                nextState = event,
                                nextSchemaVersion = schemaVersion,
                            ),
                        )
                    ) {
                        is ProviderOperationResult.Failure ->
                            return DurableStrategyDecisionRecordOutcome.PersistenceFailure(result.error)
                        is ProviderOperationResult.Success -> when (result.value) {
                            is DurableStateCompareAndSetResult.Updated ->
                                return DurableStrategyDecisionRecordOutcome.Recorded(event)
                            // Lost the insert race; reload and re-evaluate against whatever won it.
                            is DurableStateCompareAndSetResult.Conflict -> Unit
                        }
                    }
                }
            }
        }
        return DurableStrategyDecisionRecordOutcome.ContentionLimitReached
    }

    /**
     * Compares every [StrategyDecisionEvent] field except
     * [StrategyDecisionEvent.committedAt] -- matching every other
     * commit-once durable log in this codebase, which always excludes its
     * own commit timestamp from the idempotent-retry comparison.
     */
    private fun StrategyDecisionEvent.matchesIgnoringCommittedAt(other: StrategyDecisionEvent): Boolean =
        planId == other.planId &&
            requestedStrategy == other.requestedStrategy &&
            effectiveStrategy == other.effectiveStrategy &&
            effectiveProfileId == other.effectiveProfileId &&
            configurationVersion == other.configurationVersion &&
            direction == other.direction &&
            mode == other.mode &&
            disposition == other.disposition &&
            reasonCodesList == other.reasonCodesList &&
            outcomeKind == other.outcomeKind &&
            outcomeDetail == other.outcomeDetail

    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder] for [StrategyDecisionId].
         * [StrategyDecisionId.value] is already validated non-blank and is
         * the entire scope identity, so no escaping/composition is needed --
         * the same reasoning
         * [io.dataloom.api.conflict.DurableUnresolvedConflictLog.KeyEncoder]
         * documents for its own single-field scope. Attached here rather
         * than to [StrategyDecisionId] itself for the same reason: Kotlin
         * cannot add a companion member to an existing class from a
         * different file.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<StrategyDecisionId> = DurableStateScopeKeyEncoder { it.value }

        private const val DEFAULT_SCHEMA_VERSION: Int = 1
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
