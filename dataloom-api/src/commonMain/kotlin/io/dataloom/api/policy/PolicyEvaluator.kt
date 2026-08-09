package io.dataloom.api.policy

import io.dataloom.api.configuration.ConfigurationSnapshot
import io.dataloom.api.configuration.ConfigurationValue
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.time.DataLoomMonotonicClock

/**
 * Deterministic combinator that evaluates a [PolicySet] against one
 * [PolicyEvaluationInput] and produces one [PolicyDecision].
 *
 * [PolicyEvaluator] is this foundation's top-level evaluation entry point.
 * Unlike [PolicyCheck] — the application/subsystem extension point — the
 * combining algorithm itself is fixed by this class, the same way
 * [io.dataloom.api.configuration.DataLoomConfigurationResolver]'s merge
 * algorithm is fixed while its [io.dataloom.api.configuration.ConfigurationSource]
 * inputs are the extension point.
 *
 * ## Evaluation order and evidence
 *
 * Checks in [PolicySet.checks] are evaluated in list order. Every check that
 * runs contributes one [PolicyCheckEvidence] entry, in the order it ran —
 * this evaluator does not short-circuit on the first [PolicyCheckOutcome.Deny]
 * it sees, so that [PolicyDecision.evidence] is a complete trail rather than
 * stopping at the first disqualifying check, except when the
 * [PolicyEvaluationBudget] is exhausted first (see below).
 *
 * ## Precedence — deny dominates allow
 *
 * If any evaluated check produced [PolicyCheckOutcome.Deny], the decision's
 * [PolicyDecision.outcome] is a `Deny` — specifically, the first `Deny` in
 * evaluation order — regardless of how many checks produced `Allow`. This
 * rule has **no configuration override**: ADR-0002 states "deny dominates
 * allow" as an unqualified rule, in contrast to the immediately following,
 * explicitly qualified "required user action dominates delay unless an
 * approved configuration says otherwise." There is deliberately no key in
 * [PolicyConfigurationKeys] that touches deny's dominance; weakening a deny
 * is not a decision this generic foundation exposes a lever for.
 *
 * ## Precedence — required user action dominates delay, unless configured otherwise
 *
 * Absent any `Deny`: if any evaluated check produced
 * [PolicyCheckOutcome.RequireUserAction] and any produced
 * [PolicyCheckOutcome.Defer], this evaluator reads
 * `input.configurationSnapshot[`[PolicyConfigurationKeys.DEFER_DOMINATES_REQUIRE_USER_ACTION]`]`.
 * When that entry is a [ConfigurationValue.BooleanValue] equal to `true`, the
 * first `Defer` in evaluation order wins; otherwise (missing, `false`, or a
 * different [ConfigurationValue] type) the first `RequireUserAction` in
 * evaluation order wins.
 *
 * ## Full precedence order
 *
 * Combining the two rules above with the natural remaining cases yields one
 * total order per evaluation: `Deny` > (`RequireUserAction` or `Defer`, per
 * the override above) > (the other of the two) > `Allow`. Within whichever
 * outcome kind wins, the earliest (lowest-index) check in [PolicySet.checks]
 * that produced that kind determines [PolicyDecision.winningCheckId] and
 * [PolicyDecision.outcome]; later checks of the same kind still appear in
 * [PolicyDecision.evidence] but do not change the winner. When every
 * evaluated check produced `Allow`, the first `Allow` in evaluation order
 * wins, by the same rule.
 *
 * ## Time-bounded, fail-closed evaluation
 *
 * Before evaluating each check, the elapsed time since evaluation began
 * (measured with [monotonicClock]) is compared against
 * `budget.maxElapsedNanoseconds`. If the budget is already exhausted, this
 * evaluator stops without running the remaining checks and returns a
 * [PolicyDecision] whose [PolicyDecision.outcome] is a synthesized
 * [PolicyCheckOutcome.Deny] explaining budget exhaustion, whose
 * [PolicyDecision.winningCheckId] is `null`, and whose
 * [PolicyDecision.evidence] holds whatever prefix of checks ran before the
 * cutoff. This is a fail-closed default: exhausting the time budget can never
 * itself produce `Allow`.
 *
 * This bounds the *cumulative* time across an ordered set of otherwise
 * individually-fast checks. It cannot protect against one non-compliant
 * [PolicyCheck] implementation that blocks or sleeps inside a single
 * [PolicyCheck.evaluate] call — exactly the same limitation
 * [io.dataloom.api.retry.RetryPolicy] already accepts for its own
 * evaluation-restriction contract, enforced by documentation and review, not
 * by a runtime sandbox.
 *
 * ## Side-effect-free
 *
 * [evaluate] itself performs no I/O beyond reading [monotonicClock] and
 * calling [PolicyCheck.evaluate] on each check. It does not apply the
 * resulting decision, mutate [PolicyEvaluationInput], or persist anything.
 *
 * ## Injection
 *
 * Must be injected; must not be accessed through a global singleton.
 *
 * @param monotonicClock supplies elapsed-time readings for a call's
 *   [PolicyEvaluationBudget] enforcement.
 */
