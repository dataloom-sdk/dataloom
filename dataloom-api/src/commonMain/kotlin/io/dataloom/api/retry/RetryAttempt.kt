package io.dataloom.api.retry

/**
 * Immutable counter representing the number of processing attempts made for a
 * queue entry.
 *
 * [RetryAttempt] is supplied by the DataLoom runtime after evaluating retry
 * policy. It does not evaluate policy itself, select a retry strategy, or
 * schedule work.
 *
 * ## Constraints
 *
 * - [count] must be greater than zero.
 * - Zero and negative values are rejected at construction.
 * - Construction does not read the clock, sleep, or schedule work.
 *
 * ## Equality
 *
 * Equality compares [count] by value.
 *
 * @param count the number of processing attempts, starting at 1 for the first
 *   retry. Must be greater than zero.
 */
public class RetryAttempt(
    /** The number of processing attempts. Must be greater than zero. */
    public val count: Int,
) {
    init {
        require(count > 0) {
            "RetryAttempt count must be greater than zero, but was $count."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RetryAttempt) return false
        return count == other.count
    }

    override fun hashCode(): Int = count.hashCode()

    override fun toString(): String = "RetryAttempt(count=$count)"
}
