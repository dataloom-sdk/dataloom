package io.dataloom.api.time

import platform.Foundation.NSProcessInfo

/**
 * Production [DataLoomMonotonicClock] backed by
 * [NSProcessInfo.systemUptime].
 *
 * This is the default monotonic-time implementation for Apple Kotlin/Native
 * targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`). It has no mutable
 * state and may be shared across threads.
 *
 * `NSProcessInfo.systemUptime` reports the number of seconds the system has
 * been awake since it was last restarted, excluding sleep time, and is
 * monotonically non-decreasing while the process runs. Its origin is
 * arbitrary and not related to wall-clock time, matching
 * [DataLoomMonotonicReading] semantics exactly.
 */
public class AppleDataLoomMonotonicClock : DataLoomMonotonicClock {

    override fun mark(): DataLoomMonotonicReading {
        val uptimeSeconds = NSProcessInfo.processInfo.systemUptime
        val nanoseconds = (uptimeSeconds * 1_000_000_000.0).toLong()
        return DataLoomMonotonicReading(nanoseconds = nanoseconds)
    }
}
