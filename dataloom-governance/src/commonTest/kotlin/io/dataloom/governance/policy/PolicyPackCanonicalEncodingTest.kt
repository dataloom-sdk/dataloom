package io.dataloom.governance.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.security.KeyReference
import io.dataloom.governance.CanonicalFormatException
import io.dataloom.governance.CanonicalWriter
import io.dataloom.governance.platformHmacCalculator
import io.dataloom.governance.testKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Known-answer tests. The expected canonical bytes are written out by hand
 * from the documented layout, and the expected MACs were computed with an
 * independent implementation (OpenSSL `dgst -sha256 -hmac`) over exactly
 * those bytes. They pin the wire encoding so the JVM and Apple
 * implementations cannot drift apart, matching
 * `AuditCanonicalEncodingTest`'s own approach.
 */
class PolicyPackCanonicalEncodingTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val domain = "0000001e" + "646174616c6f6f6d2e676f7665726e616e63652e706f6c6963797061636b"

    private val singleCheckManifest = PolicyPackManifest(
        policySetId = PolicySetId("ps1"),
        version = 1L,
        keyId = KeyReference("k1"),
        checkIds = listOf(PolicyCheckId("c1")),
    )

    @Test
    fun singleCheckNoMetadataCanonicalBytesMatchTheDocumentedLayout() {
        val expected = domain +
            "00000001" + // format version 1
            "00000002" + "6b31" + // keyId "k1"
            "00000003" + "707331" + // policySetId "ps1"
            "0000000000000001" + // version 1
            "00000001" + // one check id
            "00000002" + "6331" + // "c1"
            "00000000" // zero metadata entries
        val encoded = PolicyPackCanonicalEncoding.encode(singleCheckManifest)
        assertEquals(expected, hex(encoded))
    }

    @Test
    fun singleCheckNoMetadataMacMatchesTheIndependentlyComputedKnownAnswer() {
        val mac = platformHmacCalculator().hmac(
            HmacAlgorithm.HMAC_SHA_256,
            testKey(),
            PolicyPackCanonicalEncoding.encode(singleCheckManifest),
        )
        assertEquals("62498e71b3fd5496456349a285d544831d5298c60a30e69b0408a72e1fa3a0be", mac.toHex())
    }

    private val twoCheckManifest = PolicyPackManifest(
        policySetId = PolicySetId("ps1"),
        version = 2L,
        keyId = KeyReference("k1"),
        checkIds = listOf(PolicyCheckId("c1"), PolicyCheckId("yz")),
        metadata = DataLoomMetadata.of(linkedMapOf("b" to "😀", "a" to "x")),
    )

    @Test
    fun twoChecksWithMetadataCanonicalBytesMatchTheDocumentedLayoutIncludingSortingAndSupplementaryCharacters() {
        val expected = domain +
            "00000001" +
            "00000002" + "6b31" +
            "00000003" + "707331" +
            "0000000000000002" + // version 2
            "00000002" + // two check ids, in supplied order
            "00000002" + "6331" + // "c1"
            "00000002" + "797a" + // "yz"
            "00000002" + // two metadata entries, written in key order regardless of insertion order
            "00000001" + "61" + "00000001" + "78" + // "a" -> "x"
            "00000001" + "62" + "00000004" + "f09f9880" // "b" -> U+1F600 (4 UTF-8 bytes)
        val encoded = PolicyPackCanonicalEncoding.encode(twoCheckManifest)
        assertEquals(expected, hex(encoded))
    }

    @Test
    fun twoChecksWithMetadataMacMatchesTheIndependentlyComputedKnownAnswer() {
        val mac = platformHmacCalculator().hmac(
            HmacAlgorithm.HMAC_SHA_256,
            testKey(),
            PolicyPackCanonicalEncoding.encode(twoCheckManifest),
        )
        assertEquals("9d8ffcd20616b6e2e8cce1ba8d560948032dbc5255de9bd298c1b94a8c3b6cbd", mac.toHex())
    }

    @Test
    fun insertionOrderOfMetadataDoesNotChangeTheEncoding() {
        val ab = twoCheckManifest.let {
            PolicyPackManifest(it.policySetId, it.version, it.keyId, it.checkIds, DataLoomMetadata.of(linkedMapOf("a" to "x", "b" to "😀")))
        }
        val ba = twoCheckManifest
        assertContentEquals(
            PolicyPackCanonicalEncoding.encode(ab),
            PolicyPackCanonicalEncoding.encode(ba),
        )
    }

    @Test
    fun checkIdOrderDoesChangeTheEncoding() {
        val forward = PolicyPackManifest(PolicySetId("s"), 1L, KeyReference("k"), listOf(PolicyCheckId("a"), PolicyCheckId("b")))
        val backward = PolicyPackManifest(PolicySetId("s"), 1L, KeyReference("k"), listOf(PolicyCheckId("b"), PolicyCheckId("a")))
        assertNotEquals(
            hex(PolicyPackCanonicalEncoding.encode(forward)),
            hex(PolicyPackCanonicalEncoding.encode(backward)),
        )
    }

    @Test
    fun fieldBoundariesAreUnambiguous() {
        // Moving a character between adjacent fields must change the bytes (length prefixes
        // prevent "ab"+"c" == "a"+"bc").
        val a = PolicyPackManifest(PolicySetId("ab"), 1L, KeyReference("c"), listOf(PolicyCheckId("x")))
        val b = PolicyPackManifest(PolicySetId("a"), 1L, KeyReference("bc"), listOf(PolicyCheckId("x")))
        assertNotEquals(
            hex(PolicyPackCanonicalEncoding.encode(a)),
            hex(PolicyPackCanonicalEncoding.encode(b)),
        )
    }

    // -- decode / round trip -----------------------------------------------------

    @Test
    fun decodingTheEncodingOfAManifestReproducesAnEqualManifest() {
        for (manifest in listOf(singleCheckManifest, twoCheckManifest)) {
            val roundTripped = PolicyPackCanonicalEncoding.decode(PolicyPackCanonicalEncoding.encode(manifest))
            assertEquals(manifest, roundTripped)
        }
    }

    @Test
    fun decodingRejectsAWrongDomainTag() {
        val writer = CanonicalWriter()
        writer.string("not-a-policy-pack")
        writer.u32(PolicyPackCanonicalEncoding.SUPPORTED_FORMAT_VERSION)
        assertFailsWith<CanonicalFormatException> {
            PolicyPackCanonicalEncoding.decode(writer.toByteArray())
        }
    }

    @Test
    fun decodingRejectsCompletelyRandomGarbageBytes() {
        assertFailsWith<Exception> {
            PolicyPackCanonicalEncoding.decode(ByteArray(40) { 0x01 })
        }
    }

    @Test
    fun decodingRejectsAnUnsupportedFormatVersion() {
        val exception = assertFailsWith<UnsupportedPolicyPackVersionException> {
            PolicyPackCanonicalEncoding.decode(PolicyPackCanonicalEncoding.encode(singleCheckManifest, formatVersion = 0))
        }
        assertEquals(0, exception.formatVersion)
    }

    @Test
    fun decodingRejectsTruncatedInput() {
        val bytes = PolicyPackCanonicalEncoding.encode(singleCheckManifest)
        assertFailsWith<Exception> {
            PolicyPackCanonicalEncoding.decode(bytes.copyOfRange(0, bytes.size - 3))
        }
    }

    @Test
    fun decodingRejectsEmptyInput() {
        assertFailsWith<Exception> { PolicyPackCanonicalEncoding.decode(ByteArray(0)) }
    }

    @Test
    fun decodingRejectsTrailingBytes() {
        val bytes = PolicyPackCanonicalEncoding.encode(singleCheckManifest)
        assertFailsWith<Exception> { PolicyPackCanonicalEncoding.decode(bytes + byteArrayOf(0x00)) }
    }
}
