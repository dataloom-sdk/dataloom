package io.dataloom.runtime.strategy

import io.dataloom.api.configuration.ConfigurationSnapshot
import io.dataloom.api.policy.DurablePolicyDecisionLog
import io.dataloom.api.policy.PolicyEvaluationBudget
import io.dataloom.api.policy.PolicyEvaluator
import io.dataloom.api.policy.PolicySet

/**
 * Resolved, immutable collaborators for optional strategy-admission policy
 * evaluation, assembled by
 * [io.dataloom.runtime.facade.DataLoomBuilder.strategyAdmissionPolicyConfiguration]
 * from [io.dataloom.runtime.facade.DataLoomStrategyAdmissionPolicySpec].
 *
 * Internal wiring type only -- applications configure
 * [io.dataloom.runtime.facade.DataLoomStrategyAdmissionPolicySpec], not this
 * class directly. See [StrategySynchronizationExecutionCoordinator]'s own
 * KDoc for exactly how this is consulted.
 *
 * @param evaluator the injected [PolicyEvaluator] used to combine [policySet]
 *   against one [io.dataloom.api.policy.PolicyEvaluationInput] per admission.
 * @param policySet the ordered set of checks a request must pass to be
 *   admitted.
 * @param budget the time bound enforced on every evaluation.
 * @param configurationSnapshot the fixed [ConfigurationSnapshot] every
 *   evaluation's [io.dataloom.api.policy.PolicyEvaluationInput] carries. This
 *   slice does not re-resolve configuration per request -- see
 *   [io.dataloom.runtime.facade.DataLoomStrategyAdmissionPolicySpec] for why.
 * @param decisionLog optional durable log every evaluated [io.dataloom.api.policy.PolicyDecision]
 *   is committed to, keyed by [io.dataloom.api.policy.PolicyDecisionScope]. `null`
 *   means decisions are evaluated and enforced but never durably recorded.
 */
internal class StrategyAdmissionPolicyConfiguration(
    val evaluator: PolicyEvaluator,
    val policySet: PolicySet,
    val budget: PolicyEvaluationBudget,
    val configurationSnapshot: ConfigurationSnapshot,
    val decisionLog: DurablePolicyDecisionLog?,
)
