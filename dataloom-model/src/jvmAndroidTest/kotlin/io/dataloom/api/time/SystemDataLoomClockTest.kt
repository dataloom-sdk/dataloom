package io.dataloom.api.time

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemDataLoomClockTest {

    @Test
    fun `now returns a value bracketed by System currentTimeMillis calls`() {
        val clock = SystemDataLoomClock()

        val before = System.currentTimeMillis()
        val reading = clock.now()
        val after = System.currentTimeMillis()

        assertTrue(
            reading.epochMilliseconds in before..after,
            "Expected ${reading.epochMilliseconds} to be within [$before, $after].",
        )
    }

    @Test
    fun `now never throws construction validation because epochMilliseconds is non-negative`() {
        val clock = SystemDataLoomClock()
        val reading = clock.now()
        assertTrue(reading.epochMilliseconds >= 0L)
    }

    @Test
    fun `successive calls do not decrease`() {
        val clock = SystemDataLoomClock()
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
        val a = SystemDataLoomClock()
        val b = SystemDataLoomClock()
        val readingA = a.now()
        val readingB = b.now()
        assertTrue(kotlin.math.abs(readingB.epochMilliseconds - readingA.epochMilliseconds) < 1_000L)
    }
}
