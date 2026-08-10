package io.dataloom.api.policy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant

/**
 * Identifies which committed [PolicyDecision] a [DurablePolicyDecisionLog]
 * call addresses: one [PolicySetId] evaluated for one [ExecutionId].
 *
 * [ExecutionId] is [io.dataloom.api.context.ExecutionContext]'s own required
 * canonical identifier for one execution — the natural idempotency key for
 * "this policy set was already decided for this execution," reused here
 * rather than inventing a second identifier scheme.
 */
public data class PolicyDecisionScope(
    public val policySetId: PolicySetId,
    public val executionId: ExecutionId,
) {
    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder] for [PolicyDecisionScope].
         * Length-prefixes each field before concatenating (the same scheme
         * `circuitBreakerScopeKey`/`scopeStorageKey` already use for
         * [io.dataloom.api.circuit.CircuitBreakerScope]) so that no possible
         * [PolicySetId]/[ExecutionId] content can shift a field boundary and
         * collide with a different scope's encoded key.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<PolicyDecisionScope> = DurableStateScopeKeyEncoder { scope ->
            buildString {
                val policySetId = scope.policySetId.value
                val executionId = scope.executionId.value
                append(policySetId.length)
                append(':')
                append(policySetId)
                append('|')
                append(executionId.length)
                append(':')
                append(executionId)
            }
        }
    }
}

/**
 * Durable `TState` persisted per [PolicyDecisionScope]: the committed
 * [PolicyDecision] and when it was committed.
 */
public data class PolicyDecisionRecord(
    public val decision: PolicyDecision,
    public val committedAt: DataLoomInstant,
)

/** Outcome of one [DurablePolicyDecisionLog.commit] call. */
public sealed interface DurablePolicyDecisionCommitOutcome {

    /** [record] was newly committed — the first commit for its [PolicyDecisionScope]. */
    public data class Committed(public val record: PolicyDecisionRecord) : DurablePolicyDecisionCommitOutcome

    /**
     * A decision was already committed for this scope and it [equals] the
     * one being committed now — an idempotent retry. [record] is the
     * unchanged existing record; nothing new was persisted.
     */
    public data class AlreadyCommitted(public val record: PolicyDecisionRecord) : DurablePolicyDecisionCommitOutcome

    /**
     * A decision was already committed for this scope and it differs from
     * the one being committed now. Nothing was persisted — the original
     * commit is never overwritten. This generally signals a caller bug (the
     * same [PolicyDecisionScope] evaluated against two different
     * [PolicyEvaluationInput]s) rather than an expected runtime condition.
     */
    public data class Conflict(
        public val existing: PolicyDecisionRecord,
        public val attempted: PolicyDecision,
    ) : DurablePolicyDecisionCommitOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurablePolicyDecisionCommitOutcome

    /**
     * [DurablePolicyDecisionLog.maximumStateUpdateAttempts] consecutive
     * compare-and-set attempts all lost the insert race to concurrent
     * committers for the same scope. Nothing was persisted; the caller may
     * retry.
     */
    public data object ContentionLimitReached : DurablePolicyDecisionCommitOutcome
}

/**
 * Durable, commit-once record of [PolicyDecision]s, backed by a [DurableStateStore].
 *
 * ## Why commit-once, not versioned
 *
 * ADR-0002's Policy section states "the resulting decision/evidence is
 * committed with the state transition" — a [PolicyDecision] is evaluated
 * once for one execution and recorded as a fact of what happened, not a
 * value that legitimately changes over time the way
 * [io.dataloom.api.configuration.ConfigurationSnapshot] history does. So
 * unlike [io.dataloom.api.configuration.DurableConfigurationHistory]'s
 * monotonic-version model, [commit] is insert-if-absent: the first commit
 * for a [PolicyDecisionScope] wins, and every later commit for the same
 * scope is judged only by whether it agrees with what is already there —
 * never overwritten.
 *
 * ## Idempotency
 *
 * [PolicyEvaluator.evaluate] is deterministic for the same inputs (see its
 * own KDoc), so a caller retrying "evaluate then commit" after a crash or a
 * duplicate delivery reproduces the same [PolicyDecision], and [commit]
 * simply reports [DurablePolicyDecisionCommitOutcome.AlreadyCommitted]
 * rather than failing. A [DurablePolicyDecisionCommitOutcome.Conflict] — the
 * same scope, a different decision — is returned distinctly rather than
 * folded into ordinary contention, because it points at a genuine caller bug
 * (the same [ExecutionId]/[PolicySetId] pair evaluated against two different
 * [PolicyEvaluationInput]s) rather than an expected race.
 *
 * ## Concurrency
 *
 * Follows the same bounded load-evaluate-compare-and-set retry loop
 * [io.dataloom.api.configuration.DurableConfigurationHistory] and
 * `CircuitBreakerCoordinator` already establish: on losing the insert race,
 * [commit] re-reads and re-evaluates, up to [maximumStateUpdateAttempts]
 * times, before giving up with
 * [DurablePolicyDecisionCommitOutcome.ContentionLimitReached].
 *
 * @param store durable persistence for this log's [PolicyDecisionRecord].
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and expects to read.
 * @param maximumStateUpdateAttempts bounded compare-and-set retry attempts
 *   per [commit] call before giving up with
 *   [DurablePolicyDecisionCommitOutcome.ContentionLimitReached]. Must be at
 *   least `1`.
 */
public class DurablePolicyDecisionLog(
    private val store: DurableStateStore<PolicyDecisionScope, PolicyDecisionRecord>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /** The committed decision for [scope], or `null` if [commit] has never succeeded for it. */
    public suspend fun current(scope: PolicyDecisionScope): ProviderOperationResult<PolicyDecisionRecord?> =
        when (val loaded = store.load(scope)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                (loaded.value as? DurableStateLoadResult.Found)?.record?.state,
            )
        }

    /**
     * Commits [decision] for [scope] if no decision has been committed for it
     * yet. If one already has, this call never overwrites it — it reports
     * whether [decision] agrees with what is already committed instead.
     */
    public suspend fun commit(
        scope: PolicyDecisionScope,
        decision: PolicyDecision,
        committedAt: DataLoomInstant,
    ): DurablePolicyDecisionCommitOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return DurablePolicyDecisionCommitOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            when (loaded) {
                is DurableStateLoadResult.Found -> {
                    val existing = loaded.record.state
                    return if (existing.decision == decision) {
                        DurablePolicyDecisionCommitOutcome.AlreadyCommitted(existing)
                    } else {
                        DurablePolicyDecisionCommitOutcome.Conflict(existing, decision)
                    }
                }
                is DurableStateLoadResult.Missing -> {
                    val record = PolicyDecisionRecord(decision, committedAt)
                    when (
                        val result = store.compareAndSet(
                            DurableStateCompareAndSetRequest(
                                scope = scope,
                                expectedVersion = null,
                                nextState = record,
                                nextSchemaVersion = schemaVersion,
                            ),
                        )
                    ) {
                        is ProviderOperationResult.Failure ->
                            return DurablePolicyDecisionCommitOutcome.PersistenceFailure(result.error)
                        is ProviderOperationResult.Success -> when (result.value) {
                            is DurableStateCompareAndSetResult.Updated ->
                                return DurablePolicyDecisionCommitOutcome.Committed(record)
                            // Lost the insert race; reload and re-evaluate against whatever won it.
                            is DurableStateCompareAndSetResult.Conflict -> Unit
                        }
                    }
                }
            }
        }
        return DurablePolicyDecisionCommitOutcome.ContentionLimitReached
    }

    private companion object {
        const val DEFAULT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
