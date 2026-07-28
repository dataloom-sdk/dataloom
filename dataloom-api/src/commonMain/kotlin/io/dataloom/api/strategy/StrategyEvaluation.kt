package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode

/** Immutable input to a bounded, side-effect-free strategy evaluation. */
public data class StrategyEvaluationRequest(
    public val decisionId: StrategyDecisionId,
    public val planId: StrategyPlanId,
    public val profile: SynchronizationStrategyProfile,
    public val direction: SynchronizationDirection,
    public val mode: SynchronizationMode,
    public val evidence: StrategyRuntimeEvidence,
)

/**
 * Explainable result of strategy evaluation.
 *
 * [reasonCodes] are stable, non-sensitive identifiers intended for logs,
 * events, and durable decision records. They must never contain arbitrary
 * exception messages, payload data, tenant IDs, or credentials.
 */
public class StrategyEvaluationResult(
    public val decisionId: StrategyDecisionId,
    public val plan: StrategyExecutionPlan,
    reasonCodes: List<String>,
) {
    private val reasons: List<String> = reasonCodes.toList()

    init {
        require(reasons.isNotEmpty()) {
            "StrategyEvaluationResult requires at least one reason code."
        }
        require(reasons.all { it.isNotBlank() }) {
            "StrategyEvaluationResult reason codes must not be blank."
        }
    }

    public val reasonCodes: List<String>
        get() = reasons

    override fun equals(other: Any?): Boolean =
        other is StrategyEvaluationResult &&
            decisionId == other.decisionId &&
            plan == other.plan &&
            reasons == other.reasons

    override fun hashCode(): Int {
        var result = decisionId.hashCode()
        result = 31 * result + plan.hashCode()
        result = 31 * result + reasons.hashCode()
        return result
    }

    override fun toString(): String =
        "StrategyEvaluationResult(decisionId=$decisionId, plan=$plan, reasonCodes=$reasons)"
}

/** Extensible public strategy-policy contract. */
public fun interface SynchronizationStrategyEvaluator {
    /**
     * Evaluates [request] without provider calls, I/O, clock reads, randomness,
     * identifier generation, or mutation.
     */
    public fun evaluate(request: StrategyEvaluationRequest): StrategyEvaluationResult
}

/**
 * Durable, non-sensitive identity recorded whenever strategy work is queued.
 *
 * Reacquisition and retry reuse this record rather than silently re-evaluating
 * the strategy.
 */
public data class PersistedStrategyDecision(
    public val decisionId: StrategyDecisionId,
    public val planId: StrategyPlanId,
    public val requestedStrategy: BuiltInSynchronizationStrategy,
    public val effectiveProfileId: StrategyProfileId,
    public val effectiveStrategy: BuiltInSynchronizationStrategy,
    public val configurationVersion: StrategyConfigurationVersion,
    public val disposition: StrategyDisposition,
)
