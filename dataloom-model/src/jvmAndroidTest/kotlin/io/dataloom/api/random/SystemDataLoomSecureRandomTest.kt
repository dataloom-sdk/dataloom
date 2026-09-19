package io.dataloom.api.random

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SystemDataLoomSecureRandomTest {

    @Test
    fun `nextBytes returns an array of the requested length`() {
        val random = SystemDataLoomSecureRandom()
        val bytes = random.nextBytes(16)
        assertEquals(16, bytes.size)
    }

    @Test
    fun `nextBytes returns a single byte when requested`() {
        val random = SystemDataLoomSecureRandom()
        val bytes = random.nextBytes(1)
        assertEquals(1, bytes.size)
    }

    @Test
    fun `nextBytes rejects zero`() {
        val random = SystemDataLoomSecureRandom()
        assertFailsWith<IllegalArgumentException> {
            random.nextBytes(0)
        }
    }

    @Test
    fun `nextBytes rejects a negative count`() {
        val random = SystemDataLoomSecureRandom()
        assertFailsWith<IllegalArgumentException> {
            random.nextBytes(-1)
        }
    }

    @Test
    fun `successive calls produce different output`() {
        val random = SystemDataLoomSecureRandom()
        val first = random.nextBytes(32)
        val second = random.nextBytes(32)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `output is not all zero bytes`() {
        val random = SystemDataLoomSecureRandom()
        val bytes = random.nextBytes(32)
        assertTrue(bytes.any { it != 0.toByte() })
    }

    @Test
    fun `separate instances produce different output`() {
        val a = SystemDataLoomSecureRandom()
        val b = SystemDataLoomSecureRandom()
        assertNotEquals(a.nextBytes(32).toList(), b.nextBytes(32).toList())
    }
}
