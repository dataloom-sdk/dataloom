package io.dataloom.api.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies the [DataLoomClock] interface contract using a private test
 * implementation.
 *
 * No platform-specific type, system clock, or asynchronous API is required.
 */
class DataLoomClockTest {

    // -------------------------------------------------------------------------
    // Private test implementation
    // -------------------------------------------------------------------------

    private class StubDataLoomClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    // -------------------------------------------------------------------------
    // Contract verification
    // -------------------------------------------------------------------------

    @Test
    fun `now returns the supplied DataLoomInstant`() {
        val expected = DataLoomInstant(epochMilliseconds = 1_000L)
        val clock = StubDataLoomClock(instant = expected)

        assertEquals(expected, clock.now())
    }

    @Test
    fun `now can return zero epoch milliseconds`() {
        val expected = DataLoomInstant(epochMilliseconds = 0L)
        val clock = StubDataLoomClock(instant = expected)

        assertEquals(0L, clock.now().epochMilliseconds)
    }

    @Test
    fun `now can return large epoch milliseconds`() {
        val expected = DataLoomInstant(epochMilliseconds = Long.MAX_VALUE)
        val clock = StubDataLoomClock(instant = expected)

        assertEquals(Long.MAX_VALUE, clock.now().epochMilliseconds)
    }

    @Test
    fun `different clock instances can return different instants`() {
        val clock1 = StubDataLoomClock(instant = DataLoomInstant(100L))
        val clock2 = StubDataLoomClock(instant = DataLoomInstant(200L))

        assertNotEquals(clock1.now(), clock2.now())
    }

    @Test
    fun `repeated calls return equal instants for a fixed implementation`() {
        val clock = StubDataLoomClock(instant = DataLoomInstant(500L))

        val first = clock.now()
        val second = clock.now()

        assertEquals(first, second)
    }

    @Test
    fun `no platform-specific type is required to implement DataLoomClock`() {
        // Structural test: the interface is satisfied by a pure Kotlin class
        // with no java.time, kotlinx-datetime, or Android dependency.
        val clock: DataLoomClock = StubDataLoomClock(
            instant = DataLoomInstant(epochMilliseconds = 42L),
        )
        assertEquals(42L, clock.now().epochMilliseconds)
    }
}
