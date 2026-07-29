package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Built-in deterministic delay strategy used by [StandardRetryPolicy].
 *
 * Every strategy is immutable, side-effect free, and evaluated only from the
 * retry-attempt number already supplied by the runtime. Evaluation never reads
 * a clock, sleeps, schedules work, accesses providers, or uses random state.
 * Jitter is intentionally a separate concern and is not silently applied.
 */
public sealed interface RetryBackoffStrategy {

    /** Requests an immediate retry with a zero-millisecond minimum delay. */
    public data object Immediate : RetryBackoffStrategy

    /** Requests the same [delay] for every allowed retry attempt. */
    public data class Fixed(
        public val delay: SchedulingDelay,
    ) : RetryBackoffStrategy

    /**
     * Increases delay by [increment] for each attempt after attempt one and
     * clamps the result to [maximumDelay].
     *
     * Attempt one uses [initialDelay]. Arithmetic is overflow-safe.
     */
    public data class Linear(
        public val initialDelay: SchedulingDelay,
        public val increment: SchedulingDelay,
        public val maximumDelay: SchedulingDelay,
    ) : RetryBackoffStrategy {
        init {
            require(maximumDelay.milliseconds >= initialDelay.milliseconds) {
                "Linear maximumDelay must be greater than or equal to initialDelay."
            }
        }
    }

    /**
     * Multiplies [initialDelay] by [multiplier] for each attempt after attempt
     * one and clamps the result to [maximumDelay].
     *
     * [multiplier] must be at least two. Arithmetic is overflow-safe.
     */
    public data class Exponential(
        public val initialDelay: SchedulingDelay,
        public val multiplier: Int,
        public val maximumDelay: SchedulingDelay,
    ) : RetryBackoffStrategy {
        init {
            require(multiplier >= 2) {
                "Exponential multiplier must be at least 2, but was $multiplier."
            }
            require(maximumDelay.milliseconds >= initialDelay.milliseconds) {
                "Exponential maximumDelay must be greater than or equal to initialDelay."
            }
        }
    }
}
