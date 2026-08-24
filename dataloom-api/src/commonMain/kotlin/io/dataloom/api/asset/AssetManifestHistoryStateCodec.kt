package io.dataloom.api.asset

import io.dataloom.api.identifier.AssetId
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.security.KeyReference
import io.dataloom.api.state.DurableStateCodec

/**
 * Deterministic bounded V1 text codec for [AssetManifestHistoryState], for
 * use with a generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation (for example [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore]).
 *
 * ## Integrity — different shape from [io.dataloom.api.configuration.ConfigurationHistoryStateCodec]
 *
 * [io.dataloom.api.configuration.ConfigurationHistoryStateCodec.decode]
 * recomputes each decoded snapshot's checksum from its own decoded entries
 * via `ConfigurationSnapshot.create`, because a configuration snapshot's
 * checksum is defined *over its own fields*. An [AssetManifest.checksum] is
 * different: it is a whole-object digest over the asset's actual bytes,
 * which this codec never has access to (it only ever sees manifest
 * metadata) — so there is nothing for [decode] to independently recompute
 * the way the configuration codec does.
 *
 * Instead, [decode] reconstructs every [AssetManifest] (and its nested
 * [AssetChunkLayout]) through their real public constructors, which already
 * enforce every cross-field invariant [AssetManifest] and [AssetChunkLayout]
 * themselves define — [AssetManifest.version] positive,
 * [AssetManifest.sizeBytes] non-negative and equal to
 * `chunkLayout.totalSizeBytes`, chunk geometry contiguous and gap/overlap
 * free, and [DataLoomDigest] byte-length matching its [DigestAlgorithm]. So
 * storage-layer corruption that still parses as well-formed fields but
 * violates any of those invariants still fails closed — just via the domain
 * type's own constructor validation rather than an independently recomputed
 * checksum.
 *
 * ## What this never encodes
 *
 * [AssetEncryptionMetadata.keyReference] is an opaque [KeyReference] label,
 * never key bytes; [AssetEncryptionMetadata.copyNonceBytes] is a nonce/IV,
 * not secret key material. This codec persists both exactly as
 * [AssetManifest] itself already exposes them — it never carries anything
 * beyond that.
 */
public class AssetManifestHistoryStateCodec : DurableStateCodec<AssetManifestHistoryState> {

