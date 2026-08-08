package io.dataloom.api.time

import platform.Foundation.NSDate

/**
 * Production [DataLoomClock] backed by [NSDate.timeIntervalSince1970].
 *
 * This is the default wall-clock implementation for Apple Kotlin/Native
 * targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`). It has no mutable
 * state and may be shared across threads.
 *
 * As with `NSDate` itself, [now] is not guaranteed to be monotonic; it can
 * move backward if the host system clock is adjusted. Use
 * [AppleDataLoomMonotonicClock] to measure elapsed time instead.
 */
public class AppleDataLoomClock : DataLoomClock {

    override fun now(): DataLoomInstant {
        val secondsSinceEpoch = NSDate().timeIntervalSince1970
        // NSDate.timeIntervalSince1970 is a Double number of seconds and can
        // be negative for dates before 1970. DataLoomInstant rejects a
        // negative result rather than silently clamping it, matching the
        // fail-closed convention used throughout the shared model types.
        val epochMilliseconds = (secondsSinceEpoch * 1_000.0).toLong()
        return DataLoomInstant(epochMilliseconds = epochMilliseconds)
    }
}
