package io.dataloom.api.time

import kotlin.test.Test
import kotlin.test.assertTrue

class AppleDataLoomClockTest {

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

    @Test
    fun `separate instances observe the same host clock`() {
        val a = AppleDataLoomClock()
        val b = AppleDataLoomClock()
        val readingA = a.now()
        val readingB = b.now()
        val deltaMillis = readingB.epochMilliseconds - readingA.epochMilliseconds
        assertTrue(deltaMillis < 1_000L && deltaMillis > -1_000L)
    }
}
