package io.dataloom.testing.time

import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * A deterministic [DataLoomClock] implementation that always returns a single
 * configured [DataLoomInstant].
 *
 * ## Purpose
 *
 * [FixedDataLoomClock] is a test utility intended for use in deterministic,
 * platform-independent tests. It enables components that depend on
 * [DataLoomClock] to be tested without any system-clock access.
 *
 * ## Semantics
 *
 * Every call to [now] returns the same [instant] value supplied at
 * construction. The value never changes and is never automatically advanced.
 *
 * ## Thread safety
 *
 * [FixedDataLoomClock] is immutable and safe for concurrent use.
 *
 * ## Usage
 *
 * ```kotlin
 * val clock = FixedDataLoomClock(
 *     instant = DataLoomInstant(epochMilliseconds = 1_000L),
 * )
 *
 * check(clock.now() == DataLoomInstant(1_000L))
 * ```
 *
 * Do not use [FixedDataLoomClock] in production code. Use an application- or
 * platform-supplied [DataLoomClock] implementation instead.
 *
 * @param instant the fixed [DataLoomInstant] returned by every call to [now].
 */
public class FixedDataLoomClock(
    /** The fixed instant returned by every [now] invocation. */
    public val instant: DataLoomInstant,
) : DataLoomClock {

    /**
     * Returns the fixed [instant] configured at construction.
     *
     * This method does not access the system clock, sleep, advance time, or
     * mutate any state. Every invocation returns the same value.
     *
     * @return the configured [DataLoomInstant].
     */
    override fun now(): DataLoomInstant = instant

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FixedDataLoomClock) return false
        return instant == other.instant
    }

    override fun hashCode(): Int = instant.hashCode()

    override fun toString(): String = "FixedDataLoomClock(instant=$instant)"
}
