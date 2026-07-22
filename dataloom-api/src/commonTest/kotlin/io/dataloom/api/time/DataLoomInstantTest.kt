package io.dataloom.api.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DataLoomInstantTest {

    // -------------------------------------------------------------------------
    // Construction — valid values
    // -------------------------------------------------------------------------

    @Test
    fun `zero epoch milliseconds is accepted`() {
        val instant = DataLoomInstant(0L)
        assertEquals(0L, instant.epochMilliseconds)
    }

    @Test
    fun `positive epoch milliseconds is accepted`() {
        val instant = DataLoomInstant(1_000_000L)
        assertEquals(1_000_000L, instant.epochMilliseconds)
    }

    @Test
    fun `large positive epoch milliseconds is accepted`() {
        val instant = DataLoomInstant(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, instant.epochMilliseconds)
    }

    // -------------------------------------------------------------------------
    // Construction — invalid values
    // -------------------------------------------------------------------------

    @Test
    fun `negative epoch milliseconds is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomInstant(-1L)
        }
    }

    @Test
    fun `large negative epoch milliseconds is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomInstant(Long.MIN_VALUE)
        }
    }

    @Test
    fun `rejection message includes the invalid value`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            DataLoomInstant(-42L)
        }
        val message = exception.message ?: ""
        assertEquals(true, message.contains("-42"), "Message should reference the invalid value.")
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    fun `equal epoch milliseconds compare as equal`() {
        val a = DataLoomInstant(1_234_567_890L)
        val b = DataLoomInstant(1_234_567_890L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different epoch milliseconds compare as unequal`() {
        val a = DataLoomInstant(100L)
        val b = DataLoomInstant(200L)
        assertNotEquals(a, b)
    }

    @Test
    fun `same instance compares as equal to itself`() {
        val a = DataLoomInstant(500L)
        assertEquals(a, a)
    }

    @Test
    fun `DataLoomInstant is not equal to null`() {
        val a = DataLoomInstant(100L)
        val result = a.equals(null)
        assertEquals(false, result)
    }

    @Test
    fun `DataLoomInstant is not equal to a different type`() {
        val a = DataLoomInstant(100L)
        val result = a.equals("100")
        assertEquals(false, result)
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Test
    fun `toString includes epoch milliseconds`() {
        val instant = DataLoomInstant(1_000L)
        val str = instant.toString()
        assertEquals(true, str.contains("1000"), "toString should include the value.")
    }
}
