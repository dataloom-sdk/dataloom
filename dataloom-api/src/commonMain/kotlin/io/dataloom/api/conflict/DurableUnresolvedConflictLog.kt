package io.dataloom.api.conflict

import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant

/**
 * Payload-free structural summary of one side (local or remote) of a
 * [SynchronizationConflict]'s [io.dataloom.api.change.ChangeEvent], safe to
 * persist durably.
 *
 * Deliberately excludes [io.dataloom.api.change.ChangeEvent.payload] —
 * matching the same "structural identifiers only, never payload content"
 * convention [ConflictOrchestrationResult]'s own safe `toString()` methods
 * already establish for this exact domain. A caller that needs the actual
 * changed content for manual resolution retrieves it separately (for
 * example from a durable event outbox) by [changeEventId] — this record is
 * "a conflict happened, here is its identity," not a resumable snapshot of
 * the conflicting data.
 */
public data class UnresolvedConflictChangeSummary(
    public val changeEventId: ChangeEventId,
    public val operation: ChangeOperation,
    public val metadata: DataLoomMetadata,
)

/**
 * Why a conflict was left unresolved. Mirrors
 * [io.dataloom.runtime.conflict.ConflictOrchestrationStatus]'s own two
 * unresolved-outcome variants — `RESOLVER_NOT_CONFIGURED` and
 * `RESOLVER_NOT_FOUND` — rather than inventing a second vocabulary for the
 * same two conditions.
 */
public enum class UnresolvedConflictReason {
    RESOLVER_NOT_CONFIGURED,
    RESOLVER_NOT_FOUND,
}

/**
 * Durable `TState` persisted per [ConflictId]: everything about an
 * unresolved conflict a future manual (or automated, once configured) pass
 * needs to find and act on it, without ever carrying payload content.
 */
public data class UnresolvedConflictRecord(
    public val conflictType: ConflictType,
    public val entity: EntityReference,
    public val localChange: UnresolvedConflictChangeSummary,
    public val remoteChange: UnresolvedConflictChangeSummary,
    public val conflictMetadata: DataLoomMetadata,
    public val reason: UnresolvedConflictReason,
    public val committedAt: DataLoomInstant,
)

/** Outcome of one [DurableUnresolvedConflictLog.record] call. */
public sealed interface DurableUnresolvedConflictRecordOutcome {

    /** [record] was newly recorded — the first record for its [ConflictId]. */
    public data class Recorded(public val record: UnresolvedConflictRecord) : DurableUnresolvedConflictRecordOutcome

    /**
     * A record already existed for this conflict and it agrees with the one
     * being recorded now (every field except [UnresolvedConflictRecord.committedAt]
     * matches) — an idempotent retry. [record] is the unchanged existing
     * record; nothing new was persisted.
     */
    public data class AlreadyRecorded(
        public val record: UnresolvedConflictRecord,
    ) : DurableUnresolvedConflictRecordOutcome

    /**
     * A record already existed for this conflict and it disagrees with the
     * one being recorded now. Nothing was persisted — the original record is
     * never overwritten. This generally signals a caller bug (the same
     * [ConflictId] reported with different underlying facts) rather than an
     * expected runtime condition.
     */
    public data class Conflict(
        public val existing: UnresolvedConflictRecord,
        public val attempted: UnresolvedConflictRecord,
    ) : DurableUnresolvedConflictRecordOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurableUnresolvedConflictRecordOutcome

    /**
     * [DurableUnresolvedConflictLog.maximumStateUpdateAttempts] consecutive
     * compare-and-set attempts all lost the insert race to concurrent
     * recorders for the same conflict. Nothing was persisted; the caller may
     * retry.
     */
    public data object ContentionLimitReached : DurableUnresolvedConflictRecordOutcome
}

