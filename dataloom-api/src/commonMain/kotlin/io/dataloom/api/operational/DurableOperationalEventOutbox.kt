package io.dataloom.api.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import kotlin.jvm.JvmInline

/**
 * Identifies which durable operational-event outbox stream a
 * [DurableOperationalEventOutbox] call addresses -- for example, one per
 * application, tenant, or subsystem sharing a single [DurableStateStore].
 *
 * Deliberately the same flat, single-field shape
 * [io.dataloom.api.configuration.ConfigurationHistoryScope] and
 * [io.dataloom.api.policy.PolicyDecisionScope] already use for their own
 * single-field scopes.
 */
@JvmInline
public value class OperationalEventOutboxScope(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "OperationalEventOutboxScope must not be blank." }
    }

    override fun toString(): String = value

    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder] for [OperationalEventOutboxScope].
         * [value] is already validated non-blank and is the entire scope
         * identity, so no escaping/composition is needed -- the same reasoning
         * [io.dataloom.api.configuration.ConfigurationHistoryScope] documents
         * for its own single-field scope.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<OperationalEventOutboxScope> =
            DurableStateScopeKeyEncoder { it.value }
    }
}

/**
 * Durable `TState` persisted per [OperationalEventOutboxScope]: every
 * [OperationalEventEnvelope] appended to this outbox stream so far, in
 * append order (oldest first).
 *
 * This is a bounded first slice of DL-042's durable-outbox requirement --
 * ordered append, optional count-based retention, operator-driven
 * per-entry acknowledgement (see [DurableOperationalEventOutbox]'s
 * "Retention" and "Acknowledgement" documentation), and "read everything
 * currently retained" only. It does not implement filtered/subscription
 * delivery or cross-scope enumeration; those remain separate, larger
 * follow-up work.
 */
public data class OperationalEventOutboxState(
    public val entries: List<OperationalEventEnvelope>,
)

/** Outcome of one [DurableOperationalEventOutbox.append] call. */
public sealed interface DurableOperationalEventOutboxAppendOutcome {

    /** [envelope] was newly appended -- the first entry with its [OperationalEventEnvelope.id]. */
    public data class Appended(
        public val envelope: OperationalEventEnvelope,
    ) : DurableOperationalEventOutboxAppendOutcome

    /**
     * An entry with the same [OperationalEventEnvelope.id] was already
     * appended and it is identical to the one just attempted -- an
     * idempotent retry. [envelope] is the unchanged existing entry; nothing
     * new was persisted.
     */
    public data class AlreadyAppended(
        public val envelope: OperationalEventEnvelope,
    ) : DurableOperationalEventOutboxAppendOutcome

    /**
     * An entry with the same [OperationalEventEnvelope.id] was already
     * appended and it differs from [attempted]. Nothing was persisted -- the
     * original entry is never overwritten. This generally signals a caller
     * bug (the same event identifier reused for two different events) rather
     * than an expected runtime condition.
     */
    public data class Conflict(
        public val existing: OperationalEventEnvelope,
        public val attempted: OperationalEventEnvelope,
    ) : DurableOperationalEventOutboxAppendOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : DurableOperationalEventOutboxAppendOutcome

    /**
     * [DurableOperationalEventOutbox.maximumStateUpdateAttempts] consecutive
     * compare-and-set attempts all lost the race to concurrent appenders for
     * the same scope. Nothing was persisted; the caller may retry.
     */
    public data object ContentionLimitReached : DurableOperationalEventOutboxAppendOutcome
}

/**
 * Outcome of one [DurableOperationalEventOutbox.acknowledge] call. See that
 * class's "Acknowledgement" documentation for what acknowledgement does and
 * does not mean here.
 */
public sealed interface DurableOperationalEventOutboxAcknowledgeOutcome {

    /**
     * [envelope] -- the entry with the acknowledged [OperationalEventId] --
     * was removed from the retained list. A subsequent [DurableOperationalEventOutbox.entries]
     * call for the same scope no longer includes it.
     */
    public data class Acknowledged(
        public val envelope: OperationalEventEnvelope,
    ) : DurableOperationalEventOutboxAcknowledgeOutcome

