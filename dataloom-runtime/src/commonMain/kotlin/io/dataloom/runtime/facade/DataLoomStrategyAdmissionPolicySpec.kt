package io.dataloom.runtime.facade

import io.dataloom.api.configuration.ConfigurationSnapshot
import io.dataloom.api.policy.PolicyDecisionRecord
import io.dataloom.api.policy.PolicyDecisionScope
import io.dataloom.api.policy.PolicyEvaluationBudget
import io.dataloom.api.policy.PolicyEvaluator
import io.dataloom.api.policy.PolicySet
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on real strategy-admission
 * policy evaluation: before
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
 * resolves or invokes any provider for a
 * [io.dataloom.api.strategy.StrategySynchronizationRequest], it evaluates
 * [policySet] via [evaluator] and admits the request only when the combined
 * [io.dataloom.api.policy.PolicyDecision] is
 * [io.dataloom.api.policy.PolicyCheckOutcome.Allow].
 *
 * ## Why this exists (DL-039 / `#93`)
 *
 * [io.dataloom.api.policy.PolicyEvaluator.evaluate] and
 * [io.dataloom.api.policy.DurablePolicyDecisionLog] have existed since their
 * own foundational slices, but nothing in `dataloom-runtime` called either
 * one. This spec, `strategyAdmissionPolicyConfiguration`, and the coordinator
 * wiring behind it are that missing connection, mirroring
 * [DataLoomStrategyDiagnosticsSpec]'s own shape and purpose for a different
 * durable-state domain.
 *
 * ## Why [configurationSnapshot] is fixed, not resolved per request
 *
 * [io.dataloom.api.policy.PolicyEvaluationInput.configurationSnapshot] is
 * required (see its own KDoc). Nothing in `dataloom-runtime` currently
 * resolves a live [ConfigurationSnapshot] per request -- that is a separate,
 * independent slice of `#93`
 * ([io.dataloom.api.configuration.DataLoomConfigurationResolver]) with no
 * runtime caller of its own yet either. Rather than block real policy
 * evaluation on that unrelated integration, this spec accepts one fixed,
 * already-resolved [ConfigurationSnapshot] -- built directly via
 * [ConfigurationSnapshot.create] or however the host already produces one --
 * that every evaluation reuses. A caller with no real configuration to route
 * through policy can supply an empty, schema-valid snapshot. A future slice
 * may replace this fixed snapshot with a live per-request resolution once
 * that caller exists.
 *
 * ## Only [io.dataloom.api.policy.PolicyCheckOutcome.Allow] admits
 *
 * This first bounded integration has no dedicated
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult]
 * variant distinguishing [io.dataloom.api.policy.PolicyCheckOutcome.Deny],
 * [io.dataloom.api.policy.PolicyCheckOutcome.RequireUserAction], and
 * [io.dataloom.api.policy.PolicyCheckOutcome.Defer] -- every non-`Allow`
 * outcome uniformly produces
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult.Rejected]
 * with reason
 * [io.dataloom.runtime.strategy.StrategyExecutionRejectionReason.POLICY_DENIED].
 *
 * ## Optional durable recording
 *
 * When [decisionLogStore] is supplied, every evaluated
 * [io.dataloom.api.policy.PolicyDecision] is committed to a
 * [io.dataloom.api.policy.DurablePolicyDecisionLog] built from it -- after
 * the decision has already determined admission, never blocking or altering
 * it. When `null` (the default), decisions are evaluated and enforced but
 * never durably recorded.
 *
 * When this method is not called at all, behavior is unchanged from before
 * this feature existed: no policy is ever evaluated, and
 * [io.dataloom.runtime.strategy.StrategyExecutionRejectionReason.POLICY_DENIED]
 * is never produced.
 *
 * @param policySet the ordered, non-empty set of checks a strategy request
 *   must pass to be admitted.
 * @param evaluator the injected [PolicyEvaluator] used to combine
 *   [policySet] against one [io.dataloom.api.policy.PolicyEvaluationInput]
 *   per admission. Application-owned -- see [PolicyEvaluator]'s own KDoc for
 *   why it must be injected, not accessed through a global singleton.
 * @param budget the time bound enforced on every evaluation. Required; see
 *   [PolicyEvaluationBudget] for why there is no default.
 * @param configurationSnapshot the fixed [ConfigurationSnapshot] every
 *   evaluation's [io.dataloom.api.policy.PolicyEvaluationInput] carries. See
 *   "Why this is fixed" above.
 * @param decisionLogStore optional durable store backing a
 *   [io.dataloom.api.policy.DurablePolicyDecisionLog]. The application
 *   chooses the backing implementation (Room, in-memory, or its own) --
 *   [DataLoomBuilder] does not select one.
 * @param decisionLogSchemaVersion passed through to
 *   [io.dataloom.api.policy.DurablePolicyDecisionLog]'s own schema-version
 *   parameter. Ignored when [decisionLogStore] is `null`.
 * @param decisionLogMaximumStateUpdateAttempts passed through to
 *   [io.dataloom.api.policy.DurablePolicyDecisionLog]'s own retry-bound
 *   parameter. Ignored when [decisionLogStore] is `null`.
 */
public class DataLoomStrategyAdmissionPolicySpec(
    public val policySet: PolicySet,
    public val evaluator: PolicyEvaluator,
    public val budget: PolicyEvaluationBudget,
    public val configurationSnapshot: ConfigurationSnapshot,
    public val decisionLogStore: DurableStateStore<PolicyDecisionScope, PolicyDecisionRecord>? = null,
    public val decisionLogSchemaVersion: Int = 1,
    public val decisionLogMaximumStateUpdateAttempts: Int = 8,
) {
    init {
        require(decisionLogMaximumStateUpdateAttempts >= 1) {
            "DataLoomStrategyAdmissionPolicySpec decisionLogMaximumStateUpdateAttempts must be at least one."
        }
    }

    /** Avoids rendering collaborator implementation state in diagnostics. */
    override fun toString(): String =
        "DataLoomStrategyAdmissionPolicySpec(" +
            "policySetId=${policySet.id}, " +
            "hasDecisionLog=${decisionLogStore != null})"
}
