package io.dataloom.api.asset

import io.dataloom.api.identifier.AssetId
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.security.KeyReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies the [AssetManifest] contract: construction, cross-field
 * validation, chunk-layout geometry, and equality/immutability — including
 * the nested [AssetChunkLayout], [AssetChunkDescriptor], and
 * [AssetEncryptionMetadata] types.
 */
class AssetManifestTest {

    @Test
    fun constructsWithASingleChunkLayout() {
        val manifest = manifestOf(sizeBytes = 10L, chunkLengths = listOf(10L))
        assertEquals(1, manifest.chunkLayout.chunkCount)
        assertEquals(10L, manifest.chunkLayout.totalSizeBytes)
    }

    @Test
    fun constructsWithAMultiChunkLayoutOfVaryingLengths() {
        val manifest = manifestOf(sizeBytes = 25L, chunkLengths = listOf(10L, 10L, 5L))
        assertEquals(3, manifest.chunkLayout.chunkCount)
        assertEquals(25L, manifest.chunkLayout.totalSizeBytes)
    }

    @Test
    fun rejectsNonPositiveVersion() {
        assertFailsWith<IllegalArgumentException> { manifestOf(version = 0L) }
        assertFailsWith<IllegalArgumentException> { manifestOf(version = -1L) }
    }

    @Test
    fun rejectsNegativeSizeBytes() {
        assertFailsWith<IllegalArgumentException> {
            manifestOf(sizeBytes = -1L, chunkLengths = listOf(10L))
        }
    }

    @Test
    fun rejectsSizeBytesInconsistentWithChunkLayoutTotal() {
        val exception = assertFailsWith<IllegalArgumentException> {
            manifestOf(sizeBytes = 99L, chunkLengths = listOf(10L, 10L))
        }
        assertTrue(exception.message!!.contains("sizeBytes"))
    }

    @Test
    fun compressionAndEncryptionDefaultToNull() {
        val manifest = manifestOf()
        assertEquals(null, manifest.compression)
        assertEquals(null, manifest.encryption)
    }

    @Test
    fun equalsAndHashCodeAreStructural() {
        val first = manifestOf()
        val second = manifestOf()
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun equalsDistinguishesDifferentVersions() {
        val first = manifestOf(version = 1L)
        val second = manifestOf(version = 2L)
        assertNotEquals(first, second)
    }

    // --- AssetMediaType ---

    @Test
    fun mediaTypeAcceptsTypicalMimeTypes() {
        assertEquals("image/png", AssetMediaType("image/png").value)
        assertEquals("application/vnd.api+json", AssetMediaType("application/vnd.api+json").value)
    }

    @Test
    fun mediaTypeRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { AssetMediaType("") }
    }

    @Test
    fun mediaTypeRejectsDisallowedCharacters() {
        assertFailsWith<IllegalArgumentException> { AssetMediaType("image/png; charset=utf-8") }
    }

    // --- AssetChunkDescriptor / AssetChunkLayout ---

    @Test
    fun chunkLayoutRejectsEmptyChunkList() {
        assertFailsWith<IllegalArgumentException> { AssetChunkLayout(emptyList()) }
    }

    @Test
    fun chunkLayoutRejectsOutOfOrderIndices() {
        val chunks = listOf(
            chunkOf(index = 1, offsetBytes = 0L, lengthBytes = 5L),
            chunkOf(index = 0, offsetBytes = 5L, lengthBytes = 5L),
        )
        assertFailsWith<IllegalArgumentException> { AssetChunkLayout(chunks) }
    }

    @Test
    fun chunkLayoutRejectsGapsBetweenChunks() {
        val chunks = listOf(
            chunkOf(index = 0, offsetBytes = 0L, lengthBytes = 5L),
            // Gap: next chunk should start at offset 5, not 6.
            chunkOf(index = 1, offsetBytes = 6L, lengthBytes = 5L),
        )
        assertFailsWith<IllegalArgumentException> { AssetChunkLayout(chunks) }
    }

    @Test
    fun chunkLayoutRejectsOverlappingChunks() {
        val chunks = listOf(
            chunkOf(index = 0, offsetBytes = 0L, lengthBytes = 5L),
            // Overlap: next chunk should start at offset 5, not 4.
            chunkOf(index = 1, offsetBytes = 4L, lengthBytes = 5L),
        )
        assertFailsWith<IllegalArgumentException> { AssetChunkLayout(chunks) }
    }

    @Test
    fun chunkDescriptorRejectsNegativeOffset() {
        assertFails { chunkOf(offsetBytes = -1L) }
    }

    @Test
    fun chunkDescriptorRejectsNonPositiveLength() {
        assertFails { chunkOf(lengthBytes = 0L) }
    }

    // --- AssetCompressionMetadata ---

