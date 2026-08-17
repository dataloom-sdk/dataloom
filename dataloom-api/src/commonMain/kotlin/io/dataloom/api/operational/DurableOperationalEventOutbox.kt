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
 * ordered append plus "read everything currently persisted" only. It does
 * not implement acknowledgement, per-item removal, retention/eviction, or
 * filtered/subscription delivery; those remain separate, larger follow-up
 * work.
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
 * process restart, and [entries] reads back everything currently persisted
 * for a scope, oldest first. That is the entire bounded scope of this first
 * slice:
 *
 * - No acknowledgement or per-item removal -- [entries] always returns the
 *   full retained list; there is no "mark consumed" operation.
 * - No retention/eviction policy -- entries accumulate without bound.
 * - No filtering or subscription delivery.
 * - No enumeration across scopes -- a caller must already know which
 *   [OperationalEventOutboxScope] to read.
 *
 * All of the above are real, separately-scoped follow-up work, not
 * oversights.
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
 */
public class DurableOperationalEventOutbox(
    private val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
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
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = scope,
                        expectedVersion = expectedVersion,
                        nextState = OperationalEventOutboxState(currentState.entries + envelope),
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

    private companion object {
        const val DEFAULT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
