package io.dataloom.api.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DataLoomMonotonicReadingTest {

    // -------------------------------------------------------------------------
    // Construction — valid values
    // -------------------------------------------------------------------------

    @Test
    fun `zero nanoseconds is accepted`() {
        val reading = DataLoomMonotonicReading(0L)
        assertEquals(0L, reading.nanoseconds)
    }

    @Test
    fun `positive nanoseconds is accepted`() {
        val reading = DataLoomMonotonicReading(1_000_000L)
        assertEquals(1_000_000L, reading.nanoseconds)
    }

    @Test
    fun `large positive nanoseconds is accepted`() {
        val reading = DataLoomMonotonicReading(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, reading.nanoseconds)
    }

    // -------------------------------------------------------------------------
    // Construction — invalid values
    // -------------------------------------------------------------------------

    @Test
    fun `negative nanoseconds is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomMonotonicReading(-1L)
        }
    }

    @Test
    fun `large negative nanoseconds is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomMonotonicReading(Long.MIN_VALUE)
        }
    }

    @Test
    fun `rejection message includes the invalid value`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DataLoomMonotonicReading(-42L)
        }
        val message = exception.message ?: ""
        assertEquals(true, message.contains("-42"), "Message should reference the invalid value.")
    }

    // -------------------------------------------------------------------------
    // elapsedNanosecondsSince
    // -------------------------------------------------------------------------

    @Test
    fun `elapsed duration between two readings is the exact difference`() {
        val earlier = DataLoomMonotonicReading(1_000L)
        val later = DataLoomMonotonicReading(1_500L)
        assertEquals(500L, later.elapsedNanosecondsSince(earlier))
    }

    @Test
    fun `elapsed duration against an identical reading is zero`() {
        val reading = DataLoomMonotonicReading(1_000L)
        assertEquals(0L, reading.elapsedNanosecondsSince(reading))
    }

    @Test
    fun `elapsed duration fails closed when earlier reading is actually later`() {
        val earlier = DataLoomMonotonicReading(1_500L)
        val later = DataLoomMonotonicReading(1_000L)
        assertFailsWith<IllegalArgumentException> {
            later.elapsedNanosecondsSince(earlier)
        }
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    fun `equal nanoseconds compare as equal`() {
        val a = DataLoomMonotonicReading(1_234_567_890L)
        val b = DataLoomMonotonicReading(1_234_567_890L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different nanoseconds compare as unequal`() {
        val a = DataLoomMonotonicReading(100L)
        val b = DataLoomMonotonicReading(200L)
        assertNotEquals(a, b)
    }

    @Test
    fun `same instance compares as equal to itself`() {
        val a = DataLoomMonotonicReading(500L)
        assertEquals(a, a)
    }

    @Test
    fun `DataLoomMonotonicReading is not equal to null`() {
        val a = DataLoomMonotonicReading(100L)
        val result = a.equals(null)
        assertEquals(false, result)
    }

    @Test
    fun `DataLoomMonotonicReading is not equal to a different type`() {
        val a = DataLoomMonotonicReading(100L)
        val result = a.equals("100")
        assertEquals(false, result)
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Test
    fun `toString includes nanoseconds`() {
        val reading = DataLoomMonotonicReading(1_000L)
        val str = reading.toString()
        assertEquals(true, str.contains("1000"), "toString should include the value.")
    }
}
