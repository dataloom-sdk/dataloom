package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DataLoomMacTest {

    // -------------------------------------------------------------------------
    // Construction — valid length
    // -------------------------------------------------------------------------

    @Test
    fun `HMAC_SHA_256 accepts exactly 32 bytes`() {
        val mac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32))
        assertEquals(32, mac.size)
    }

    @Test
    fun `HMAC_SHA_512 accepts exactly 64 bytes`() {
        val mac = DataLoomMac(HmacAlgorithm.HMAC_SHA_512, ByteArray(64))
        assertEquals(64, mac.size)
    }

    // -------------------------------------------------------------------------
    // Construction — invalid length
    // -------------------------------------------------------------------------

    @Test
    fun `HMAC_SHA_256 rejects the wrong byte length`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(31))
        }
    }

    @Test
    fun `HMAC_SHA_512 rejects the wrong byte length`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomMac(HmacAlgorithm.HMAC_SHA_512, ByteArray(32))
        }
    }

    // -------------------------------------------------------------------------
    // Defensive copying
    // -------------------------------------------------------------------------

    @Test
    fun `mutating the source array after construction does not affect the tag`() {
        val source = ByteArray(32) { 1 }
        val mac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, source)
        source[0] = 99
        assertEquals(1, mac.copyBytes()[0])
    }

    @Test
    fun `mutating a returned copy does not affect the tag`() {
        val mac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { 2 })
        val copy = mac.copyBytes()
        copy[0] = 77
        assertEquals(2, mac.copyBytes()[0])
    }

    // -------------------------------------------------------------------------
    // toHex / toString
    // -------------------------------------------------------------------------

    @Test
    fun `toHex renders known bytes as lowercase hex`() {
        val bytes = byteArrayOf(0x00, 0x0F, 0xFF.toByte(), 0xA5.toByte()) + ByteArray(28)
        val mac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, bytes)
        assertTrue(mac.toHex().startsWith("000fffa5"))
    }

    @Test
    fun `toString includes the algorithm and hex encoding`() {
        val mac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32))
        val str = mac.toString()
        assertTrue(str.startsWith("HMAC_SHA_256:"))
        assertTrue(str.contains(mac.toHex()))
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    fun `same algorithm and bytes compare as equal`() {
        val a = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { it.toByte() })
        val b = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { it.toByte() })
        assertEquals(a, b)
        assertTrue(a contentEquals b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different bytes compare as unequal`() {
        val a = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { 0 })
        val b = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { 1 })
        assertNotEquals(a, b)
    }

    @Test
    fun `different algorithm with matching zero bytes compares as unequal`() {
        val a = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32))
        val b = DataLoomMac(HmacAlgorithm.HMAC_SHA_512, ByteArray(64))
        assertNotEquals(a, b)
    }

    @Test
    fun `same instance compares as equal to itself`() {
        val a = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32))
        assertEquals(a, a)
    }

    @Test
    fun `DataLoomMac is not equal to null`() {
        val a = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32))
        assertEquals(false, a.equals(null))
    }

    @Test
    fun `DataLoomMac is not equal to a different type`() {
        val a = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32))
        assertEquals(false, a.equals("not a mac"))
    }
}
