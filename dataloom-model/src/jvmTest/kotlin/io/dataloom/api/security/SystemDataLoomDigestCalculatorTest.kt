package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SystemDataLoomDigestCalculatorTest {

    // -------------------------------------------------------------------------
    // Known-answer vectors (computed against java.security.MessageDigest
    // directly, independent of production code, via jshell during
    // development — not transcribed from memory).
    // -------------------------------------------------------------------------

    @Test
    fun `SHA_256 of empty input matches the known digest`() {
        val calculator = SystemDataLoomDigestCalculator()
        val digest = calculator.digest(DigestAlgorithm.SHA_256, ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest.toHex(),
        )
    }

    @Test
    fun `SHA_256 of 'abc' matches the known digest`() {
        val calculator = SystemDataLoomDigestCalculator()
        val digest = calculator.digest(DigestAlgorithm.SHA_256, "abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest.toHex())
    }

    @Test
    fun `SHA_512 of empty input matches the known digest`() {
        val calculator = SystemDataLoomDigestCalculator()
        val digest = calculator.digest(DigestAlgorithm.SHA_512, ByteArray(0))
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            digest.toHex(),
        )
    }

    @Test
    fun `SHA_512 of 'abc' matches the known digest`() {
        val calculator = SystemDataLoomDigestCalculator()
        val digest = calculator.digest(DigestAlgorithm.SHA_512, "abc".toByteArray())
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            digest.toHex(),
        )
    }

    // -------------------------------------------------------------------------
    // Behavior
    // -------------------------------------------------------------------------

    @Test
    fun `digest carries the requested algorithm`() {
        val calculator = SystemDataLoomDigestCalculator()
        assertEquals(DigestAlgorithm.SHA_256, calculator.digest(DigestAlgorithm.SHA_256, "x".toByteArray()).algorithm)
        assertEquals(DigestAlgorithm.SHA_512, calculator.digest(DigestAlgorithm.SHA_512, "x".toByteArray()).algorithm)
    }

    @Test
    fun `digest is deterministic for the same input`() {
        val calculator = SystemDataLoomDigestCalculator()
        val input = "deterministic input".toByteArray()
        val first = calculator.digest(DigestAlgorithm.SHA_256, input)
        val second = calculator.digest(DigestAlgorithm.SHA_256, input)
        assertEquals(first, second)
    }

    @Test
    fun `different input produces a different digest`() {
        val calculator = SystemDataLoomDigestCalculator()
        val a = calculator.digest(DigestAlgorithm.SHA_256, "input a".toByteArray())
        val b = calculator.digest(DigestAlgorithm.SHA_256, "input b".toByteArray())
        assertNotEquals(a, b)
    }

    @Test
    fun `different algorithm on the same input produces a different digest`() {
        val calculator = SystemDataLoomDigestCalculator()
        val input = "same input".toByteArray()
        val sha256 = calculator.digest(DigestAlgorithm.SHA_256, input)
        val sha512 = calculator.digest(DigestAlgorithm.SHA_512, input)
        assertNotEquals(sha256.toHex(), sha512.toHex())
    }
}
