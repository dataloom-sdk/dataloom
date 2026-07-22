package io.dataloom.testing.time

import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * A mutable [DataLoomClock] implementation for deterministic, controlled
 * testing of components that depend on wall-clock time.
 *
 * ## Purpose
 *
 * [MutableDataLoomClock] is a test utility that allows test code to set and
 * advance the clock to specific instants. It enables deterministic tests that
 * verify time-dependent behavior such as lease expiration, retry delays, and
 * event ordering.
 *
 * ## Semantics
 *
 * - [now] returns the currently configured instant.
 * - [set] replaces the current instant with the supplied value.
 * - [advanceBy] advances the current instant by the supplied number of
 *   milliseconds. Zero is accepted. Negative values are rejected. Arithmetic
 *   overflow and resulting negative epoch milliseconds are rejected.
 *
 * ## No system-clock access
 *
 * [MutableDataLoomClock] does not access the system clock, sleep, or perform
 * any platform-specific operation.
 *
 * ## Thread safety
 *
 * [MutableDataLoomClock] is mutable. Test code that shares a single instance
 * across threads must coordinate concurrent access externally. No
 * production-grade thread safety is claimed or implemented.
 *
 * ## Usage
 *
 * ```kotlin
 * val clock = MutableDataLoomClock(
 *     initialInstant = DataLoomInstant(epochMilliseconds = 1_000L),
 * )
 *
 * check(clock.now() == DataLoomInstant(1_000L))
 *
 * clock.advanceBy(milliseconds = 500L)
 * check(clock.now() == DataLoomInstant(1_500L))
 *
 * clock.set(instant = DataLoomInstant(epochMilliseconds = 2_000L))
 * check(clock.now() == DataLoomInstant(2_000L))
 * ```
 *
 * Do not use [MutableDataLoomClock] in production code. Use an application- or
 * platform-supplied [DataLoomClock] implementation instead.
 *
 * @param initialInstant the starting instant returned by the first [now]
 *   invocation.
 */
public class MutableDataLoomClock(
    initialInstant: DataLoomInstant,
) : DataLoomClock {

    private var currentInstant: DataLoomInstant = initialInstant

    /**
     * Returns the currently configured instant.
     *
     * The returned value changes when [set] or [advanceBy] is called.
     * This method does not access the system clock, sleep, or mutate state
     * beyond reading the current value.
     *
     * @return the current [DataLoomInstant].
     */
    override fun now(): DataLoomInstant = currentInstant

    /**
     * Replaces the current instant with the supplied [instant].
     *
     * The new value is returned by subsequent calls to [now].
     *
     * @param instant the new current instant.
     */
    public fun set(instant: DataLoomInstant): Unit {
        currentInstant = instant
    }

    /**
     * Advances the current instant by the supplied number of [milliseconds].
     *
     * Zero advancement is accepted and leaves the instant unchanged.
     *
     * @param milliseconds the number of milliseconds to advance. Must be
     *   zero or positive.
     * @throws IllegalArgumentException if [milliseconds] is negative.
     * @throws IllegalStateException if the addition would overflow [Long],
     *   or if the result would be a negative epoch milliseconds value.
     */
    public fun advanceBy(milliseconds: Long): Unit {
        require(milliseconds >= 0L) {
            "advanceBy requires a zero-or-positive duration, but was $milliseconds."
        }
        val current = currentInstant.epochMilliseconds
        val advanced = current + milliseconds
        check(advanced >= current) {
            "advanceBy caused arithmetic overflow: $current + $milliseconds overflowed Long."
        }
        // DataLoomInstant constructor enforces non-negative epoch milliseconds.
        currentInstant = DataLoomInstant(epochMilliseconds = advanced)
    }
}
