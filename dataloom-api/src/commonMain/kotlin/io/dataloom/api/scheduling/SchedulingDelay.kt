package io.dataloom.api.scheduling

/**
 * Immutable relative scheduling delay expressed in milliseconds.
 *
 * [SchedulingDelay] represents how long a platform scheduler should wait
 * before executing a scheduled synchronization, measured from the time the
 * scheduler accepts the request. A value of zero means no minimum delay is
 * requested.
 *
 * ## Constraints
 *
 * - [milliseconds] must be zero or greater.
 * - Negative values are rejected at construction.
 * - Construction does not read the clock, calculate an absolute timestamp,
 *   sleep, or delay execution.
 * - [SchedulingDelay] represents a relative duration, not an absolute time.
 *
 * ## Platform semantics
 *
 * The platform scheduler interprets this delay as a minimum requested wait.
 * Actual execution may be deferred further by operating-system scheduling
 * policies, battery-optimization constraints, or platform limitations.
 *
 * ## Equality
 *
 * Equality compares [milliseconds] by value.
 *
 * @param milliseconds non-negative delay in milliseconds. Zero means no
 *   minimum delay is requested.
 */
public class SchedulingDelay(
    /** Non-negative delay in milliseconds. Zero means no minimum delay is requested. */
    public val milliseconds: Long,
) {
    init {
        require(milliseconds >= 0L) { "SchedulingDelay milliseconds must be zero or greater." }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SchedulingDelay) return false
        return milliseconds == other.milliseconds
    }

    override fun hashCode(): Int = milliseconds.hashCode()

    override fun toString(): String = "SchedulingDelay(milliseconds=$milliseconds)"

    public companion object {
        /** Convenient zero-delay value representing no minimum requested delay. */
        public val ZERO: SchedulingDelay = SchedulingDelay(0L)
    }
}
