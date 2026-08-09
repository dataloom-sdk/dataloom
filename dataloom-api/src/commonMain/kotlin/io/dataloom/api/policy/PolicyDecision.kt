package io.dataloom.api.policy

import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId

/**
 * One [PolicyCheck]'s contribution to a [PolicyDecision]'s evidence trail.
 *
 * [checkId] is attributed by [PolicyEvaluator] itself (which already knows
 * which check it is calling as it iterates [PolicySet.checks]), not
 * self-reported by the check's [PolicyCheckOutcome] — so there is no
 * possibility of a check's outcome disagreeing with its own identity.
 * Creating this value does not itself apply or persist anything.
 *
 * @param checkId the contributing check's [PolicyCheck.id].
 * @param outcome that check's [PolicyCheckOutcome] for the same
 *   [PolicyEvaluationInput].
 */
public data class PolicyCheckEvidence(
    public val checkId: PolicyCheckId,
    public val outcome: PolicyCheckOutcome,
)

/**
 * The outcome of one [PolicyEvaluator.evaluate] call: a single, explainable
 * decision combined from every check in a [PolicySet].
 *
 * ## Explainability
 *
 * [PolicyDecision] is never opaque: [outcome] is always accompanied by
 * [evidence], the complete ordered trail of every check that was actually
 * evaluated, and [winningCheckId] names which one produced [outcome] — or is
 * `null` when [outcome] was synthesized by [PolicyEvaluator] itself rather
 * than by any single check (currently: only when the configured
 * [PolicyEvaluationBudget] was exhausted before every check ran — see
 * [PolicyEvaluator.evaluate]). In that case [evidence] holds whatever prefix
 * of [PolicySet.checks] was evaluated before the budget ran out, which is
 * itself useful diagnostic information, not a discarded partial result.
 *
 * ## Not durable
 *
 * A [PolicyDecision] is a plain in-memory value. Committing a decision and
 * its evidence with a state transition — ADR-0002's "the resulting
 * decision/evidence is committed with the state transition" — is a separate
 * foundation bullet ("Durable state") and explicitly out of scope for this
 * slice; nothing here writes to storage, and there is no commit/store
 * mechanism attached to this type.
 *
 * @param policySetId the [PolicySet.id] this decision was evaluated from.
 * @param outcome the winning [PolicyCheckOutcome] — see precedence in
 *   [PolicyEvaluator.evaluate].
 * @param winningCheckId the [PolicyCheckId] of the check that produced
 *   [outcome], or `null` when [outcome] was synthesized by the evaluator
 *   itself rather than by any single check.
 * @param evidence the complete ordered trail of every check actually
 *   evaluated, in [PolicySet.checks] order. Never empty when [winningCheckId]
 *   is non-null; may be a partial prefix of the originating
 *   [PolicySet.checks] when [winningCheckId] is `null`.
 */
public data class PolicyDecision(
    public val policySetId: PolicySetId,
    public val outcome: PolicyCheckOutcome,
    public val winningCheckId: PolicyCheckId?,
    public val evidence: List<PolicyCheckEvidence>,
)
