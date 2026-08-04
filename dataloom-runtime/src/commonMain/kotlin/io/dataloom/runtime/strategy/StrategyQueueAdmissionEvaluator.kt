package io.dataloom.runtime.strategy

import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProviderCapability

/** Bounded reason why an evaluated strategy plan cannot enter durable queue admission. */
internal enum class StrategyQueueAdmissionRejectionReason {
    PLAN_REJECTED,
    MISSING_DURABLE_QUEUE_OPERATION,
    MISSING_QUEUE_CAPABILITY,
}

/** Provider-free result of validating one evaluated strategy plan for durable admission. */
internal sealed interface StrategyQueueAdmissionResult {

    /** The plan is eligible for durable queue admission. */
    data class Admitted(
        val plan: StrategyExecutionPlan,
        val persistedDecision: PersistedStrategyDecision,
    ) : StrategyQueueAdmissionResult

    /** The plan must not reach storage, queue, scheduler, or transport providers. */
    data class Rejected(
        val planId: StrategyPlanId,
        val reason: StrategyQueueAdmissionRejectionReason,
    ) : StrategyQueueAdmissionResult
}

/**
 * Converts one deterministic strategy evaluation into bounded durable queue evidence.
 *
 * This evaluator performs no provider calls, I/O, clock reads, identifier generation,
 * scheduling, retry evaluation, or mutation. It admits only plans that explicitly contain
 * [StrategyOperation.ENQUEUE_DURABLE_WORK] and explicitly require
 * [StrategyProviderCapability.QUEUE]. A rejected strategy plan can never be converted into
 * queue work.
 */
internal object StrategyQueueAdmissionEvaluator {

    fun evaluate(
        evaluation: StrategyEvaluationResult,
    ): StrategyQueueAdmissionResult {
        val plan = evaluation.plan

        if (plan.disposition == StrategyDisposition.REJECT) {
            return StrategyQueueAdmissionResult.Rejected(
                planId = plan.id,
                reason = StrategyQueueAdmissionRejectionReason.PLAN_REJECTED,
            )
        }

        if (StrategyOperation.ENQUEUE_DURABLE_WORK !in plan.operations) {
            return StrategyQueueAdmissionResult.Rejected(
                planId = plan.id,
                reason = StrategyQueueAdmissionRejectionReason.MISSING_DURABLE_QUEUE_OPERATION,
            )
        }

        if (StrategyProviderCapability.QUEUE !in plan.requiredCapabilities) {
            return StrategyQueueAdmissionResult.Rejected(
                planId = plan.id,
                reason = StrategyQueueAdmissionRejectionReason.MISSING_QUEUE_CAPABILITY,
            )
        }

        return StrategyQueueAdmissionResult.Admitted(
            plan = plan,
            persistedDecision = PersistedStrategyDecision(
                decisionId = evaluation.decisionId,
                planId = plan.id,
                requestedStrategy = plan.requestedStrategy,
                effectiveProfileId = plan.effectiveProfileId,
                effectiveStrategy = plan.effectiveStrategy,
                configurationVersion = plan.configurationVersion,
                disposition = plan.disposition,
            ),
        )
    }
}
