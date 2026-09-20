package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.governance.platformHmacCalculator
import io.dataloom.governance.rbac.PrincipalId
import io.dataloom.governance.testKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Known-answer tests. The expected canonical bytes are written out by hand from
 * the documented layout, and the expected MACs were computed with an independent
 * implementation (OpenSSL `dgst -sha256 -mac HMAC`) over exactly those bytes.
 * They pin the wire encoding so the JVM and Apple implementations cannot drift
 * apart, and so a future encoding change is a deliberate, versioned decision.
 */
class AuditCanonicalEncodingTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val domain = "0000001c" + "646174616c6f6f6d" + "2e" + "676f7665726e616e6365" + "2e" + "6175646974" + "2e" + "7631"

    private val firstRecordCanonical = domain +
        "0000000000000000" + // sequence 0
        "00000000000003e8" + // recordedAt 1000
        "00" + // no previous MAC
        "000000027431" + // tenant "t1"
        "000000027031" + // principal "p1"
        "0000000165" + // event type "e"
        "00000001" + // one detail
        "000000016b" + "0000000176" // "k" -> "v"

    private val firstEvent = AuditEvent(
        TenantId("t1"),
        PrincipalId("p1"),
        AuditEventType("e"),
        DataLoomMetadata.of(mapOf("k" to "v")),
    )

    private val laterPreviousMac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { 0x11 })

    private val laterRecordCanonical = domain +
        "0000000000000001" + // sequence 1
        "00000000000007d0" + // recordedAt 2000
        "01" + // previous MAC present
        "00000020" + "11".repeat(32) + // previous MAC, 32 bytes
        "000000027431" +
        "000000027031" +
        "0000000165" +
        "00000002" + // two details, written in key order regardless of insertion order
        "0000000161" + "0000000178" + // "a" -> "x"
        "0000000162" + "00000004f09f9880" // "b" -> U+1F600 (supplementary plane, 4 UTF-8 bytes)

    private val laterEvent = AuditEvent(
        TenantId("t1"),
        PrincipalId("p1"),
        AuditEventType("e"),
        DataLoomMetadata.of(linkedMapOf("b" to "😀", "a" to "x")),
    )

    @Test
    fun firstRecordCanonicalBytesMatchTheDocumentedLayout() {
        val encoded = AuditCanonicalEncoding.encode(0L, DataLoomInstant(1_000L), null, firstEvent)
        assertEquals(firstRecordCanonical, hex(encoded))
    }

    @Test
    fun laterRecordCanonicalBytesMatchTheDocumentedLayoutIncludingSortingAndSupplementaryCharacters() {
        val encoded = AuditCanonicalEncoding.encode(1L, DataLoomInstant(2_000L), laterPreviousMac, laterEvent)
        assertEquals(laterRecordCanonical, hex(encoded))
    }

    @Test
    fun firstRecordMacMatchesTheIndependentlyComputedKnownAnswer() {
        val mac = platformHmacCalculator().hmac(
            HmacAlgorithm.HMAC_SHA_256,
            testKey(),
            AuditCanonicalEncoding.encode(0L, DataLoomInstant(1_000L), null, firstEvent),
        )
        assertEquals("76528b6d090e833f5f1ef99cb0b598b1647a850527ad9711ba8a9a0a2c381637", mac.toHex())
    }

    @Test
    fun laterRecordMacMatchesTheIndependentlyComputedKnownAnswer() {
        val mac = platformHmacCalculator().hmac(
            HmacAlgorithm.HMAC_SHA_256,
            testKey(),
            AuditCanonicalEncoding.encode(1L, DataLoomInstant(2_000L), laterPreviousMac, laterEvent),
        )
        assertEquals("b4dded19838bdcb6091268852f1a51a4c4b89dfbd02a8ab9cd7eceb9e69e549d", mac.toHex())
    }

    @Test
    fun insertionOrderOfDetailsDoesNotChangeTheEncoding() {
        val ab = laterEvent.copy(details = DataLoomMetadata.of(linkedMapOf("a" to "x", "b" to "😀")))
        val ba = laterEvent.copy(details = DataLoomMetadata.of(linkedMapOf("b" to "😀", "a" to "x")))
        assertContentEquals(
            AuditCanonicalEncoding.encode(1L, DataLoomInstant(2_000L), laterPreviousMac, ab),
            AuditCanonicalEncoding.encode(1L, DataLoomInstant(2_000L), laterPreviousMac, ba),
        )
    }

    @Test
    fun fieldBoundariesAreUnambiguous() {
        // Moving a character between adjacent fields must change the bytes (length prefixes prevent "ab"+"c" == "a"+"bc").
        val a = AuditEvent(TenantId("ab"), PrincipalId("c"), AuditEventType("e"))
        val b = AuditEvent(TenantId("a"), PrincipalId("bc"), AuditEventType("e"))
        assertNotEquals(
            hex(AuditCanonicalEncoding.encode(0L, DataLoomInstant(1L), null, a)),
            hex(AuditCanonicalEncoding.encode(0L, DataLoomInstant(1L), null, b)),
        )
        val d1 = AuditEvent(TenantId("t"), PrincipalId("p"), AuditEventType("e"), DataLoomMetadata.of(mapOf("k" to "vw")))
        val d2 = AuditEvent(TenantId("t"), PrincipalId("p"), AuditEventType("e"), DataLoomMetadata.of(mapOf("kv" to "w")))
        assertNotEquals(
            hex(AuditCanonicalEncoding.encode(0L, DataLoomInstant(1L), null, d1)),
            hex(AuditCanonicalEncoding.encode(0L, DataLoomInstant(1L), null, d2)),
        )
    }

    @Test
    fun presenceOfAPreviousMacIsDistinguishedFromItsAbsence() {
        val zeroMac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32))
        assertNotEquals(
            hex(AuditCanonicalEncoding.encode(1L, DataLoomInstant(1L), null, firstEvent)),
            hex(AuditCanonicalEncoding.encode(1L, DataLoomInstant(1L), zeroMac, firstEvent)),
        )
    }
}
