package io.dataloom.assets

import io.dataloom.api.asset.AssetChunkDescriptor
import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.DigestAlgorithm

/**
 * Per-chunk and whole-object integrity verification (FR-ASSET-004), and
 * manifest preparation from a source, in bounded memory.
 *
 * - **Per-chunk** verification hashes one chunk-sized array with the one-shot
 *   [io.dataloom.api.security.DataLoomDigestCalculator.digest].
 * - **Whole-object** verification streams the bytes through a
 *   [io.dataloom.api.security.DataLoomDigestAccumulator] in pieces of at most
 *   [readBufferBytes], so memory use is independent of the asset's size. The
 *   manifest's whole-object checksum is a digest of the raw bytes (not of the
 *   chunk digests), which is exactly what the accumulator computes.
 *
 * @param readBufferBytes size of the single reusable buffer used while
 *   streaming; the memory bound of [verifyObject] and [prepareManifest]'s
 *   whole-object pass.
 */
public class AssetIntegrityVerifier(
    private val calculator: DataLoomIncrementalDigestCalculator,
    private val readBufferBytes: Int = DEFAULT_READ_BUFFER_BYTES,
) {
    init {
        require(readBufferBytes > 0) { "readBufferBytes must be positive, but was $readBufferBytes." }
    }

    /**
     * `true` iff [bytes] has exactly the descriptor's length and hashes to the
     * descriptor's checksum under the checksum's own algorithm.
     */
    public fun verifyChunk(descriptor: AssetChunkDescriptor, bytes: ByteArray): Boolean =
        bytes.size.toLong() == descriptor.lengthBytes &&
            descriptor.checksum contentEquals calculator.digest(descriptor.checksum.algorithm, bytes)

    /**
     * Streams [manifest]`.sizeBytes` bytes from [reader] from position 0 and
     * `true` iff they hash to [AssetManifest.checksum]. Returns `false` if the
     * reader ends before the full size.
     */
    public suspend fun verifyObject(manifest: AssetManifest, reader: AssetReadable): Boolean {
        val computed = digestRange(reader, 0, manifest.sizeBytes, manifest.checksum.algorithm) ?: return false
        return manifest.checksum contentEquals computed
    }

    /**
     * Builds the [AssetManifest] for [source] by reading it once, chunk by
     * chunk: each chunk is hashed one-shot and also fed to a single
     * whole-object accumulator. Peak memory is one chunk.
     *
     * @throws AssetSourceChangedException if the source ends before its
     *   size ([AssetChunkPlan.totalSizeBytes], normally
     *   [AssetSource.sizeBytes]).
     */
    public suspend fun prepareManifest(
        assetId: AssetId,
        version: Long,
        mediaType: AssetMediaType,
        source: AssetSource,
        plan: AssetChunkPlan,
        algorithm: DigestAlgorithm,
    ): AssetManifest {
        val buffer = ByteArray(plan.chunkSizeBytes)
        val checksums = ArrayList<DataLoomDigest>(plan.chunkCount)
        val whole = calculator.newAccumulator(algorithm)
        val wholeDigest = whole.use {
            for (index in 0 until plan.chunkCount) {
                val length = plan.lengthBytes(index)
                val read = source.readFully(plan.offsetBytes(index), buffer, 0, length)
                if (read != length) {
                    throw AssetSourceChangedException("Source ended before its declared size.")
                }
                whole.update(buffer, 0, length)
                checksums.add(calculator.digest(algorithm, if (length == buffer.size) buffer else buffer.copyOf(length)))
            }
            whole.finish()
        }
        return AssetManifest(
            assetId = assetId,
            version = version,
            sizeBytes = plan.totalSizeBytes,
            mediaType = mediaType,
            checksum = wholeDigest,
            chunkLayout = plan.toLayout(checksums),
        )
    }

    private suspend fun digestRange(
        reader: AssetReadable,
        start: Long,
        length: Long,
        algorithm: DigestAlgorithm,
    ): DataLoomDigest? {
        val buffer = ByteArray(minOf(readBufferBytes.toLong(), length).toInt().coerceAtLeast(1))
        return calculator.newAccumulator(algorithm).use { accumulator ->
            var position = start
            val end = start + length
            while (position < end) {
                val wanted = minOf(buffer.size.toLong(), end - position).toInt()
                val read = reader.readFully(position, buffer, 0, wanted)
                if (read != wanted) return null
                accumulator.update(buffer, 0, read)
                position += read
            }
            accumulator.finish()
        }
    }

    public companion object {
        /** Default streaming buffer: 64 KiB. */
        public const val DEFAULT_READ_BUFFER_BYTES: Int = 64 * 1024
    }
}
