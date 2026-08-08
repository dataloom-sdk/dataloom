package io.dataloom.api.time

import kotlin.test.Test
import kotlin.test.assertTrue

class AppleDataLoomMonotonicClockTest {

    @Test
    fun `mark returns a non-negative reading`() {
        val clock = AppleDataLoomMonotonicClock()
        val reading = clock.mark()
        assertTrue(reading.nanoseconds >= 0L)
    }

    @Test
    fun `successive marks do not decrease`() {
        val clock = AppleDataLoomMonotonicClock()
        val first = clock.mark()
        val second = clock.mark()
        assertTrue(second.nanoseconds >= first.nanoseconds)
    }

    @Test
    fun `elapsed duration between two marks is non-negative and computable`() {
        val clock = AppleDataLoomMonotonicClock()
        val first = clock.mark()

        var spin = 0L
        for (i in 0 until 200_000) {
            spin += i
        }

        val second = clock.mark()
        val elapsed = second.elapsedNanosecondsSince(first)
        assertTrue(elapsed >= 0L)
        assertTrue(spin >= 0L)
    }
}
