package io.dataloom.api.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PayloadContractsTest {

    // -------------------------------------------------------------------------
    // PayloadContentType
    // -------------------------------------------------------------------------

    @Test
    fun `payload content type accepts valid value`() {
        val contentType: PayloadContentType = PayloadContentType("application/json")
        assertEquals("application/json", contentType.value)
    }

    @Test
    fun `payload content type preserves exact input`() {
        val raw = "application/vnd.example.entity"
        val contentType: PayloadContentType = PayloadContentType(raw)
        assertEquals(raw, contentType.value)
    }

    @Test
    fun `payload content type rejects blank input`() {
        assertFailsWith<IllegalArgumentException> { PayloadContentType("") }
    }

    @Test
    fun `payload content type rejects whitespace-only input`() {
        assertFailsWith<IllegalArgumentException> { PayloadContentType("   ") }
    }

    @Test
    fun `equal payload content types compare as equal`() {
        val a: PayloadContentType = PayloadContentType("application/json")
        val b: PayloadContentType = PayloadContentType("application/json")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different payload content types compare as unequal`() {
        val a: PayloadContentType = PayloadContentType("application/json")
        val b: PayloadContentType = PayloadContentType("application/octet-stream")
        assertNotEquals(a, b)
    }

    @Test
    fun `payload content type toString returns wrapped value`() {
        val contentType: PayloadContentType = PayloadContentType("application/pdf")
        assertEquals("application/pdf", contentType.toString())
    }

    // -------------------------------------------------------------------------
    // EntityVersion
    // -------------------------------------------------------------------------

    @Test
    fun `entity version accepts valid value`() {
        val version: EntityVersion = EntityVersion("v1")
        assertEquals("v1", version.value)
    }

    @Test
    fun `entity version preserves exact input`() {
        val raw = "etag-abc123"
        val version: EntityVersion = EntityVersion(raw)
        assertEquals(raw, version.value)
    }

    @Test
    fun `entity version rejects blank input`() {
        assertFailsWith<IllegalArgumentException> { EntityVersion("") }
    }

    @Test
    fun `entity version rejects whitespace-only input`() {
        assertFailsWith<IllegalArgumentException> { EntityVersion("  ") }
    }

    @Test
    fun `equal entity versions compare as equal`() {
        val a: EntityVersion = EntityVersion("rev-42")
        val b: EntityVersion = EntityVersion("rev-42")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different entity versions compare as unequal`() {
        val a: EntityVersion = EntityVersion("rev-42")
        val b: EntityVersion = EntityVersion("rev-43")
        assertNotEquals(a, b)
    }

    @Test
    fun `entity version toString returns wrapped value`() {
        val version: EntityVersion = EntityVersion("seq-007")
        assertEquals("seq-007", version.toString())
    }

    // -------------------------------------------------------------------------
    // DataLoomPayload
    // -------------------------------------------------------------------------

    private val jsonType: PayloadContentType = PayloadContentType("application/json")
    private val binaryType: PayloadContentType = PayloadContentType("application/octet-stream")

    @Test
    fun `payload preserves content type`() {
        val payload: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))
        assertEquals(jsonType, payload.contentType)
    }

    @Test
    fun `empty payload is supported`() {
        val payload: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf())
        assertEquals(0, payload.size)
        assertTrue(payload.isEmpty)
    }

    @Test
    fun `non-empty payload has correct size`() {
        val payload: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(10, 20, 30))
        assertEquals(3, payload.size)
        assertFalse(payload.isEmpty)
    }

    @Test
    fun `mutating constructor input does not mutate payload`() {
        val source: ByteArray = byteArrayOf(1, 2, 3)
        val payload: DataLoomPayload = DataLoomPayload(jsonType, source)
        source[0] = 99.toByte()
        assertEquals(1, payload.copyBytes()[0])
    }

    @Test
    fun `mutating copyBytes result does not mutate payload`() {
        val payload: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))
        val copy: ByteArray = payload.copyBytes()
        copy[0] = 99.toByte()
        assertEquals(1, payload.copyBytes()[0])
    }

    @Test
    fun `equal byte content and content type compare as equal`() {
        val a: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))
        val b: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))
        assertEquals(a, b)
    }

    @Test
    fun `different byte content compares as unequal`() {
        val a: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))
        val b: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(4, 5, 6))
        assertNotEquals(a, b)
    }

    @Test
    fun `different content type compares as unequal`() {
        val a: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))
        val b: DataLoomPayload = DataLoomPayload(binaryType, byteArrayOf(1, 2, 3))
        assertNotEquals(a, b)
    }

    @Test
    fun `equal payloads have equal hash codes`() {
        val a: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))
        val b: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString does not contain raw payload bytes`() {
        val bytes: ByteArray = byteArrayOf(0x7b, 0x22, 0x6b, 0x22, 0x7d) // {"k"}
        val payload: DataLoomPayload = DataLoomPayload(jsonType, bytes)
        val str: String = payload.toString()
        assertFalse(str.contains("123"), "toString must not contain decimal byte values")
        assertFalse(str.contains("7b"), "toString must not contain hex byte values")
        assertTrue(str.contains("application/json"), "toString should include content type")
        assertTrue(str.contains("5"), "toString should include size")
    }
}
