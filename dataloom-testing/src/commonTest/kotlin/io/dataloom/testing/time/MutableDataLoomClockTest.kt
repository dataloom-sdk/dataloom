package io.dataloom.testing.time

import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies the [MutableDataLoomClock] contract.
 *
 * No system-clock access, randomness, platform dependency, or arbitrary
 * delays are used.
 */
class MutableDataLoomClockTest {

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun `now returns the initial instant`() {
        val initial = DataLoomInstant(epochMilliseconds = 1_000L)
        val clock = MutableDataLoomClock(initialInstant = initial)

        assertEquals(initial, clock.now())
    }

    @Test
    fun `now returns zero epoch milliseconds when initialized with zero`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 0L))

        assertEquals(0L, clock.now().epochMilliseconds)
    }

    // -------------------------------------------------------------------------
    // set()
    // -------------------------------------------------------------------------

    @Test
    fun `set replaces the current instant`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 1_000L))

        clock.set(instant = DataLoomInstant(epochMilliseconds = 5_000L))

        assertEquals(5_000L, clock.now().epochMilliseconds)
    }

    @Test
    fun `set can move time backward`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 5_000L))

        clock.set(instant = DataLoomInstant(epochMilliseconds = 1_000L))

        assertEquals(1_000L, clock.now().epochMilliseconds)
    }

    @Test
    fun `set to zero is accepted`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 1_000L))

        clock.set(instant = DataLoomInstant(epochMilliseconds = 0L))

        assertEquals(0L, clock.now().epochMilliseconds)
    }

    // -------------------------------------------------------------------------
    // advanceBy() — valid inputs
    // -------------------------------------------------------------------------

    @Test
    fun `advanceBy zero leaves the instant unchanged`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 1_000L))

        clock.advanceBy(milliseconds = 0L)

        assertEquals(1_000L, clock.now().epochMilliseconds)
    }

    @Test
    fun `advanceBy positive milliseconds advances the instant`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 1_000L))

        clock.advanceBy(milliseconds = 500L)

        assertEquals(1_500L, clock.now().epochMilliseconds)
    }

    @Test
    fun `advanceBy can be called multiple times cumulatively`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 0L))

        clock.advanceBy(milliseconds = 100L)
        clock.advanceBy(milliseconds = 200L)
        clock.advanceBy(milliseconds = 300L)

        assertEquals(600L, clock.now().epochMilliseconds)
    }

    // -------------------------------------------------------------------------
    // advanceBy() — rejected inputs
    // -------------------------------------------------------------------------

    @Test
    fun `advanceBy negative milliseconds is rejected`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 1_000L))

        assertFailsWith<IllegalArgumentException> {
            clock.advanceBy(milliseconds = -1L)
        }
    }

    @Test
    fun `advanceBy large negative milliseconds is rejected`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 1_000L))

        assertFailsWith<IllegalArgumentException> {
            clock.advanceBy(milliseconds = Long.MIN_VALUE)
        }
    }

    @Test
    fun `rejection message for negative advancement references the invalid value`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 1_000L))

        val exception = assertFailsWith<IllegalArgumentException> {
            clock.advanceBy(milliseconds = -42L)
        }
        val message = exception.message ?: ""
        assertEquals(true, message.contains("-42"), "Message should reference the invalid value.")
    }

    // -------------------------------------------------------------------------
    // advanceBy() — overflow rejection
    // -------------------------------------------------------------------------

    @Test
    fun `advanceBy that would overflow Long is rejected`() {
        val clock = MutableDataLoomClock(
            initialInstant = DataLoomInstant(epochMilliseconds = Long.MAX_VALUE),
        )

        assertFailsWith<IllegalStateException> {
            clock.advanceBy(milliseconds = 1L)
        }
    }

    @Test
    fun `clock instant is preserved after a failed advancement`() {
        val initial = DataLoomInstant(epochMilliseconds = Long.MAX_VALUE)
        val clock = MutableDataLoomClock(initialInstant = initial)

        try {
            clock.advanceBy(milliseconds = 1L)
        } catch (_: IllegalStateException) {
            // Expected
        }

        assertEquals(initial, clock.now())
    }

    @Test
    fun `clock instant is preserved after a rejected negative advancement`() {
        val initial = DataLoomInstant(epochMilliseconds = 1_000L)
        val clock = MutableDataLoomClock(initialInstant = initial)

        try {
            clock.advanceBy(milliseconds = -100L)
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        assertEquals(initial, clock.now())
    }

    // -------------------------------------------------------------------------
    // Deterministic repeated behavior
    // -------------------------------------------------------------------------

    @Test
    fun `set followed by advanceBy produces consistent results`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 0L))

        clock.set(instant = DataLoomInstant(epochMilliseconds = 1_000L))
        clock.advanceBy(milliseconds = 250L)

        assertEquals(1_250L, clock.now().epochMilliseconds)
    }

    @Test
    fun `now is deterministic for repeated reads`() {
        val clock = MutableDataLoomClock(initialInstant = DataLoomInstant(epochMilliseconds = 7_000L))

        val first = clock.now()
        val second = clock.now()

        assertEquals(first, second)
    }
}
