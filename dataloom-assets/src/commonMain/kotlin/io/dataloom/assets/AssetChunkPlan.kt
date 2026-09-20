package io.dataloom.assets

import io.dataloom.api.asset.AssetChunkDescriptor
import io.dataloom.api.asset.AssetChunkLayout
import io.dataloom.api.security.DataLoomDigest

/**
 * Chunk-size limits a provider accepts (FR-ASSET-002's "negotiated part
 * sizing"). Object stores commonly impose both a minimum and a maximum part
 * size, so a fixed global default is not enough.
 *
 * @param minBytes smallest accepted chunk size. Must be at least 1. The final
 *   chunk of an asset may be smaller than this; only planned chunk sizes are
 *   bounded.
 * @param maxBytes largest accepted chunk size. Must be at least [minBytes].
 *   Chunk size is also the transfer's per-chunk memory bound, so this is a
 *   memory ceiling as well as a protocol limit.
 */
public data class AssetChunkSizeBounds(
    public val minBytes: Int,
    public val maxBytes: Int,
) {
    init {
        require(minBytes >= 1) { "AssetChunkSizeBounds.minBytes must be at least 1, but was $minBytes." }
        require(maxBytes >= minBytes) {
            "AssetChunkSizeBounds.maxBytes ($maxBytes) must not be below minBytes ($minBytes)."
        }
    }

    /** Clamps [requestedBytes] into these bounds. */
    public fun negotiate(requestedBytes: Int): Int = requestedBytes.coerceIn(minBytes, maxBytes)

    public companion object {
        /** Default chunk size: 1 MiB, small enough for mobile memory budgets. */
        public const val DEFAULT_CHUNK_SIZE_BYTES: Int = 1024 * 1024

        /** Conservative bounds for real providers: 4 KiB through 64 MiB. */
        public val DEFAULT: AssetChunkSizeBounds = AssetChunkSizeBounds(4 * 1024, 64 * 1024 * 1024)
    }
}

/**
 * Fixed-size chunk geometry for one asset: every chunk is [chunkSizeBytes]
 * long except the last, which holds the remainder.
 *
 * This is a *plan* — a pure function of `(totalSizeBytes, chunkSizeBytes)` —
 * whereas [AssetChunkLayout] is the *record* of chunks including their
 * checksums. Once every chunk has been hashed, [toLayout] converts one into
 * the other. A plan never describes an empty asset, matching
 * [io.dataloom.api.asset.AssetManifest], which cannot describe zero chunks.
 *
 * @param totalSizeBytes total asset size. Must be positive.
 * @param chunkSizeBytes size of every chunk but the last. Must be positive.
 * @throws IllegalArgumentException if either size is non-positive, or the
 *   chunk count would not fit in an [Int].
 */
public class AssetChunkPlan(
    public val totalSizeBytes: Long,
    public val chunkSizeBytes: Int,
) {
    /** Number of chunks: `ceil(totalSizeBytes / chunkSizeBytes)`. */
    public val chunkCount: Int

    init {
        require(totalSizeBytes > 0) { "AssetChunkPlan.totalSizeBytes must be positive, but was $totalSizeBytes." }
        require(chunkSizeBytes > 0) { "AssetChunkPlan.chunkSizeBytes must be positive, but was $chunkSizeBytes." }
        // Not (total + chunk - 1) / chunk: that overflows for totals near Long.MAX_VALUE.
        val count = totalSizeBytes / chunkSizeBytes + if (totalSizeBytes % chunkSizeBytes == 0L) 0 else 1
        require(count <= Int.MAX_VALUE) {
            "AssetChunkPlan would need $count chunks; use a larger chunkSizeBytes."
        }
        chunkCount = count.toInt()
    }

    /** Byte offset of the first byte of chunk [index]. */
    public fun offsetBytes(index: Int): Long {
        requireIndex(index)
        return index.toLong() * chunkSizeBytes
    }

    /** Length in bytes of chunk [index]: [chunkSizeBytes], or the remainder for the last chunk. */
    public fun lengthBytes(index: Int): Int {
        requireIndex(index)
        return minOf(chunkSizeBytes.toLong(), totalSizeBytes - offsetBytes(index)).toInt()
    }

    /**
     * Builds the [AssetChunkLayout] for this plan from one digest per chunk,
     * in chunk order.
     *
     * @throws IllegalArgumentException if [checksums] does not contain exactly
     *   [chunkCount] entries.
     */
    public fun toLayout(checksums: List<DataLoomDigest>): AssetChunkLayout {
        require(checksums.size == chunkCount) {
            "Expected $chunkCount chunk checksums but got ${checksums.size}."
        }
        return AssetChunkLayout(
            checksums.mapIndexed { index, checksum ->
                AssetChunkDescriptor(index, offsetBytes(index), lengthBytes(index).toLong(), checksum)
            },
        )
    }

    private fun requireIndex(index: Int) {
        require(index in 0 until chunkCount) {
            "Chunk index $index is outside 0 until $chunkCount."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AssetChunkPlan && totalSizeBytes == other.totalSizeBytes && chunkSizeBytes == other.chunkSizeBytes

    override fun hashCode(): Int = 31 * totalSizeBytes.hashCode() + chunkSizeBytes

    override fun toString(): String =
        "AssetChunkPlan(totalSizeBytes=$totalSizeBytes, chunkSizeBytes=$chunkSizeBytes, chunkCount=$chunkCount)"

    public companion object {
        /**
         * Plans [totalSizeBytes] using [requestedChunkSizeBytes] clamped into
         * the provider's [bounds].
         */
        public fun negotiate(
            totalSizeBytes: Long,
            requestedChunkSizeBytes: Int,
            bounds: AssetChunkSizeBounds,
        ): AssetChunkPlan = AssetChunkPlan(totalSizeBytes, bounds.negotiate(requestedChunkSizeBytes))
    }
}
