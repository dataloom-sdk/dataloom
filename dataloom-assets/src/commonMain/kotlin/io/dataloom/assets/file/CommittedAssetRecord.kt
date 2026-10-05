package io.dataloom.assets.file

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetManifestHistoryState
import io.dataloom.api.asset.AssetManifestHistoryStateCodec
import io.dataloom.assets.transform.AssetWireFormat

/**
 * What a file-backed provider persists next to a committed `asset.bin` so a
 * fresh provider instance over the same directory can rebuild its committed
 * index after a restart: the [manifest] (needed to serve `readManifest` and to
 * verify chunks) and the stored byte length of every chunk (needed to locate
 * each chunk inside `asset.bin`).
 *
 * The stored lengths cannot be derived from the manifest alone: for a
 * compressed or encrypted manifest the provider stores opaque transform frames
 * whose sizes differ from the manifest's logical chunk lengths (ADR-0014).
 * Offsets are the running sum of the lengths.
 */
internal class CommittedAssetRecord(
    val manifest: AssetManifest,
    val storedChunkLengths: IntArray,
) {
    /** Total bytes `asset.bin` must hold for this record to be believable. */
    val storedSizeBytes: Long get() = storedChunkLengths.sumOf { it.toLong() }
}

/**
 * Deterministic bounded V1 text codec for [CommittedAssetRecord]:
 *
 * ```
 * DATALOOM_FILE_ASSET_COMMIT<TAB>1
 * <stored chunk lengths, comma separated, one per manifest chunk>
 * <the AssetManifestHistoryStateCodec payload of exactly one manifest (2 lines)>
 * ```
 *
 * The manifest rides in [AssetManifestHistoryStateCodec] rather than a new
 * format, so it is reconstructed through the real [AssetManifest] constructors
 * and every cross-field invariant they define is enforced on decode. [decode]
 * is fail-closed: it throws [IllegalArgumentException] for anything oversized,
 * truncated, of an unknown header or version, unparseable, or whose stored
 * lengths contradict the manifest (wrong count; for an untransformed manifest,
 * any length differing from its descriptor; for a transformed one, a length
 * outside `1..descriptor + MAX_FRAME_OVERHEAD_BYTES`, the same bound the
 * providers apply on upload).
 *
 * It never carries bytes or key material; the manifest's own codec documents
 * what it persists.
 */
internal object CommittedAssetRecordCodec {

    const val MAX_ENCODED_LENGTH: Int = 2 * 1_048_576

    private const val HEADER: String = "DATALOOM_FILE_ASSET_COMMIT"
    private const val FORMAT_VERSION: String = "1"

    private val manifestCodec = AssetManifestHistoryStateCodec()

    fun encode(record: CommittedAssetRecord): String {
        val encoded = listOf(
            "$HEADER\t$FORMAT_VERSION",
            record.storedChunkLengths.joinToString(","),
            manifestCodec.encode(AssetManifestHistoryState(listOf(record.manifest))),
        ).joinToString("\n")
        require(encoded.length <= MAX_ENCODED_LENGTH) { "Encoded committed asset record exceeds the bounded V1 limit." }
        return encoded
    }

    fun decode(payload: String): CommittedAssetRecord {
        require(payload.length <= MAX_ENCODED_LENGTH) { "Encoded committed asset record exceeds the bounded V1 limit." }
        return try {
            val lines = payload.split('\n')
            require(lines.size == 4)
            val header = lines[0].split('\t')
            require(header.size == 2 && header[0] == HEADER && header[1] == FORMAT_VERSION)
            val manifest = manifestCodec.decode(lines[2] + "\n" + lines[3]).retainedManifests.single()
            val chunks = manifest.chunkLayout.chunks
            val lengths = lines[1].split(',').map { it.toInt() }.toIntArray()
            require(lengths.size == chunks.size)
            val transformed = AssetWireFormat.isTransformed(manifest)
            for (index in lengths.indices) {
                val logical = chunks[index].lengthBytes
                if (transformed) {
                    require(lengths[index] >= 1 && lengths[index] <= logical + AssetWireFormat.MAX_FRAME_OVERHEAD_BYTES)
                } else {
                    require(lengths[index].toLong() == logical)
                }
            }
            CommittedAssetRecord(manifest, lengths)
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed committed asset record payload.", malformed)
        }
    }
}
