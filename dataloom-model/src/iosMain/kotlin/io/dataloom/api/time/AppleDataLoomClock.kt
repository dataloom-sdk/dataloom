@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.api.time

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * Production [DataLoomClock] backed by `clock_gettime(CLOCK_REALTIME, ...)`.
 *
 * This is the default wall-clock implementation for Apple Kotlin/Native
 * targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`). It has no mutable
 * state and may be shared across threads. It uses the same POSIX interop
 * family (`kotlinx.cinterop.memScoped`/`alloc`/`ptr` over a C struct) already
 * used by this module's other Apple file-backed stores, rather than the
 * Foundation `NSDate` API.
 *
 * As with `CLOCK_REALTIME` itself, [now] is not guaranteed to be monotonic;
 * it can move backward if the host system clock is adjusted. Use
 * [AppleDataLoomMonotonicClock] to measure elapsed time instead.
 */
public class AppleDataLoomClock : DataLoomClock {

    override fun now(): DataLoomInstant {
        val epochMilliseconds = memScoped {
            val ts = alloc<timespec>()
            clock_gettime(CLOCK_REALTIME.convert(), ts.ptr)
            // timespec.tv_sec is whole seconds since the Unix epoch;
            // tv_nsec is the sub-second remainder in nanoseconds.
            // DataLoomInstant rejects a negative result rather than silently
            // clamping it, matching the fail-closed convention used
            // throughout the shared model types.
            ts.tv_sec.toLong() * 1_000L + ts.tv_nsec.toLong() / 1_000_000L
        }
        return DataLoomInstant(epochMilliseconds = epochMilliseconds)
    }
}