    /**
     * No entry with the given [OperationalEventId] is currently retained for
     * this scope -- because it was never appended, it was already
     * acknowledged, or count-based retention already evicted it. A
     * well-defined no-op, not a failure: nothing was persisted because there
     * was nothing left to remove. Safe to treat acknowledgement as
     * idempotent -- acknowledging the same id twice reports [Acknowledged]
     * once and [NotFound] every time after.
     */
    public data object NotFound : DurableOperationalEventOutboxAcknowledgeOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : DurableOperationalEventOutboxAcknowledgeOutcome

    /**
     * [DurableOperationalEventOutbox.maximumStateUpdateAttempts] consecutive
     * compare-and-set attempts all lost the race to concurrent writers for
     * the same scope. Nothing was persisted; the caller may retry.
     */
    public data object ContentionLimitReached : DurableOperationalEventOutboxAcknowledgeOutcome
}

/**
 * Durable, ordered-append event outbox for [OperationalEventEnvelope],
 * backed by a [DurableStateStore] -- a bounded first slice of DL-042's
 * durable-outbox requirement.
 *
 * ## Why [DurableStateStore], not [io.dataloom.api.queue.QueueProvider]
 *
 * [io.dataloom.api.queue.QueueProvider] already has durable enqueue/acquire
 * semantics, but its [io.dataloom.api.queue.QueueEntry] is DataLoom's own
 * synchronization workflow execution record -- it requires a
 * [io.dataloom.api.model.SynchronizationRequest] and carries lease,
 * retry-attempt, and strategy-plan fields that have no meaning for an
 * arbitrary operational event. Reusing it here would mean either forcing
 * events through work-item lease/acquire/complete/fail semantics they do not
 * need, or breaking [io.dataloom.api.queue.QueueEntry] to make its
 * synchronization fields optional -- both riskier than the option actually
 * taken. [DurableStateStore]'s own documentation already names "event
 * outbox/audit" as a domain it was generalized to serve, and this codebase's
 * established precedent
 * ([io.dataloom.api.configuration.DurableConfigurationHistory],
 * [io.dataloom.api.policy.DurablePolicyDecisionLog],
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog]) is exactly this
 * shape: a per-scope [DurableStateStore] compare-and-set retry loop over a
 * `TState` that itself holds an ordered list.
 *
 * ## What this does and does not provide
 *
 * [append] durably persists one [OperationalEventEnvelope] so it survives a
 * process restart, [entries] reads back everything currently retained for a
 * scope, oldest first, and [acknowledge] lets a caller remove one entry it
 * has already dealt with. That is the entire bounded scope of this first
 * slice:
 *
 * - No filtering or subscription delivery.
 * - No enumeration across scopes -- a caller must already know which
 *   [OperationalEventOutboxScope] to read.
 *
 * Both of the above are real, separately-scoped follow-up work, not
 * oversights.
 *
 * ## Retention
 *
 * By default [maximumRetainedEntries] is `null` and behavior is byte-for-byte
 * unchanged from before this policy existed: entries accumulate without
 * bound, limited only by [OperationalEventOutboxStateCodec]'s own
 * overall-encoded-length safety limit. A caller that sets
 * [maximumRetainedEntries] gets a bounded first slice of real retention
 * instead: once an [append] would grow [OperationalEventOutboxState.entries]
 * past that count, the oldest entries are evicted first -- as many as needed
 * to bring the retained count back down to [maximumRetainedEntries] -- as
 * part of the very same compare-and-set write that persists the new entry,
 * never a separate follow-up write. This mirrors
 * [io.dataloom.api.configuration.DurableConfigurationHistory]'s own
 * `maxRetainedVersions` shape -- a count-based cap enforced by trimming the
 * oldest entries off an ordered list inside the same atomic update -- which
 * is this codebase's only existing precedent for bounding an ordered
 * [DurableStateStore]-backed list.
 *
 * A count-based cap was chosen over an age-based one: eviction happens
 * entirely from the list's own `size`, so it needs no clock read and no
 * dependency on [OperationalEventEnvelope.occurredAt] being trustworthy as a
 * "now" reference -- consistent with [OperationalEventEnvelope] itself
 * documenting that it "never reads a global clock" and treats every time
 * value as caller-supplied, not authoritative. It also composes directly
 * with the outbox's own "ordered, oldest-first" contract: the entries to
 * evict are always exactly the current list's leading elements.
 *
 * Eviction only ever removes entries older than what [append] itself is
 * adding, so it can never evict the entry an [append] call just persisted.
 * An evicted entry's [OperationalEventEnvelope.id] is no longer visible to
 * the same-id [DurableOperationalEventOutboxAppendOutcome.AlreadyAppended] /
 * [DurableOperationalEventOutboxAppendOutcome.Conflict] duplicate check --
 * once retention has evicted an entry, appending its identifier again is
 * indistinguishable from a genuinely new entry. That is an accepted
 * consequence of bounding retention, not an oversight.
 *
 * ## Acknowledgement
 *
 * [acknowledge] removes exactly one entry -- the one whose
 * [OperationalEventEnvelope.id] matches -- from a scope's retained list, as
 * part of one atomic compare-and-set write, using the same
 * load-evaluate-compare-and-set retry loop [append] already uses.
 *
 * This is deliberately **operator-driven dismissal from view**, not
 * work-queue completion. Every real caller of this outbox so far
 * (`SynchronizationOperationalEventBridge`,
 * `RetryCircuitAdministrationOperationalEventBridge`,
 * `StrategyDecisionOperationalEventBridge`,
 * `QueueLifecycleOperationalEventBridge`) durably appends an envelope as a
 * side-record purely for operator visibility and debugging, after the real
 * operation it describes has already happened -- it never reads [entries]
 * back to decide what to do next, and append outcomes other than success are
 * always swallowed rather than gated on. Nothing in this outbox's existing
 * shape ever depended on an entry surviving until some future "processing"
 * step, so [acknowledge] does not need to invent or enforce that guarantee
 * either: it is the durable counterpart of an operator clearing a diagnostic
 * entry they have already reviewed out of a dashboard list, not a queue
 * consumer marking a work item done. A caller that genuinely needs
 * lease/acquire/complete queue semantics still belongs on
 * [io.dataloom.api.queue.QueueProvider], for the same reasons this class's
 * own "Why [DurableStateStore], not [io.dataloom.api.queue.QueueProvider]"
 * documentation above already gives.
 *
 * [acknowledge] composes with retention with no special-case handling
 * needed: both operate on the exact same persisted
 * [OperationalEventOutboxState.entries] list by producing a new list with
 * some entries removed, so acknowledging an entry retention already evicted,
 * or retention evicting an entry that was already acknowledged, are simply
 * the same "entry no longer present" state reached two different ways --
 * [acknowledge] reports [DurableOperationalEventOutboxAcknowledgeOutcome.NotFound]
 * for the former, and a later [append] evicting further entries never
 * revisits an id [acknowledge] already removed, mirroring how
 * already-evicted ids are already invisible to [append]'s own duplicate-id
 * check (see "Retention" above).
 *
 * ## Idempotency
 *
 * [append] is commit-once per [OperationalEventEnvelope.id], following the
 * same posture as [io.dataloom.api.conflict.DurableUnresolvedConflictLog]: a
 * caller retrying the same append after a crash or a duplicate delivery
 * reproduces the same envelope, so [append] reports
 * [DurableOperationalEventOutboxAppendOutcome.AlreadyAppended] rather than
 * duplicating the entry or failing.
 *
 * ## Concurrency
 *
 * Follows the same bounded load-evaluate-compare-and-set retry loop
 * [io.dataloom.api.configuration.DurableConfigurationHistory] and
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] already establish.
 *
 * @param store durable persistence for this outbox's [OperationalEventOutboxState].
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and expects to read.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per [append] call before giving up with
 *   [DurableOperationalEventOutboxAppendOutcome.ContentionLimitReached]. Must
 *   be at least `1`.
 * @param maximumRetainedEntries the maximum number of entries kept per scope,
 *   including the one just appended. `null` (the default) means no
 *   eviction -- byte-for-byte the same unbounded-accumulation behavior this
 *   outbox had before this parameter existed. When non-null it must be at
 *   least `1`; see this class's "Retention" documentation.
 */
