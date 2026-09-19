package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SystemDataLoomHmacCalculatorTest {

    // RFC 4231 Test Case 1: key = 20 bytes of 0x0b, data = "Hi There".
    // Cross-checked against javax.crypto.Mac directly via jshell during
    // development, independent of production code.
    private val rfc4231Key = ByteArray(20) { 0x0b }
    private val rfc4231Data = "Hi There".toByteArray()

    @Test
    fun `HMAC_SHA_256 of the RFC 4231 test case 1 vector matches`() {
        val calculator = SystemDataLoomHmacCalculator()
        val mac = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, rfc4231Key, rfc4231Data)
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", mac.toHex())
    }

    @Test
    fun `HMAC_SHA_512 of the RFC 4231 test case 1 vector matches`() {
        val calculator = SystemDataLoomHmacCalculator()
        val mac = calculator.hmac(HmacAlgorithm.HMAC_SHA_512, rfc4231Key, rfc4231Data)
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cdedaa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            mac.toHex(),
        )
    }

    // -------------------------------------------------------------------------
    // hmac() — behavior
    // -------------------------------------------------------------------------

    @Test
    fun `hmac rejects an empty key`() {
        val calculator = SystemDataLoomHmacCalculator()
        assertFailsWith<IllegalArgumentException> {
            calculator.hmac(HmacAlgorithm.HMAC_SHA_256, ByteArray(0), rfc4231Data)
        }
    }

    @Test
    fun `hmac is deterministic for the same key and input`() {
        val calculator = SystemDataLoomHmacCalculator()
        val first = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, rfc4231Key, rfc4231Data)
        val second = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, rfc4231Key, rfc4231Data)
        assertEquals(first, second)
    }

    @Test
    fun `different keys produce different tags for the same input`() {
        val calculator = SystemDataLoomHmacCalculator()
        val a = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, rfc4231Key, rfc4231Data)
        val b = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, ByteArray(20) { 0x0c }, rfc4231Data)
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // verify()
    // -------------------------------------------------------------------------

    @Test
    fun `verify rejects an empty key`() {
        val calculator = SystemDataLoomHmacCalculator()
        val mac = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, rfc4231Key, rfc4231Data)
        assertFailsWith<IllegalArgumentException> {
            calculator.verify(ByteArray(0), rfc4231Data, mac)
        }
    }

    @Test
    fun `verify accepts a tag computed with the same key and input`() {
        val calculator = SystemDataLoomHmacCalculator()
        val mac = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, rfc4231Key, rfc4231Data)
        assertTrue(calculator.verify(rfc4231Key, rfc4231Data, mac))
    }

    @Test
    fun `verify rejects a tag when the input was tampered with`() {
        val calculator = SystemDataLoomHmacCalculator()
        val mac = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, rfc4231Key, rfc4231Data)
        assertFalse(calculator.verify(rfc4231Key, "Tampered!".toByteArray(), mac))
    }

    @Test
    fun `verify rejects a tag when the key does not match`() {
        val calculator = SystemDataLoomHmacCalculator()
        val mac = calculator.hmac(HmacAlgorithm.HMAC_SHA_256, rfc4231Key, rfc4231Data)
        assertFalse(calculator.verify(ByteArray(20) { 0x0c }, rfc4231Data, mac))
    }

    @Test
    fun `verify honors the algorithm carried by the expected tag`() {
        val calculator = SystemDataLoomHmacCalculator()
        val mac = calculator.hmac(HmacAlgorithm.HMAC_SHA_512, rfc4231Key, rfc4231Data)
        assertTrue(calculator.verify(rfc4231Key, rfc4231Data, mac))
    }
}
