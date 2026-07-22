package io.dataloom.testing.time

import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies the [FixedDataLoomClock] contract.
 *
 * No system-clock access, randomness, or platform dependency is used.
 */
class FixedDataLoomClockTest {

    // -------------------------------------------------------------------------
    // now() semantics
    // -------------------------------------------------------------------------

    @Test
    fun `now returns the configured instant`() {
        val expected = DataLoomInstant(epochMilliseconds = 1_000L)
        val clock = FixedDataLoomClock(instant = expected)

        assertEquals(expected, clock.now())
    }

    @Test
    fun `now returns zero epoch milliseconds when configured`() {
        val clock = FixedDataLoomClock(instant = DataLoomInstant(epochMilliseconds = 0L))

        assertEquals(0L, clock.now().epochMilliseconds)
    }

    @Test
    fun `now returns large epoch milliseconds when configured`() {
        val clock = FixedDataLoomClock(instant = DataLoomInstant(epochMilliseconds = Long.MAX_VALUE))

        assertEquals(Long.MAX_VALUE, clock.now().epochMilliseconds)
    }

    @Test
    fun `repeated calls return the same instant`() {
        val clock = FixedDataLoomClock(instant = DataLoomInstant(epochMilliseconds = 500L))

        val first = clock.now()
        val second = clock.now()
        val third = clock.now()

        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `no automatic advancement occurs between calls`() {
        val clock = FixedDataLoomClock(instant = DataLoomInstant(epochMilliseconds = 100L))

        repeat(10) {
            assertEquals(100L, clock.now().epochMilliseconds)
        }
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    fun `two clocks with the same instant compare as equal`() {
        val a = FixedDataLoomClock(instant = DataLoomInstant(epochMilliseconds = 1_000L))
        val b = FixedDataLoomClock(instant = DataLoomInstant(epochMilliseconds = 1_000L))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two clocks with different instants compare as unequal`() {
        val a = FixedDataLoomClock(instant = DataLoomInstant(epochMilliseconds = 1_000L))
        val b = FixedDataLoomClock(instant = DataLoomInstant(epochMilliseconds = 2_000L))

        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // Configured instant property
    // -------------------------------------------------------------------------

    @Test
    fun `instant property matches the value supplied at construction`() {
        val expected = DataLoomInstant(epochMilliseconds = 42L)
        val clock = FixedDataLoomClock(instant = expected)

        assertEquals(expected, clock.instant)
    }
}
