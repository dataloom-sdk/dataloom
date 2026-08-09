package io.dataloom.api.policy

import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId

/**
 * A deterministically ordered, bounded collection of [PolicyCheck] instances,
 * evaluated together as one unit by [PolicyEvaluator].
 *
 * [PolicySet] is this foundation's answer to ADR-0002's "deterministic
 * ordered policy sets." Order is exactly [checks]' list order — supplied by
 * the caller assembling the set, not an implicit priority derived from
 * registration time or a mutable registry. There is no dynamic add/remove
 * once constructed; a mutable, dynamically-registered policy registry is a
 * separate, larger future concern, not this stable-extension-contract slice.
 *
 * ## Non-emptiness
 *
 * A [PolicySet] with zero checks is rejected at construction rather than
 * given implicit "always allow" or "always deny" semantics. What an empty set
 * should mean is a policy choice in its own right — one this
 * subsystem-agnostic foundation should not make on every future consumer's
 * behalf. Callers with genuinely no checks yet should not construct a
 * [PolicySet] at all.
 *
 * ## Bounds
 *
 * Must contain at least one check and at most 64. Every [PolicyCheck.id] in
 * the set must be unique. The upper bound is a construction-time safety rail
 * independent of [PolicyEvaluationBudget]'s runtime elapsed-time enforcement:
 * it catches an obviously oversized set immediately, before any evaluation is
 * attempted.
 *
 * @param id stable identifier for this set, used for diagnostics and carried
 *   onto every [PolicyDecision] produced from it.
 * @param checks the ordered checks composing this set. Defensively copied.
 * @throws IllegalArgumentException if [checks] is empty, exceeds 64 entries,
 *   or contains a duplicate [PolicyCheck.id].
 */
public class PolicySet(
    public val id: PolicySetId,
    checks: List<PolicyCheck>,
) {
    /** Defensive, order-preserving copy of the supplied checks. */
    public val checks: List<PolicyCheck> = checks.toList()

    init {
        require(this.checks.isNotEmpty()) {
            "PolicySet '$id' must contain at least one PolicyCheck."
        }
        require(this.checks.size <= MAX_CHECKS) {
            "PolicySet '$id' must contain at most $MAX_CHECKS checks, but had ${this.checks.size}."
        }
        val duplicateIds: Set<PolicyCheckId> = this.checks
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "PolicySet '$id' checks must have unique ids; duplicates: $duplicateIds."
        }
    }

    /** Never renders individual checks — only the set id and check count. */
    override fun toString(): String = "PolicySet(id=$id, checkCount=${checks.size})"

    private companion object {
        const val MAX_CHECKS: Int = 64
    }
}
