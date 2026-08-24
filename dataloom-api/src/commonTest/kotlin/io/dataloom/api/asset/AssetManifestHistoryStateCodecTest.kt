package io.dataloom.api.asset

import io.dataloom.api.identifier.AssetId
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.security.KeyReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetManifestHistoryStateCodecTest {

    private val codec = AssetManifestHistoryStateCodec()

    @Test
    fun roundTripsAnEmptyHistory() {
        val state = AssetManifestHistoryState(emptyList())
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsASingleChunkManifestWithNoCompressionOrEncryption() {
        val manifest = manifestOf(version = 1L, chunkLengths = listOf(10L))
        val state = AssetManifestHistoryState(listOf(manifest))

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsAMultiChunkManifest() {
        val manifest = manifestOf(version = 1L, chunkLengths = listOf(4L, 6L, 3L))
        val state = AssetManifestHistoryState(listOf(manifest))

        val decoded = codec.decode(codec.encode(state))

        assertEquals(state, decoded)
        assertEquals(3, decoded.retainedManifests.single().chunkLayout.chunkCount)
    }

    @Test
    fun roundTripsCompressionMetadataWhenPresent() {
        val manifest = manifestOf(
            version = 1L,
            compression = AssetCompressionMetadata(AssetCompressionAlgorithm("gzip"), uncompressedSizeBytes = 40L),
        )
        val state = AssetManifestHistoryState(listOf(manifest))

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsCompressionMetadataWithoutUncompressedSize() {
        val manifest = manifestOf(
            version = 1L,
            compression = AssetCompressionMetadata(AssetCompressionAlgorithm("gzip"), uncompressedSizeBytes = null),
        )
        val state = AssetManifestHistoryState(listOf(manifest))

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsEncryptionMetadataWhenPresent() {
        val manifest = manifestOf(
            version = 1L,
            encryption = AssetEncryptionMetadata(
                AssetEncryptionAlgorithm("AES-256-GCM"),
                KeyReference("app-managed-key-001"),
                byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
            ),
        )
        val state = AssetManifestHistoryState(listOf(manifest))

        val decoded = codec.decode(codec.encode(state))

        assertEquals(state, decoded)
        assertEquals("app-managed-key-001", decoded.retainedManifests.single().encryption?.keyReference?.value)
    }

    @Test
    fun roundTripsMultipleRetainedManifests() {
        val state = AssetManifestHistoryState(
            listOf(manifestOf(version = 1L), manifestOf(version = 2L)),
        )

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsAnAssetIdContainingSeparatorCharacters() {
        // AssetId, unlike AssetMediaType, is not a bounded token -- only
        // non-blank -- so it can legitimately contain this codec's own
        // internal delimiter characters ('|', ':', ';'). Hex-encoding the
        // field before persisting it is exactly what protects against that.
        val manifest = manifestOf(version = 1L, assetId = AssetId("weird|asset:id;here"))
        val state = AssetManifestHistoryState(listOf(manifest))

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun decodeRejectsAnUnrecognizedHeader() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("NOT_AN_ASSET_MANIFEST_HISTORY_PAYLOAD\t1")
        }
    }

    @Test
    fun decodeRejectsATruncatedChunkCount() {
        val manifest = manifestOf(version = 1L)
        val encoded = codec.encode(AssetManifestHistoryState(listOf(manifest)))
        val lines = encoded.split('\n').toMutableList()
        val fields = lines[1].split('|').toMutableList()
        fields[5] = "5" // claims 5 chunks but only 1 is present
        lines[1] = fields.joinToString("|")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(lines.joinToString("\n"))
        }
    }

    @Test
    fun decodeRejectsASizeBytesInconsistentWithChunkLayoutAfterTampering() {
        val manifest = manifestOf(version = 1L, chunkLengths = listOf(10L))
        val encoded = codec.encode(AssetManifestHistoryState(listOf(manifest)))
        val lines = encoded.split('\n').toMutableList()
        val fields = lines[1].split('|').toMutableList()
        fields[2] = "999" // sizeBytes no longer matches chunkLayout.totalSizeBytes
        lines[1] = fields.joinToString("|")

        // AssetManifest's own constructor validation fails closed on the
        // reconstructed, now-inconsistent manifest.
        assertFailsWith<IllegalArgumentException> {
            codec.decode(lines.joinToString("\n"))
        }
    }

    @Test
    fun decodeRejectsATamperedDigestAlgorithmName() {
        val manifest = manifestOf(version = 1L)
        val encoded = codec.encode(AssetManifestHistoryState(listOf(manifest)))
        val lines = encoded.split('\n').toMutableList()
        val fields = lines[1].split('|').toMutableList()
        fields[4] = "NOT_AN_ALGORITHM:${"0".repeat(64)}"
        lines[1] = fields.joinToString("|")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(lines.joinToString("\n"))
        }
    }

    @Test
    fun encodeRejectsAPayloadBeyondTheBoundedLimit() {
        val hugeChunkCount = 40_000
        val manifest = manifestOf(version = 1L, chunkLengths = List(hugeChunkCount) { 1L })
        assertFailsWith<IllegalArgumentException> {
            codec.encode(AssetManifestHistoryState(listOf(manifest)))
        }
    }

    private fun digestOf(seed: Int): DataLoomDigest =
        DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { (it + seed).toByte() })

    private fun manifestOf(
        version: Long,
        chunkLengths: List<Long> = listOf(10L),
        mediaType: String = "application/octet-stream",
        assetId: AssetId = AssetId("asset-001"),
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
            assetId = assetId,
            version = version,
            sizeBytes = chunkLengths.sum(),
            mediaType = AssetMediaType(mediaType),
            checksum = digestOf(999),
            chunkLayout = AssetChunkLayout(chunks),
            compression = compression,
            encryption = encryption,
        )
    }
}
