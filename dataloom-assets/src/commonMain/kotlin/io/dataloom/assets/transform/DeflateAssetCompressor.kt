package io.dataloom.assets.transform

import io.dataloom.api.asset.AssetCompressionAlgorithm

/**
 * Per-chunk zlib/DEFLATE compression (RFC 1950 stream around RFC 1951 DEFLATE),
 * the algorithm ADR-0014 chose for FR-ASSET-007.
 *
 * JVM and Android use `java.util.zip`; Apple platforms use the system zlib
 * through Kotlin/Native's `platform.zlib`. Both emit standard zlib streams, so
 * a chunk compressed on one platform decompresses on the other.
 *
 * Decompression is bounded: [decompress] never produces more than
 * `expectedSizeBytes + 1` bytes, rejects output of any length other than
 * `expectedSizeBytes`, truncated or corrupt streams, and bytes trailing the
 * stream, all with [IllegalArgumentException].
 *
 * @param level zlib compression level `1..9` (1 fastest, 9 smallest); default 6.
 */
public class DeflateAssetCompressor(private val level: Int = DEFAULT_LEVEL) : AssetCompressor {

    init {
        require(level in 1..9) { "DeflateAssetCompressor.level must be within 1..9, but was $level." }
    }

    override val algorithm: AssetCompressionAlgorithm = LABEL

    override suspend fun compress(chunk: ByteArray): ByteArray = platformDeflate(chunk, level)

    override suspend fun decompress(compressed: ByteArray, expectedSizeBytes: Int): ByteArray {
        require(expectedSizeBytes >= 0 && expectedSizeBytes < Int.MAX_VALUE) {
            "expectedSizeBytes is out of range."
        }
        return platformInflate(compressed, expectedSizeBytes)
    }

    public companion object {
        /** The label recorded in the manifest's compression metadata. */
        public val LABEL: AssetCompressionAlgorithm = AssetCompressionAlgorithm("zlib-deflate")

        /** Default zlib level. */
        public const val DEFAULT_LEVEL: Int = 6
    }
}

// Platform zlib. Both directions are pure functions of their arguments.

/** A complete zlib stream of [input] at [level]. */
internal expect fun platformDeflate(input: ByteArray, level: Int): ByteArray

/**
 * Inflates [compressed], which must be one complete zlib stream expanding to
 * exactly [expectedSize] bytes with nothing after it.
 *
 * @throws IllegalArgumentException otherwise.
 */
internal expect fun platformInflate(compressed: ByteArray, expectedSize: Int): ByteArray
