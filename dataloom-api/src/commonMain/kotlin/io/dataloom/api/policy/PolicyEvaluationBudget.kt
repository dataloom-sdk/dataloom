package io.dataloom.api.policy

/**
 * Immutable, positive time budget bounding one [PolicyEvaluator.evaluate]
 * call, measured against an injected
 * [io.dataloom.api.time.DataLoomMonotonicClock].
 *
 * [PolicyEvaluationBudget] is this slice's concrete realization of
 * ADR-0002's "evaluation is time-bounded." It reuses
 * [io.dataloom.api.time.DataLoomMonotonicReading]'s elapsed-nanosecond shape
 * — the same primitive ADR-0002's `### Deterministic execution` section
 * names for "monotonic time for elapsed budgets" — rather than introducing a
 * new duration or timeout taxonomy.
 *
 * There is deliberately no default budget constant. A time bound relevant to
 * policy evaluation (which may gate retry, conflict, plugin, or
 * administrative-override decisions) is safety-relevant; this type does not
 * silently pick a number on the caller's behalf.
 *
 * ## Construction restrictions
 *
 * Construction does not read the clock. It only validates
 * [maxElapsedNanoseconds].
 *
 * @param maxElapsedNanoseconds the maximum elapsed evaluation duration, in
 *   nanoseconds, before [PolicyEvaluator.evaluate] must fail closed. Must be
 *   greater than zero.
 */
public class PolicyEvaluationBudget(
    public val maxElapsedNanoseconds: Long,
) {
    init {
        require(maxElapsedNanoseconds > 0L) {
            "PolicyEvaluationBudget maxElapsedNanoseconds must be greater than zero, " +
                "but was $maxElapsedNanoseconds."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PolicyEvaluationBudget) return false
        return maxElapsedNanoseconds == other.maxElapsedNanoseconds
    }

    override fun hashCode(): Int = maxElapsedNanoseconds.hashCode()

    override fun toString(): String = "PolicyEvaluationBudget(maxElapsedNanoseconds=$maxElapsedNanoseconds)"
}
