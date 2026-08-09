package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DataLoomDigestTest {

    // -------------------------------------------------------------------------
    // Construction — valid length
    // -------------------------------------------------------------------------

    @Test
    fun `SHA_256 accepts exactly 32 bytes`() {
        val digest = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32))
        assertEquals(32, digest.size)
    }

    @Test
    fun `SHA_512 accepts exactly 64 bytes`() {
        val digest = DataLoomDigest(DigestAlgorithm.SHA_512, ByteArray(64))
        assertEquals(64, digest.size)
    }

    // -------------------------------------------------------------------------
    // Construction — invalid length
    // -------------------------------------------------------------------------

    @Test
    fun `SHA_256 rejects the wrong byte length`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(31))
        }
    }

    @Test
    fun `SHA_512 rejects the wrong byte length`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomDigest(DigestAlgorithm.SHA_512, ByteArray(32))
        }
    }

    @Test
    fun `empty bytes are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(0))
        }
    }

    // -------------------------------------------------------------------------
    // Defensive copying
    // -------------------------------------------------------------------------

    @Test
    fun `mutating the source array after construction does not affect the digest`() {
        val source = ByteArray(32) { 1 }
        val digest = DataLoomDigest(DigestAlgorithm.SHA_256, source)
        source[0] = 99
        assertEquals(1, digest.copyBytes()[0])
    }

    @Test
    fun `mutating a returned copy does not affect the digest`() {
        val digest = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { 2 })
        val copy = digest.copyBytes()
        copy[0] = 77
        assertEquals(2, digest.copyBytes()[0])
    }

    // -------------------------------------------------------------------------
    // toHex / toString
    // -------------------------------------------------------------------------

    @Test
    fun `toHex renders known bytes as lowercase hex`() {
        val bytes = byteArrayOf(0x00, 0x0F, 0xFF.toByte(), 0xA5.toByte()) + ByteArray(28)
        val digest = DataLoomDigest(DigestAlgorithm.SHA_256, bytes)
        assertTrue(digest.toHex().startsWith("000fffa5"))
    }

    @Test
    fun `toString includes the algorithm and hex encoding`() {
        val digest = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32))
        val str = digest.toString()
        assertTrue(str.startsWith("SHA_256:"))
        assertTrue(str.contains(digest.toHex()))
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    fun `same algorithm and bytes compare as equal`() {
        val a = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { it.toByte() })
        val b = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { it.toByte() })
        assertEquals(a, b)
        assertTrue(a contentEquals b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different bytes compare as unequal`() {
        val a = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { 0 })
        val b = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { 1 })
        assertNotEquals(a, b)
    }

    @Test
    fun `different algorithm with matching zero bytes compares as unequal`() {
        val a = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32))
        val b = DataLoomDigest(DigestAlgorithm.SHA_512, ByteArray(64))
        assertNotEquals(a, b)
    }

    @Test
    fun `same instance compares as equal to itself`() {
        val a = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32))
        assertEquals(a, a)
    }

    @Test
    fun `DataLoomDigest is not equal to null`() {
        val a = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32))
        assertEquals(false, a.equals(null))
    }

    @Test
    fun `DataLoomDigest is not equal to a different type`() {
        val a = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32))
        assertEquals(false, a.equals("not a digest"))
    }
}