    override fun encode(state: AssetManifestHistoryState): String {
        val lines = mutableListOf("$HEADER\t$FORMAT_VERSION")
        state.retainedManifests.forEach { lines += encodeManifestLine(it) }
        val encoded = lines.joinToString("\n")
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded asset manifest history exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): AssetManifestHistoryState {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded asset manifest history exceeds the bounded V1 limit."
        }
        return try {
            val lines = payload.split('\n')
            require(lines.isNotEmpty())
            val header = lines.first().split('\t')
            require(header.size == 2)
            require(header[0] == HEADER)
            require(header[1] == FORMAT_VERSION)
            val manifests = (1 until lines.size).map { decodeManifestLine(lines[it]) }
            AssetManifestHistoryState(manifests)
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed asset manifest history payload.", malformed)
        }
    }

    private fun encodeManifestLine(manifest: AssetManifest): String {
        val fields = listOf(
            hexEncode(manifest.assetId.value),
            manifest.version.toString(),
            manifest.sizeBytes.toString(),
            hexEncode(manifest.mediaType.value),
            encodeDigest(manifest.checksum),
            manifest.chunkLayout.chunkCount.toString(),
            manifest.chunkLayout.chunks.joinToString(CHUNK_SEPARATOR) { encodeChunk(it) },
            encodeCompression(manifest.compression),
            encodeEncryption(manifest.encryption),
        )
        return fields.joinToString("|")
    }

    private fun decodeManifestLine(line: String): AssetManifest {
        val fields = line.split('|')
        require(fields.size == FIELD_COUNT)
        val assetId = AssetId(hexDecode(fields[0]))
        val version = fields[1].toLong()
        val sizeBytes = fields[2].toLong()
        val mediaType = AssetMediaType(hexDecode(fields[3]))
        val checksum = decodeDigest(fields[4])
        val chunkCount = fields[5].toInt()
        require(chunkCount >= 0)
        val chunkFields = if (chunkCount == 0) emptyList() else fields[6].split(CHUNK_SEPARATOR)
        require(chunkFields.size == chunkCount)
        val chunks = chunkFields.map { decodeChunk(it) }
        val compression = decodeCompression(fields[7])
        val encryption = decodeEncryption(fields[8])
        return AssetManifest(
            assetId = assetId,
            version = version,
            sizeBytes = sizeBytes,
            mediaType = mediaType,
            checksum = checksum,
            chunkLayout = AssetChunkLayout(chunks),
            compression = compression,
            encryption = encryption,
        )
    }

    private fun encodeChunk(chunk: AssetChunkDescriptor): String =
        listOf(
            chunk.index.toString(),
            chunk.offsetBytes.toString(),
            chunk.lengthBytes.toString(),
            encodeDigest(chunk.checksum),
        ).joinToString(SUBFIELD_SEPARATOR)

    private fun decodeChunk(field: String): AssetChunkDescriptor {
        val parts = field.split(SUBFIELD_SEPARATOR)
        require(parts.size == 5)
        return AssetChunkDescriptor(
            index = parts[0].toInt(),
            offsetBytes = parts[1].toLong(),
            lengthBytes = parts[2].toLong(),
            checksum = decodeDigest("${parts[3]}$SUBFIELD_SEPARATOR${parts[4]}"),
        )
    }

    private fun encodeDigest(digest: DataLoomDigest): String =
        "${digest.algorithm.name}$SUBFIELD_SEPARATOR${digest.toHex()}"

    private fun decodeDigest(field: String): DataLoomDigest {
        val parts = field.split(SUBFIELD_SEPARATOR, limit = 2)
        require(parts.size == 2)
        return DataLoomDigest(DigestAlgorithm.valueOf(parts[0]), hexDecodeBytes(parts[1]))
    }

    private fun encodeCompression(compression: AssetCompressionMetadata?): String {
        if (compression == null) return ABSENT
        return listOf(
            PRESENT,
            hexEncode(compression.algorithm.value),
            compression.uncompressedSizeBytes?.toString() ?: EMPTY,
        ).joinToString(SUBFIELD_SEPARATOR)
    }

    private fun decodeCompression(field: String): AssetCompressionMetadata? {
        if (field == ABSENT) return null
        val parts = field.split(SUBFIELD_SEPARATOR)
        require(parts.size == 3)
        require(parts[0] == PRESENT)
        return AssetCompressionMetadata(
            algorithm = AssetCompressionAlgorithm(hexDecode(parts[1])),
            uncompressedSizeBytes = if (parts[2] == EMPTY) null else parts[2].toLong(),
        )
    }

    private fun encodeEncryption(encryption: AssetEncryptionMetadata?): String {
        if (encryption == null) return ABSENT
        return listOf(
            PRESENT,
            hexEncode(encryption.algorithm.value),
            hexEncode(encryption.keyReference.value),
            hexEncodeBytes(encryption.copyNonceBytes()),
        ).joinToString(SUBFIELD_SEPARATOR)
    }

    private fun decodeEncryption(field: String): AssetEncryptionMetadata? {
        if (field == ABSENT) return null
        val parts = field.split(SUBFIELD_SEPARATOR)
        require(parts.size == 4)
        require(parts[0] == PRESENT)
        return AssetEncryptionMetadata(
            algorithm = AssetEncryptionAlgorithm(hexDecode(parts[1])),
            keyReference = KeyReference(hexDecode(parts[2])),
            nonce = hexDecodeBytes(parts[3]),
        )
    }

    private fun hexEncode(value: String): String = hexEncodeBytes(value.encodeToByteArray())

    private fun hexDecode(value: String): String = hexDecodeBytes(value).decodeToString(throwOnInvalidSequence = true)

    private fun hexEncodeBytes(bytes: ByteArray): String = buildString {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }

    private fun hexDecodeBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].hexValue()
            val low = value[(index * 2) + 1].hexValue()
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        else -> error("Invalid hex digit.")
    }

    private companion object {
        const val HEADER: String = "DATALOOM_ASSET_MANIFEST_HISTORY"
        const val FORMAT_VERSION: String = "1"
        const val MAX_ENCODED_LENGTH: Int = 1_048_576
        const val HEX: String = "0123456789abcdef"
        const val FIELD_COUNT: Int = 9
        const val CHUNK_SEPARATOR: String = ";"
        const val SUBFIELD_SEPARATOR: String = ":"
        const val ABSENT: String = "N"
        const val PRESENT: String = "Y"
        const val EMPTY: String = ""
    }
}
