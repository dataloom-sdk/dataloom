package io.dataloom.api.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.jvm.JvmInline
import kotlin.time.Duration

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

/** Outcome of one [DurableOperationalEventOutbox.append] call. */
public sealed interface DurableOperationalEventOutboxAppendOutcome {

    /**
     * [envelope] was newly appended -- the first entry with its [OperationalEventEnvelope.id].
     *
     * @param sequence the durable per-ordering-key sequence number assigned to
     *   the new entry; see [OperationalEventOutboxEntry.sequence].
     */
    public data class Appended(
        public val envelope: OperationalEventEnvelope,
        public val sequence: Long,
    ) : DurableOperationalEventOutboxAppendOutcome

    /**
     * An entry with the same [OperationalEventEnvelope.id] was already
     * appended -- whether it is still pending or already acknowledged and
     * retained -- and it is identical to the one just attempted -- an
     * idempotent retry. [envelope] is the unchanged existing entry and
     * [sequence] its original sequence; nothing new was persisted. Notably a
     * producer retrying an append after its entry was already processed and
     * acknowledged does not resurrect the entry.
     */
    public data class AlreadyAppended(
        public val envelope: OperationalEventEnvelope,
        public val sequence: Long,
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
     * was marked acknowledged at [acknowledgedAt]. It no longer appears in
     * [DurableOperationalEventOutbox.entries]/[DurableOperationalEventOutbox.pendingEntries],
     * and remains readable through [DurableOperationalEventOutbox.acknowledgedEntries]
     * (and replayable through [DurableOperationalEventOutbox.replay]) until
     * acknowledged-history retention prunes it.
     */
    public data class Acknowledged(
        public val envelope: OperationalEventEnvelope,
        public val acknowledgedAt: DataLoomInstant,
    ) : DurableOperationalEventOutboxAcknowledgeOutcome

    /**
     * The entry was already acknowledged and its tombstone is still retained.
     * A well-defined no-op, not a failure: nothing was persisted and the
     * original [acknowledgedAt] is unchanged, so acknowledging the same id
     * twice reports [Acknowledged] once and [AlreadyAcknowledged] every time
     * after -- until retention prunes the tombstone, after which it is
     * [NotFound].
     */
    public data class AlreadyAcknowledged(
        public val envelope: OperationalEventEnvelope,
        public val acknowledgedAt: DataLoomInstant,
    ) : DurableOperationalEventOutboxAcknowledgeOutcome

    /**
     * No entry with the given [OperationalEventId] is currently retained for
     * this scope -- because it was never appended, or count-based and/or
     * age-based retention already evicted it, or its acknowledged tombstone
     * was already pruned. A well-defined no-op, not a failure: nothing was
     * persisted because there was nothing to acknowledge.
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

/** Outcome of one [DurableOperationalEventOutbox.replay] call. */
public sealed interface DurableOperationalEventOutboxReplayOutcome {

    /**
     * The acknowledged [entry] is pending again, at its original position and
     * with its original [OperationalEventOutboxEntry.sequence]. The next
     * [DurableOperationalEventOutbox.entries] read presents it in sequence
     * order, ahead of any entry appended after it.
     */
    public data class Replayed(
        public val entry: OperationalEventOutboxEntry,
    ) : DurableOperationalEventOutboxReplayOutcome

    /**
     * The entry is already pending -- never acknowledged, or already replayed.
     * A well-defined no-op: nothing was persisted.
     */
    public data class AlreadyPending(
        public val entry: OperationalEventOutboxEntry,
    ) : DurableOperationalEventOutboxReplayOutcome

    /**
     * No entry with the given [OperationalEventId] is retained for this scope
     * -- never appended, evicted by retention, or its acknowledged tombstone
     * was already pruned. Nothing can be replayed.
     */
    public data object NotFound : DurableOperationalEventOutboxReplayOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : DurableOperationalEventOutboxReplayOutcome

    /**
     * [DurableOperationalEventOutbox.maximumStateUpdateAttempts] consecutive
     * compare-and-set attempts all lost the race to concurrent writers for
     * the same scope. Nothing was persisted; the caller may retry.
     */
    public data object ContentionLimitReached : DurableOperationalEventOutboxReplayOutcome
}

/**
 * Durable, ordered event outbox for [OperationalEventEnvelope], backed by a
 * [DurableStateStore] -- DL-042's durable-outbox requirement.
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
 * ## What this provides
 *
 * - [append] durably persists one [OperationalEventEnvelope] and assigns it a
 *   durable sequence number (see "Ordering").
 * - [entries] / [pendingEntries] read what is still pending, in order.
 * - [acknowledge] marks one entry done without deleting it (see
 *   "Acknowledgement and replay").
 * - [acknowledgedEntries] / [replay] read and reopen acknowledged history.
 *
 * Not provided: subscription delivery, and enumeration across scopes -- a
 * caller must already know which [OperationalEventOutboxScope] to read. Both
 * are separately-scoped follow-up work, not oversights.
 *
 * ## Ordering (FR-EVENT-003)
 *
 * Every entry carries a durable, monotonically increasing
 * [OperationalEventOutboxEntry.sequence], assigned per
 * [OperationalEventOrderingKey] -- the workflow id, or
 * [OperationalEventOrderingKey.Global] for envelopes without one. The
 * sequence is assigned by [append] *inside* the same compare-and-set write
 * that persists the entry, computed from the very state that write is
 * conditioned on. Two concurrent appenders -- in the same process or across
 * processes sharing one [DurableStateStore] -- can therefore never both
 * persist the same sequence: exactly one compare-and-set wins, and the loser
 * reloads, sees the winner's entry and takes the next sequence. No in-memory
 * counter is involved, so nothing is lost across a restart.
 *
 * The retained list is append-ordered, and within one key list order is
 * sequence order ([OperationalEventOutboxState] validates this on
 * construction, so a corrupt payload cannot decode into a reordered stream).
 * [entries], [pendingEntries], [acknowledgedEntries] and the runtime module's
 * `DurableOperationalEventOutboxProcessor` all present entries in that order,
 * so a workflow's events are presented in
 * the order they were appended -- including after a [replay], which keeps an
 * entry's original position and sequence. Ordering across *different* keys is
 * only the shared append order; nothing promises a relationship between two
 * workflows beyond it.
 *
 * Gaps are allowed: an entry removed by retention leaves its sequence unused
 * forever. Duplicates never are: [OperationalEventOutboxState.sequenceHighWaterMarks]
 * remembers the largest sequence ever assigned per key even after every entry
 * with it has been pruned.
 *
 * ## Retention
 *
 * Two separate populations are bounded separately.
 *
 * **Pending entries** (never acknowledged, or replayed): by default both
 * [maximumRetainedEntries] and [maximumRetainedAge] are `null` and pending
 * entries accumulate without bound, limited only by
 * [OperationalEventOutboxStateCodec]'s own overall-encoded-length safety
 * limit. Eviction here is a hard removal -- a pending entry a consumer never
 * saw is gone, which is the documented cost of bounding retention.
 *
 * - *Count* ([maximumRetainedEntries]): once an [append] would grow the
 *   pending count past the cap, the oldest pending entries (by list
 *   position) are evicted first. Needs no clock read.
 * - *Age* ([maximumRetainedAge]): on each [append], every already-persisted
 *   pending entry (never the one being added) whose
 *   [OperationalEventEnvelope.occurredAt] is older than the age relative to
 *   [clock] is evicted. [OperationalEventEnvelope] never reads a clock and
 *   treats its times as caller-supplied, so a caller whose `occurredAt`
 *   cannot be trusted should prefer the count policy or both together.
 * - *Composition*: age eviction runs first over already-persisted pending
 *   entries, then the new entry is appended, then count eviction runs -- a
 *   fixed order that is part of the contract, since `occurredAt` need not
 *   align with append order.
 *
 * Both never evict the entry the current [append] is adding, all eviction
 * happens in the same compare-and-set write that persists the new entry, and
 * once an entry is evicted its [OperationalEventEnvelope.id] is invisible to
 * the duplicate-id check, so appending it again is a fresh append.
 *
 * **Acknowledged history** (tombstones): bounded by
 * [maximumRetainedAcknowledgedEntries] (default
 * [DEFAULT_MAXIMUM_RETAINED_ACKNOWLEDGED_ENTRIES]; `0` discards on
 * acknowledgement, i.e. no replay window) and optionally
 * [acknowledgedRetentionAge], measured from
 * [OperationalEventOutboxEntry.acknowledgedAt] relative to [clock]. Pruning
 * is deterministic: it runs inside the compare-and-set write of every
 * [append] and [acknowledge]; age pruning drops every tombstone past the
 * window, and count pruning then drops the tombstones with the earliest
 * `acknowledgedAt` first (ties by list position) until the cap holds. It
 * never touches pending entries, and pruning a tombstone never frees its
 * sequence.
 *
 * A [replay]ed entry is pending again at its original (old) position, so
 * it counts against the pending policies like any other pending entry and is
 * among the first candidates for count eviction, and -- if its `occurredAt`
 * is old -- for age eviction, on the next [append]. Callers replaying from a
 * capped or age-bounded outbox should process the replayed entry before
 * appending more.
 *
 * ## Acknowledgement and replay
 *
 * [acknowledge] no longer deletes. It stamps the entry with
 * [OperationalEventOutboxEntry.acknowledgedAt] (read from [clock]) in one
 * compare-and-set write, turning it into a retained tombstone. This is still
 * **operator-driven dismissal from view**, not work-queue completion -- no
 * lease, retry attempt or failure disposition exists here (a caller that needs
 * those belongs on [io.dataloom.api.queue.QueueProvider]) -- but a dismissal
 * is no longer irreversible: [replay] clears the marker on an operator's
 * request, so a downstream failure discovered later can be reprocessed.
 *
 * [replay] is an explicit operation on one entry id. It has no authorization
 * concept of its own, exactly like [acknowledge]: authorizing an operator is
 * the caller's responsibility, and a caller exposing replay to operators
 * should gate it accordingly.
 *
 * Replaying never-acknowledged entries a consumer skipped or failed needs no
 * API at all: they are still pending, so the next pass of the runtime
 * module's `DurableOperationalEventOutboxProcessor` presents them again, in sequence order, ahead of anything appended
 * since.
 *
 * ## Idempotency
 *
 * [append] is commit-once per [OperationalEventEnvelope.id], following the
 * same posture as [io.dataloom.api.conflict.DurableUnresolvedConflictLog]: a
 * caller retrying the same append after a crash or a duplicate delivery
 * reproduces the same envelope, so [append] reports
 * [DurableOperationalEventOutboxAppendOutcome.AlreadyAppended] -- with the
 * originally assigned sequence -- rather than duplicating the entry or
 * failing. This holds for pending and retained-acknowledged entries alike.
 *
 * ## Concurrency
 *
 * [append], [acknowledge] and [replay] all follow the same bounded
 * load-evaluate-compare-and-set retry loop
 * [io.dataloom.api.configuration.DurableConfigurationHistory] and
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] already establish.
 *
 * ## Persisted schema
 *
 * The persisted `TState` is [OperationalEventOutboxState] written with
 * [schemaVersion]; [CURRENT_SCHEMA_VERSION] is `2`, the version that
 * introduced sequence numbers and acknowledgement tombstones (version `1`
 * persisted bare envelopes and hard-deleted on acknowledgement).
 * [OperationalEventOutboxStateCodec] still decodes the version-`1` payload
 * format, assigning sequences in list order.
 *
 * @param store durable persistence for this outbox's [OperationalEventOutboxState].
 * @param clock the time source for acknowledgement timestamps and for every
 *   age-based retention decision. Read at most once per [acknowledge] attempt,
 *   and during [append] only when [maximumRetainedAge] or
 *   [acknowledgedRetentionAge] is set.
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per call before giving up with the respective `ContentionLimitReached`
 *   outcome. Must be at least `1`.
 * @param maximumRetainedEntries the maximum number of *pending* entries kept
 *   per scope, including the one just appended. `null` (the default) means no
 *   count-based eviction. When non-null it must be at least `1`.
 * @param maximumRetainedAge the maximum age, relative to [clock]'s current
 *   reading, a pending entry's [OperationalEventEnvelope.occurredAt] may have
 *   before it is evicted. `null` (the default) means no age-based eviction.
 *   When non-null it must be greater than zero.
 * @param maximumRetainedAcknowledgedEntries the maximum number of
 *   acknowledged tombstones kept per scope. Must not be negative; `0`
 *   discards an entry the moment it is acknowledged.
 * @param acknowledgedRetentionAge the maximum time a tombstone is kept after
 *   [OperationalEventOutboxEntry.acknowledgedAt], relative to [clock]. `null`
 *   (the default) means tombstones are bounded by count only. When non-null it
 *   must be greater than zero.
 */
public class DurableOperationalEventOutbox(
    private val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    private val clock: DataLoomClock,
    private val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
    private val maximumRetainedEntries: Int? = null,
    private val maximumRetainedAge: Duration? = null,
    private val maximumRetainedAcknowledgedEntries: Int = DEFAULT_MAXIMUM_RETAINED_ACKNOWLEDGED_ENTRIES,
    private val acknowledgedRetentionAge: Duration? = null,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
        require(maximumRetainedEntries == null || maximumRetainedEntries >= 1) {
            "maximumRetainedEntries must be at least 1 when set, but was $maximumRetainedEntries."
        }
        require(maximumRetainedAge == null || maximumRetainedAge > Duration.ZERO) {
            "maximumRetainedAge must be greater than zero when set, but was $maximumRetainedAge."
        }
        require(maximumRetainedAcknowledgedEntries >= 0) {
            "maximumRetainedAcknowledgedEntries must not be negative, but was $maximumRetainedAcknowledgedEntries."
        }
        require(acknowledgedRetentionAge == null || acknowledgedRetentionAge > Duration.ZERO) {
            "acknowledgedRetentionAge must be greater than zero when set, but was $acknowledgedRetentionAge."
        }
    }

    /**
     * Every envelope currently pending for [scope], in sequence order (see
     * this class's "Ordering" documentation). Acknowledged entries are not
     * included. Empty (never a failure) if [append] has never succeeded for
     * this scope.
     */
    public suspend fun entries(
        scope: OperationalEventOutboxScope,
    ): ProviderOperationResult<List<OperationalEventEnvelope>> =
        when (val loaded = pendingEntries(scope)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(loaded.value.map { it.envelope })
        }

    /** Like [entries], but with each envelope's [OperationalEventOutboxEntry.sequence]. */
    public suspend fun pendingEntries(
        scope: OperationalEventOutboxScope,
    ): ProviderOperationResult<List<OperationalEventOutboxEntry>> =
        when (val loaded = store.load(scope)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success ->
                ProviderOperationResult.Success(loaded.value.stateOrEmpty().entries.filterNot { it.isAcknowledged })
        }

    /**
     * The retained acknowledged history for [scope]: every acknowledged entry
     * not yet pruned, in sequence order. These are the candidates
     * [replay] can reopen.
     */
    public suspend fun acknowledgedEntries(
        scope: OperationalEventOutboxScope,
    ): ProviderOperationResult<List<OperationalEventOutboxEntry>> =
        when (val loaded = store.load(scope)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success ->
                ProviderOperationResult.Success(loaded.value.stateOrEmpty().entries.filter { it.isAcknowledged })
        }

    /**
     * Appends [envelope] to [scope]'s outbox stream if no entry with the
     * same [OperationalEventEnvelope.id] is retained yet, assigning it the
     * next sequence for its ordering key inside the same compare-and-set
     * write. If one already is, this call never overwrites it -- it reports
     * whether [envelope] agrees with what is already appended instead.
     */
    public suspend fun append(
        scope: OperationalEventOutboxScope,
        envelope: OperationalEventEnvelope,
    ): DurableOperationalEventOutboxAppendOutcome = update(
        scope = scope,
        onPersistenceFailure = { DurableOperationalEventOutboxAppendOutcome.PersistenceFailure(it) },
        onContentionLimit = DurableOperationalEventOutboxAppendOutcome.ContentionLimitReached,
    ) { current ->
        val existing = current.entries.firstOrNull { it.envelope.id == envelope.id }
        if (existing != null) {
            return@update Plan.Done(
                if (existing.envelope == envelope) {
                    DurableOperationalEventOutboxAppendOutcome.AlreadyAppended(existing.envelope, existing.sequence)
                } else {
                    DurableOperationalEventOutboxAppendOutcome.Conflict(existing.envelope, envelope)
                },
            )
        }
        val now: DataLoomInstant? = if (maximumRetainedAge != null || acknowledgedRetentionAge != null) clock.now() else null
        val key = OperationalEventOrderingKey.forWorkflow(envelope.workflowId)
        val sequence = nextSequenceFor(current, key)
        val nextEntries = (current.entries.pendingRetainedByAge(now) + OperationalEventOutboxEntry(sequence, envelope))
            .pendingRetainedByCap()
            .acknowledgedRetained(now)
        val (marks, floor) = boundSequenceTracking(
            entries = nextEntries,
            marks = current.sequenceHighWaterMarks + (key to sequence),
            floor = current.sequenceFloor,
            maximumTrackedKeys = MAXIMUM_TRACKED_ORDERING_KEYS,
        )
        Plan.Write(
            OperationalEventOutboxState(nextEntries, marks, floor),
            DurableOperationalEventOutboxAppendOutcome.Appended(envelope, sequence),
        )
    }

    /**
     * Marks the entry with [id] acknowledged in [scope], if one is currently
     * retained and still pending. See this class's "Acknowledgement and
     * replay" documentation for what this does and does not mean.
     */
    public suspend fun acknowledge(
        scope: OperationalEventOutboxScope,
        id: OperationalEventId,
    ): DurableOperationalEventOutboxAcknowledgeOutcome = update(
        scope = scope,
        onPersistenceFailure = { DurableOperationalEventOutboxAcknowledgeOutcome.PersistenceFailure(it) },
        onContentionLimit = DurableOperationalEventOutboxAcknowledgeOutcome.ContentionLimitReached,
    ) { current ->
        val existing = current.entries.firstOrNull { it.envelope.id == id }
            ?: return@update Plan.Done(DurableOperationalEventOutboxAcknowledgeOutcome.NotFound)
        existing.acknowledgedAt?.let {
            return@update Plan.Done(
                DurableOperationalEventOutboxAcknowledgeOutcome.AlreadyAcknowledged(existing.envelope, it),
            )
        }
        val now = clock.now()
        val nextEntries = current.entries
            .map { if (it.envelope.id == id) it.copy(acknowledgedAt = now) else it }
            .acknowledgedRetained(now)
        Plan.Write(
            current.copy(entries = nextEntries),
            DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged(existing.envelope, now),
        )
    }

    /**
     * Reopens the acknowledged entry with [id] in [scope], making it pending
     * again at its original position and sequence. See this class's
     * "Acknowledgement and replay" documentation.
     */
    public suspend fun replay(
        scope: OperationalEventOutboxScope,
        id: OperationalEventId,
    ): DurableOperationalEventOutboxReplayOutcome = update(
        scope = scope,
        onPersistenceFailure = { DurableOperationalEventOutboxReplayOutcome.PersistenceFailure(it) },
        onContentionLimit = DurableOperationalEventOutboxReplayOutcome.ContentionLimitReached,
    ) { current ->
        val existing = current.entries.firstOrNull { it.envelope.id == id }
            ?: return@update Plan.Done(DurableOperationalEventOutboxReplayOutcome.NotFound)
        if (!existing.isAcknowledged) {
            return@update Plan.Done(DurableOperationalEventOutboxReplayOutcome.AlreadyPending(existing))
        }
        val reopened = existing.copy(acknowledgedAt = null)
        Plan.Write(
            current.copy(entries = current.entries.map { if (it.envelope.id == id) reopened else it }),
            DurableOperationalEventOutboxReplayOutcome.Replayed(reopened),
        )
    }

    /** What one attempt of [update] decided, given the state it loaded. */
    private sealed interface Plan<out O> {
        /** Nothing to persist; report [outcome]. */
        class Done<O>(val outcome: O) : Plan<O>

        /** Persist [next] conditioned on the state that was loaded; report [outcome] if it lands. */
        class Write<O>(val next: OperationalEventOutboxState, val outcome: O) : Plan<O>
    }

    /**
     * The shared bounded load-evaluate-compare-and-set loop. [plan] is pure
     * with respect to the store: it sees the freshly loaded state and either
     * finishes without writing or proposes the next state, which is persisted
     * only if no concurrent writer changed the scope since that load --
     * otherwise the loop reloads and asks [plan] again, so every proposal
     * (including an assigned sequence) is always computed from the exact state
     * it is conditioned on.
     */
    private suspend fun <O> update(
        scope: OperationalEventOutboxScope,
        onPersistenceFailure: (DataLoomError) -> O,
        onContentionLimit: O,
        plan: (OperationalEventOutboxState) -> Plan<O>,
    ): O {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return onPersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val write = when (val decided = plan(loaded.stateOrEmpty())) {
                is Plan.Done -> return decided.outcome
                is Plan.Write -> decided
            }
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = scope,
                        expectedVersion = loaded.versionOrNull(),
                        nextState = write.next,
                        nextSchemaVersion = schemaVersion,
                    ),
                )
            ) {
                is ProviderOperationResult.Failure -> return onPersistenceFailure(result.error)
                is ProviderOperationResult.Success -> when (result.value) {
                    is DurableStateCompareAndSetResult.Conflict -> Unit // lost the race; reload and retry
                    is DurableStateCompareAndSetResult.Updated -> return write.outcome
                }
            }
        }
        return onContentionLimit
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
     * Drops the oldest *pending* entries, if needed, so at most
     * [maximumRetainedEntries] pending entries remain -- a no-op when
     * [maximumRetainedEntries] is `null` or the pending count is already
     * within the cap. Acknowledged tombstones are neither counted nor
     * dropped here. See this class's "Retention" documentation.
     */
    private fun List<OperationalEventOutboxEntry>.pendingRetainedByCap(): List<OperationalEventOutboxEntry> {
        val cap = maximumRetainedEntries ?: return this
        var toDrop = count { !it.isAcknowledged } - cap
        if (toDrop <= 0) return this
        return filter { entry ->
            if (!entry.isAcknowledged && toDrop > 0) {
                toDrop--
                false
            } else {
                true
            }
        }
    }

    /**
     * Drops every *pending* entry whose [OperationalEventEnvelope.occurredAt]
     * is older than [maximumRetainedAge] relative to [now] -- a no-op when
     * [maximumRetainedAge] is `null` or [now] is unavailable (which only
     * happens if no age policy is set). Operates only on already-persisted
     * entries, never the entry the current [append] call is itself adding.
     */
    private fun List<OperationalEventOutboxEntry>.pendingRetainedByAge(now: DataLoomInstant?): List<OperationalEventOutboxEntry> {
        val maxAgeMillis = maximumRetainedAge?.inWholeMilliseconds ?: return this
        val nowMillis = now?.epochMilliseconds ?: return this
        return filter { it.isAcknowledged || nowMillis - it.envelope.occurredAt.epochMilliseconds <= maxAgeMillis }
    }

    /**
     * Prunes acknowledged tombstones: first every tombstone older than
     * [acknowledgedRetentionAge] relative to [now], then -- if more than
     * [maximumRetainedAcknowledgedEntries] remain -- the ones with the
     * earliest [OperationalEventOutboxEntry.acknowledgedAt] (ties by list
     * position). Never touches pending entries.
     */
    private fun List<OperationalEventOutboxEntry>.acknowledgedRetained(now: DataLoomInstant?): List<OperationalEventOutboxEntry> {
        val ageMillis = acknowledgedRetentionAge?.inWholeMilliseconds
        val aged = if (ageMillis != null && now != null) {
            filter { entry ->
                val at = entry.acknowledgedAt
                at == null || now.epochMilliseconds - at.epochMilliseconds <= ageMillis
            }
        } else {
            this
        }
        val excess = aged.count { it.isAcknowledged } - maximumRetainedAcknowledgedEntries
        if (excess <= 0) return aged
        val evictedIndexes = aged.withIndex()
            .filter { it.value.isAcknowledged }
            .sortedWith(compareBy({ it.value.acknowledgedAt!!.epochMilliseconds }, { it.index }))
            .take(excess)
            .mapTo(HashSet()) { it.index }
        return aged.filterIndexed { index, _ -> index !in evictedIndexes }
    }

    public companion object {
        /**
         * The [OperationalEventOutboxState] persisted schema version this
         * class writes by default: `2` introduced per-key sequence numbers and
         * acknowledgement tombstones.
         */
        public const val CURRENT_SCHEMA_VERSION: Int = 2

        /** Default cap on retained acknowledged tombstones per scope. */
        public const val DEFAULT_MAXIMUM_RETAINED_ACKNOWLEDGED_ENTRIES: Int = 1_000

        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8

        /** Same bound [OperationalEventOutboxStateCodec] enforces on persisted high-water marks. */
        internal const val MAXIMUM_TRACKED_ORDERING_KEYS: Int = 10_000
    }
}
