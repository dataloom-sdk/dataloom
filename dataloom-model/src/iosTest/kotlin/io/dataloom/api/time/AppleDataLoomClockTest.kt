package io.dataloom.api.time

import platform.Foundation.NSDate
import kotlin.test.Test
import kotlin.test.assertTrue

class AppleDataLoomClockTest {

    @Test
    fun `now returns a value bracketed by NSDate reads`() {
        val clock = AppleDataLoomClock()

        val beforeMillis = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
        val reading = clock.now()
        val afterMillis = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

        assertTrue(
            reading.epochMilliseconds in beforeMillis..afterMillis,
            "Expected ${reading.epochMilliseconds} to be within [$beforeMillis, $afterMillis].",
        )
    }

    @Test
    fun `now never throws construction validation because epochMilliseconds is non-negative`() {
        val clock = AppleDataLoomClock()
        val reading = clock.now()
        assertTrue(reading.epochMilliseconds >= 0L)
    }

    @Test
    fun `successive calls do not decrease`() {
        val clock = AppleDataLoomClock()
        val first = clock.now()
        val second = clock.now()
        assertTrue(
            second.epochMilliseconds >= first.epochMilliseconds,
            "Wall clock is not guaranteed monotonic, but should not decrease across two immediate calls " +
                "in the absence of a system clock adjustment during the test.",
        )
    }
}