public class DurableOperationalEventOutbox(
    private val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
    private val maximumRetainedEntries: Int? = null,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
        require(maximumRetainedEntries == null || maximumRetainedEntries >= 1) {
            "maximumRetainedEntries must be at least 1 when set, but was $maximumRetainedEntries."
        }
    }

    /**
     * Every envelope currently persisted for [scope], oldest first. Empty
     * (never a failure) if [append] has never succeeded for this scope.
     */
    public suspend fun entries(
        scope: OperationalEventOutboxScope,
    ): ProviderOperationResult<List<OperationalEventEnvelope>> =
        when (val loaded = store.load(scope)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(loaded.value.stateOrEmpty().entries)
        }

    /**
     * Appends [envelope] to [scope]'s outbox stream if no entry with the
     * same [OperationalEventEnvelope.id] has been appended yet. If one
     * already has, this call never overwrites it -- it reports whether
     * [envelope] agrees with what is already appended instead.
     */
    public suspend fun append(
        scope: OperationalEventOutboxScope,
        envelope: OperationalEventEnvelope,
    ): DurableOperationalEventOutboxAppendOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return DurableOperationalEventOutboxAppendOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val expectedVersion = loaded.versionOrNull()
            val currentState = loaded.stateOrEmpty()
            val existing = currentState.entries.firstOrNull { it.id == envelope.id }
            if (existing != null) {
                return if (existing == envelope) {
                    DurableOperationalEventOutboxAppendOutcome.AlreadyAppended(existing)
                } else {
                    DurableOperationalEventOutboxAppendOutcome.Conflict(existing, envelope)
                }
            }
            val nextEntries = (currentState.entries + envelope).retainedByCap()
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = scope,
                        expectedVersion = expectedVersion,
                        nextState = OperationalEventOutboxState(nextEntries),
                        nextSchemaVersion = schemaVersion,
                    ),
                )
            ) {
                is ProviderOperationResult.Failure ->
                    return DurableOperationalEventOutboxAppendOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> when (result.value) {
                    is DurableStateCompareAndSetResult.Conflict -> Unit // lost the race; reload and retry
                    is DurableStateCompareAndSetResult.Updated ->
                        return DurableOperationalEventOutboxAppendOutcome.Appended(envelope)
                }
            }
        }
        return DurableOperationalEventOutboxAppendOutcome.ContentionLimitReached
    }

    /**
     * Removes the entry with [id] from [scope]'s retained list, if one is
     * currently retained. See this class's "Acknowledgement" documentation
     * for what this does and does not mean.
     */
    public suspend fun acknowledge(
        scope: OperationalEventOutboxScope,
        id: OperationalEventId,
    ): DurableOperationalEventOutboxAcknowledgeOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return DurableOperationalEventOutboxAcknowledgeOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val expectedVersion = loaded.versionOrNull()
            val currentState = loaded.stateOrEmpty()
            val existing = currentState.entries.firstOrNull { it.id == id }
                ?: return DurableOperationalEventOutboxAcknowledgeOutcome.NotFound
            val nextEntries = currentState.entries.filterNot { it.id == id }
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = scope,
                        expectedVersion = expectedVersion,
                        nextState = OperationalEventOutboxState(nextEntries),
                        nextSchemaVersion = schemaVersion,
                    ),
                )
            ) {
                is ProviderOperationResult.Failure ->
                    return DurableOperationalEventOutboxAcknowledgeOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> when (result.value) {
                    is DurableStateCompareAndSetResult.Conflict -> Unit // lost the race; reload and retry
                    is DurableStateCompareAndSetResult.Updated ->
                        return DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged(existing)
                }
            }
        }
        return DurableOperationalEventOutboxAcknowledgeOutcome.ContentionLimitReached
    }

    private fun DurableStateLoadResult<OperationalEventOutboxState>.stateOrEmpty(): OperationalEventOutboxState =
        when (this) {
            is DurableStateLoadResult.Missing -> OperationalEventOutboxState(emptyList())
            is DurableStateLoadResult.Found -> record.state
        }

    private fun DurableStateLoadResult<OperationalEventOutboxState>.versionOrNull(): Long? =
        when (this) {
            is DurableStateLoadResult.Missing -> null
            is DurableStateLoadResult.Found -> record.version
        }

    /**
     * Drops the oldest entries, if needed, so at most [maximumRetainedEntries]
     * remain -- a no-op when [maximumRetainedEntries] is `null` or the list is
     * already within the cap. See this class's "Retention" documentation.
     */
    private fun List<OperationalEventEnvelope>.retainedByCap(): List<OperationalEventEnvelope> {
        val cap = maximumRetainedEntries ?: return this
        return if (size > cap) subList(size - cap, size) else this
    }

    private companion object {
        const val DEFAULT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
