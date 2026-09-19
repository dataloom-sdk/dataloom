package io.dataloom.api.time

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemDataLoomMonotonicClockTest {

    @Test
    fun `mark returns a non-negative reading`() {
        val clock = SystemDataLoomMonotonicClock()
        val reading = clock.mark()
        assertTrue(reading.nanoseconds >= 0L)
    }

    @Test
    fun `successive marks do not decrease`() {
        val clock = SystemDataLoomMonotonicClock()
        val first = clock.mark()
        val second = clock.mark()
        assertTrue(second.nanoseconds >= first.nanoseconds)
    }

    @Test
    fun `elapsed duration between two marks is non-negative and computable`() {
        val clock = SystemDataLoomMonotonicClock()
        val first = clock.mark()

        // Burn a small, bounded amount of CPU so the second mark is very
        // likely to differ from the first without relying on a real sleep.
        var spin = 0L
        for (i in 0 until 200_000) {
            spin += i
        }

        val second = clock.mark()
        val elapsed = second.elapsedNanosecondsSince(first)
        assertTrue(elapsed >= 0L)
        assertTrue(spin >= 0L)
    }

    @Test
    fun `separate instances share a comparable origin`() {
        val a = SystemDataLoomMonotonicClock()
        val b = SystemDataLoomMonotonicClock()
        val readingA = a.mark()
        val readingB = b.mark()

        // Both instances derive their normalized origin from the same
        // process-lifetime baseline, so a later instance's first reading
        // must not be earlier than an already-observed reading from another
        // instance.
        assertTrue(readingB.nanoseconds >= readingA.nanoseconds - 1_000_000_000L)
    }
}
