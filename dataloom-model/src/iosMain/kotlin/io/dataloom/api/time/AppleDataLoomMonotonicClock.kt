@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.api.time

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * Production [DataLoomMonotonicClock] backed by
 * `clock_gettime(CLOCK_MONOTONIC, ...)`.
 *
 * This is the default monotonic-time implementation for Apple Kotlin/Native
 * targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`). It has no mutable
 * state and may be shared across threads. It uses the same POSIX interop
 * family (`kotlinx.cinterop.memScoped`/`alloc`/`ptr` over a C struct) already
 * used by this module's other Apple file-backed stores.
 *
 * `CLOCK_MONOTONIC` is specified by POSIX to be monotonically non-decreasing
 * and unrelated to wall-clock time, matching [DataLoomMonotonicReading]
 * semantics exactly. Unlike `NSProcessInfo.systemUptime`, it continues to
 * advance during system sleep on platforms where that distinction matters;
 * either behavior satisfies this contract because only differences between
 * readings from the same clock instance are meaningful.
 */
public class AppleDataLoomMonotonicClock : DataLoomMonotonicClock {

    override fun mark(): DataLoomMonotonicReading {
        val nanoseconds = memScoped {
            val ts = alloc<timespec>()
            clock_gettime(CLOCK_MONOTONIC.convert(), ts.ptr)
            ts.tv_sec.toLong() * 1_000_000_000L + ts.tv_nsec.toLong()
        }
        return DataLoomMonotonicReading(nanoseconds = nanoseconds)
    }
}