/**
 * Durable, commit-once log of unresolved [SynchronizationConflict]s, backed
 * by a [DurableStateStore] — a bounded first slice of ADR-0002's "durable
 * unresolved[...] state" requirement for the conflict-engine foundation.
 *
 * ## Why unresolved conflicts, not every resolution
 *
 * [io.dataloom.runtime.conflict.SynchronizationConflictOrchestrator] is
 * explicitly documented as never applying resolution decisions "to storage,
 * queues, or any synchronization pipeline" — it is a pure detect/resolve
 * step, and this log does not change that boundary or call the orchestrator.
 * The genuinely durable-worthy case is a conflict that could **not** be
 * automatically resolved ([ConflictOrchestrationResult.ResolverNotConfigured]
 * / [ConflictOrchestrationResult.ResolverNotFound]): without durable
 * persistence, that conflict is simply lost once the caller's in-memory
 * result goes out of scope, with no way for a later manual (or newly
 * configured automated) pass to find it again. A fully *resolved* decision's
 * durability — including [ConflictResolutionDecision.Merge]'s
 * application-supplied [io.dataloom.api.change.ChangeEvent] payload — is a
 * separate, larger design question (this codebase's durable/audit codecs
 * consistently exclude payload content; losslessly persisting a merge
 * payload would be the first exception) and is explicitly out of scope here.
 *
 * ## Commit-once, not versioned
 *
 * Same posture as [io.dataloom.api.policy.DurablePolicyDecisionLog]: a
 * conflict's unresolved facts (type, entity, which changes disagreed, why no
 * resolver ran) do not legitimately change over time. [record] is
 * insert-if-absent; the first record for a [ConflictId] wins and is never
 * overwritten. Marking a conflict resolved, or removing it once a human acts
 * on it, is not implemented here — a real, separately-scoped follow-up
 * concern once a real caller needs it.
 *
 * ## Idempotency
 *
 * A caller retrying "detect, find no resolver, record" after a crash or a
 * duplicate delivery reproduces the same facts for the same [ConflictId], so
 * [record] reports [DurableUnresolvedConflictRecordOutcome.AlreadyRecorded]
 * rather than failing. A [DurableUnresolvedConflictRecordOutcome.Conflict] —
 * the same [ConflictId], different facts — is returned distinctly rather
 * than folded into ordinary contention, because it points at a genuine
 * caller bug (the same conflict identifier reused for two different
 * conflicts) rather than an expected race.
 *
 * ## Concurrency
 *
 * Follows the same bounded load-evaluate-compare-and-set retry loop
 * [io.dataloom.api.policy.DurablePolicyDecisionLog] and
 * `CircuitBreakerCoordinator` already establish.
 *
 * @param store durable persistence for this log's [UnresolvedConflictRecord].
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and expects to read.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per [record] call before giving up with
 *   [DurableUnresolvedConflictRecordOutcome.ContentionLimitReached]. Must be
 *   at least `1`.
 */
public class DurableUnresolvedConflictLog(
    private val store: DurableStateStore<ConflictId, UnresolvedConflictRecord>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /** The recorded unresolved-conflict facts for [conflictId], or `null` if [record] has never succeeded for it. */
    public suspend fun current(conflictId: ConflictId): ProviderOperationResult<UnresolvedConflictRecord?> =
        when (val loaded = store.load(conflictId)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                (loaded.value as? DurableStateLoadResult.Found)?.record?.state,
            )
        }

    /**
     * Records [record] for [conflictId] if no record has been made for it
     * yet. If one already has, this call never overwrites it — it reports
     * whether [record] agrees with what is already recorded instead.
     */
    public suspend fun record(
        conflictId: ConflictId,
        record: UnresolvedConflictRecord,
    ): DurableUnresolvedConflictRecordOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(conflictId)) {
                is ProviderOperationResult.Failure -> return DurableUnresolvedConflictRecordOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            when (loaded) {
                is DurableStateLoadResult.Found -> {
                    val existing = loaded.record.state
                    return if (existing.matchesIgnoringCommittedAt(record)) {
                        DurableUnresolvedConflictRecordOutcome.AlreadyRecorded(existing)
                    } else {
                        DurableUnresolvedConflictRecordOutcome.Conflict(existing, record)
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
                            return DurableUnresolvedConflictRecordOutcome.PersistenceFailure(result.error)
                        is ProviderOperationResult.Success -> when (result.value) {
                            is DurableStateCompareAndSetResult.Updated ->
                                return DurableUnresolvedConflictRecordOutcome.Recorded(record)
                            // Lost the insert race; reload and re-evaluate against whatever won it.
                            is DurableStateCompareAndSetResult.Conflict -> Unit
                        }
                    }
                }
            }
        }
        return DurableUnresolvedConflictRecordOutcome.ContentionLimitReached
    }

    private fun UnresolvedConflictRecord.matchesIgnoringCommittedAt(other: UnresolvedConflictRecord): Boolean =
        conflictType == other.conflictType &&
            entity == other.entity &&
            localChange == other.localChange &&
            remoteChange == other.remoteChange &&
            conflictMetadata == other.conflictMetadata &&
            reason == other.reason

    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder] for [ConflictId]. [ConflictId.value]
         * is already validated non-blank and is the entire scope identity, so no
         * escaping/composition is needed — the same reasoning
         * [io.dataloom.api.configuration.ConfigurationHistoryScope.KeyEncoder]
         * documents for its own single-field scope. Attached here rather than
         * to [ConflictId] itself: unlike
         * [io.dataloom.api.configuration.ConfigurationHistoryScope] and
         * [io.dataloom.api.policy.PolicyDecisionScope], [ConflictId] is a
         * pre-existing shared identifier this log did not introduce, and
         * Kotlin cannot add a companion member to an existing class from a
         * different file.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<ConflictId> = DurableStateScopeKeyEncoder { it.value }

        private const val DEFAULT_SCHEMA_VERSION: Int = 1
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