    @Test
    fun compressionMetadataRejectsNegativeUncompressedSize() {
        assertFailsWith<IllegalArgumentException> {
            AssetCompressionMetadata(AssetCompressionAlgorithm("gzip"), uncompressedSizeBytes = -1L)
        }
    }

    @Test
    fun manifestCarriesCompressionMetadataWhenPresent() {
        val manifest = manifestOf(
            compression = AssetCompressionMetadata(AssetCompressionAlgorithm("gzip"), uncompressedSizeBytes = 40L),
        )
        val compression = manifest.compression
        assertEquals("gzip", compression?.algorithm?.value)
        assertEquals(40L, compression?.uncompressedSizeBytes)
    }

    // --- AssetEncryptionMetadata ---

    @Test
    fun encryptionMetadataRejectsEmptyNonce() {
        assertFailsWith<IllegalArgumentException> {
            AssetEncryptionMetadata(
                AssetEncryptionAlgorithm("AES-256-GCM"),
                KeyReference("app-managed-key-001"),
                ByteArray(0),
            )
        }
    }

    @Test
    fun encryptionMetadataDefensivelyCopiesNonceOnConstruction() {
        val nonce = byteArrayOf(1, 2, 3)
        val metadata = AssetEncryptionMetadata(AssetEncryptionAlgorithm("AES-256-GCM"), KeyReference("key-1"), nonce)
        nonce[0] = 99
        assertEquals(1, metadata.copyNonceBytes()[0])
    }

    @Test
    fun encryptionMetadataCopyNonceBytesReturnsADefensiveCopy() {
        val metadata = AssetEncryptionMetadata(
            AssetEncryptionAlgorithm("AES-256-GCM"),
            KeyReference("key-1"),
            byteArrayOf(1, 2, 3),
        )
        val copy = metadata.copyNonceBytes()
        copy[0] = 99
        assertEquals(1, metadata.copyNonceBytes()[0])
    }

    @Test
    fun encryptionMetadataEqualityIsContentBased() {
        val first = AssetEncryptionMetadata(AssetEncryptionAlgorithm("AES-256-GCM"), KeyReference("key-1"), byteArrayOf(1, 2, 3))
        val second = AssetEncryptionMetadata(AssetEncryptionAlgorithm("AES-256-GCM"), KeyReference("key-1"), byteArrayOf(1, 2, 3))
        val different = AssetEncryptionMetadata(AssetEncryptionAlgorithm("AES-256-GCM"), KeyReference("key-1"), byteArrayOf(9, 9, 9))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, different)
    }

    @Test
    fun encryptionMetadataToStringNeverRendersNonceOrKeyBytes() {
        val metadata = AssetEncryptionMetadata(
            AssetEncryptionAlgorithm("AES-256-GCM"),
            KeyReference("do-not-leak-key-alias"),
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
        )
        val rendered = metadata.toString()
        assertFalse(rendered.contains("do-not-leak-key-alias"))
        assertTrue(rendered.contains("AES-256-GCM"))
        assertTrue(rendered.contains("9"))
    }

    @Test
    fun manifestCarriesEncryptionMetadataWhenPresent() {
        val manifest = manifestOf(
            encryption = AssetEncryptionMetadata(
                AssetEncryptionAlgorithm("AES-256-GCM"),
                KeyReference("key-1"),
                byteArrayOf(1, 2, 3),
            ),
        )
        val encryption = manifest.encryption
        assertEquals("AES-256-GCM", encryption?.algorithm?.value)
        assertEquals("key-1", encryption?.keyReference?.value)
    }

    private fun chunkOf(
        index: Int = 0,
        offsetBytes: Long = 0L,
        lengthBytes: Long = 5L,
    ): AssetChunkDescriptor =
        AssetChunkDescriptor(index, offsetBytes, lengthBytes, digestOf(index))

    private fun digestOf(seed: Int): DataLoomDigest =
        DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { (it + seed).toByte() })

    private fun manifestOf(
        version: Long = 1L,
        sizeBytes: Long = 10L,
        chunkLengths: List<Long> = listOf(10L),
        compression: AssetCompressionMetadata? = null,
        encryption: AssetEncryptionMetadata? = null,
    ): AssetManifest {
        var offset = 0L
        val chunks = chunkLengths.mapIndexed { index, length ->
            val descriptor = AssetChunkDescriptor(index, offset, length, digestOf(index))
            offset += length
            descriptor
        }
        return AssetManifest(
            assetId = AssetId("asset-001"),
            version = version,
            sizeBytes = sizeBytes,
            mediaType = AssetMediaType("application/octet-stream"),
            checksum = digestOf(999),
            chunkLayout = AssetChunkLayout(chunks),
            compression = compression,
            encryption = encryption,
        )
    }
}
