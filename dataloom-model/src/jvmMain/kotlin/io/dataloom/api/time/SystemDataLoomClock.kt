package io.dataloom.api.time

/**
 * Production [DataLoomClock] backed by [System.currentTimeMillis].
 *
 * This is the default wall-clock implementation for the JVM target, which
 * also serves native Android applications today because the current Android
 * adapter modules consume this module's JVM target directly. It has no
 * mutable state and may be shared across threads.
 *
 * As with [System.currentTimeMillis] itself, [now] is not guaranteed to be
 * monotonic; it can move backward if the host system clock is adjusted. Use
 * [SystemDataLoomMonotonicClock] to measure elapsed time instead.
 */
public class SystemDataLoomClock : DataLoomClock {

    override fun now(): DataLoomInstant {
        val epochMilliseconds = System.currentTimeMillis()
        // System.currentTimeMillis() is specified to return non-negative
        // values for any reachable host date, so this should never throw in
        // production. DataLoomInstant still validates defensively.
        return DataLoomInstant(epochMilliseconds = epochMilliseconds)
    }
}