public class PolicyEvaluator(
    private val monotonicClock: DataLoomMonotonicClock,
) {

    /**
     * Evaluates every check in [policySet] against [input], in order, and
     * combines the results into one [PolicyDecision] using the precedence
     * described in this class's KDoc.
     *
     * For the same [policySet], [input], and [budget], repeated calls
     * produce the same [PolicyDecision] — except for the historical
     * [PolicyDecision.evidence] prefix length in the specific case where
     * [monotonicClock] itself returns different elapsed readings across
     * calls (for example, a real system clock rather than a deterministic
     * test clock) and [budget] is tight enough for that variance to matter.
     *
     * @param policySet the ordered, non-empty set of checks to evaluate.
     * @param input the immutable input every check is evaluated against.
     * @param budget the maximum elapsed evaluation time before failing
     *   closed. Required; see [PolicyEvaluationBudget] for why there is no
     *   default.
     * @return the combined [PolicyDecision].
     */
    public fun evaluate(
        policySet: PolicySet,
        input: PolicyEvaluationInput,
        budget: PolicyEvaluationBudget,
    ): PolicyDecision {
        val startReading = monotonicClock.mark()
        val evidence = mutableListOf<PolicyCheckEvidence>()

        for (check in policySet.checks) {
            val elapsedNanoseconds = monotonicClock.mark().elapsedNanosecondsSince(startReading)
            if (elapsedNanoseconds >= budget.maxElapsedNanoseconds) {
                return PolicyDecision(
                    policySetId = policySet.id,
                    outcome = PolicyCheckOutcome.Deny(
                        justification = "Policy evaluation budget exhausted after evaluating " +
                            "${evidence.size} of ${policySet.checks.size} checks in policy set " +
                            "'${policySet.id}'.",
                    ),
                    winningCheckId = null,
                    evidence = evidence.toList(),
                )
            }
            evidence += PolicyCheckEvidence(check.id, check.evaluate(input))
        }

        return combine(policySet.id, evidence, input.configurationSnapshot)
    }

    private fun combine(
        policySetId: PolicySetId,
        evidence: List<PolicyCheckEvidence>,
        configurationSnapshot: ConfigurationSnapshot,
    ): PolicyDecision {
        evidence.firstOrNull { it.outcome is PolicyCheckOutcome.Deny }?.let { winner ->
            return PolicyDecision(policySetId, winner.outcome, winner.checkId, evidence)
        }

        val firstRequireUserAction = evidence.firstOrNull { it.outcome is PolicyCheckOutcome.RequireUserAction }
        val firstDefer = evidence.firstOrNull { it.outcome is PolicyCheckOutcome.Defer }

        if (firstRequireUserAction != null || firstDefer != null) {
            val deferDominates = deferDominatesRequireUserAction(configurationSnapshot)
            val winner = when {
                deferDominates && firstDefer != null -> firstDefer
                firstRequireUserAction != null -> firstRequireUserAction
                else -> firstDefer
            }
            checkNotNull(winner) { "Unreachable: at least one of firstRequireUserAction/firstDefer is non-null." }
            return PolicyDecision(policySetId, winner.outcome, winner.checkId, evidence)
        }

        // policySet.checks (and therefore evidence, when the budget was not
        // exhausted) is guaranteed non-empty by PolicySet's own construction
        // validation, and every remaining outcome kind is Allow at this point.
        val winner = evidence.first()
        return PolicyDecision(policySetId, winner.outcome, winner.checkId, evidence)
    }

    private fun deferDominatesRequireUserAction(configurationSnapshot: ConfigurationSnapshot): Boolean {
        val value = configurationSnapshot[PolicyConfigurationKeys.DEFER_DOMINATES_REQUIRE_USER_ACTION]
        return value is ConfigurationValue.BooleanValue && value.value
    }
}
