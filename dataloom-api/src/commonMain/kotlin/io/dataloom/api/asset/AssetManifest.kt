package io.dataloom.api.asset

import io.dataloom.api.identifier.AssetId
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.KeyReference
import io.dataloom.api.security.isBoundedToken
import kotlin.jvm.JvmInline

/**
 * Versioned description of one asset's shape — identity, size, media type,
 * whole-object integrity, chunk geometry, and optional compression/encryption
 * metadata.
 *
 * ## Scope — bounded first slice
 *
 * [AssetManifest] is a pure, deeply immutable value type. It describes an
 * asset that some producer has already decided how to chunk, whether to
 * compress, and whether to encrypt — it does not perform chunking,
 * compression, or encryption itself, and it carries no upload/download,
 * streaming, session, resume, quota, or provider logic. Those remain
 * entirely open (see issue `#97`, FR-ASSET-002 through FR-ASSET-012).
 * This mirrors [io.dataloom.api.configuration.ConfigurationSnapshot]
 * (describes a config's shape, not how it was produced) and
 * [io.dataloom.api.security.KeyReference] (names a key without ever
 * resolving it to key bytes).
 *
 * ## Versioning
 *
 * [AssetManifest] never changes after construction. A new revision of the
 * same logical asset is a new [AssetManifest] instance sharing [assetId]
 * with a higher [version]; nothing here enforces monotonicity across
 * revisions — that is a future durable-history concern, not this type's.
 *
 * ## Integrity
 *
 * [checksum] is a whole-object [DataLoomDigest] — the same generic,
 * algorithm-labeled digest type `#234` already shipped for exactly this
 * purpose, reused as-is rather than inventing an asset-specific checksum
 * representation. [chunkLayout] additionally carries one [DataLoomDigest]
 * per chunk (FR-ASSET-004's "per-chunk ... integrity verification"), but
 * this type only carries those digests — it never computes or verifies
 * them.
 *
 * ## Chunk geometry
 *
 * [chunkLayout] describes chunks as an ordered list of contiguous,
 * non-overlapping byte ranges (see [AssetChunkDescriptor]) rather than a
 * single "chunk size" plus count. That deliberately does not presuppose
 * uniform fixed-size chunking — a future content-defined or
 * variable-size chunking algorithm can still produce a valid
 * [AssetChunkLayout] describing its own output. [sizeBytes] must equal
 * [AssetChunkLayout.totalSizeBytes]; construction fails otherwise.
 *
 * ## Compression and encryption — labels only, no algorithm decided here
 *
 * [compression] and [encryption], when present, name an algorithm as a
 * bounded, extensible token — [AssetCompressionAlgorithm] /
 * [AssetEncryptionAlgorithm] — rather than a closed enum, exactly how
 * `OperationalPayloadEncoding` names a wire encoding without this codebase
 * deciding the closed set of encodings up front. Which algorithms V1
 * actually implements is an open product decision this type does not make;
 * it only records, immutably, which one a producer used. [encryption] never
 * carries key bytes: it names the key via [KeyReference] — the same
 * "DataLoom never resolves it to key bytes" boundary [KeyReference] itself
 * documents — and DataLoom never performs encryption on the strength of
 * this metadata.
 *
 * @param assetId identity of the logical asset this manifest describes.
 * @param version this manifest's revision number for [assetId]. Must be
 *   positive.
 * @param sizeBytes total decompressed, unencrypted size of the asset in
 *   bytes. Must be non-negative and must equal
 *   [AssetChunkLayout.totalSizeBytes].
 * @param mediaType the asset's media type, for example `image/png` or
 *   `application/pdf`.
 * @param checksum whole-object integrity digest over the asset's bytes.
 * @param chunkLayout the asset's chunk geometry and per-chunk checksums.
 * @param compression optional compression metadata; `null` means the asset
 *   is stored/transferred uncompressed.
 * @param encryption optional encryption metadata; `null` means the asset is
 *   stored/transferred unencrypted (from DataLoom's perspective).
 */
public data class AssetManifest(
    public val assetId: AssetId,
    public val version: Long,
    public val sizeBytes: Long,
    public val mediaType: AssetMediaType,
    public val checksum: DataLoomDigest,
    public val chunkLayout: AssetChunkLayout,
    public val compression: AssetCompressionMetadata? = null,
    public val encryption: AssetEncryptionMetadata? = null,
) {
    init {
        require(version > 0) { "AssetManifest.version must be positive, but was $version." }
        require(sizeBytes >= 0) { "AssetManifest.sizeBytes must not be negative, but was $sizeBytes." }
        require(sizeBytes == chunkLayout.totalSizeBytes) {
            "AssetManifest.sizeBytes ($sizeBytes) must equal chunkLayout.totalSizeBytes " +
                "(${chunkLayout.totalSizeBytes})."
        }
    }
}

/**
 * An asset's media type, for example `image/png` or `application/pdf`.
 *
 * A bounded token, not a closed enum — the universe of media types is open
 * and application/format defined; DataLoom does not interpret or validate
 * it beyond the bounded-token shape shared with other free-form identifier
 * labels in this codebase (see [isBoundedToken]).
 */
@JvmInline
public value class AssetMediaType(
    public val value: String,
) {
    init {
        require(isBoundedToken(value, MAX_ASSET_TOKEN_LENGTH, ::isAssetTokenCharacter)) {
            "AssetMediaType must be a bounded token."
        }
    }

    override fun toString(): String = value
}

/**
 * One contiguous, non-overlapping byte range within an asset, plus its own
 * integrity digest.
 *
 * @param index this chunk's zero-based position within its
 *   [AssetChunkLayout]. Chunks are addressed by position, not by an
 *   independent identifier, because a chunk only has meaning relative to the
 *   asset it partitions.
 * @param offsetBytes byte offset of this chunk's first byte within the
 *   asset. Must be non-negative.
 * @param lengthBytes number of bytes in this chunk. Must be positive — an
 *   empty chunk carries no data worth transferring separately.
 * @param checksum integrity digest over this chunk's bytes alone.
 */
public data class AssetChunkDescriptor(
    public val index: Int,
    public val offsetBytes: Long,
    public val lengthBytes: Long,
    public val checksum: DataLoomDigest,
) {
    init {
        require(index >= 0) { "AssetChunkDescriptor.index must not be negative, but was $index." }
        require(offsetBytes >= 0) {
            "AssetChunkDescriptor.offsetBytes must not be negative, but was $offsetBytes."
        }
        require(lengthBytes > 0) { "AssetChunkDescriptor.lengthBytes must be positive, but was $lengthBytes." }
    }
}

/**
 * An asset's chunk geometry: an ordered, contiguous, non-overlapping
 * partition of its bytes, described as [AssetChunkDescriptor]s.
 *
 * Deliberately does not carry a single "chunk size" — chunks may vary in
 * length, so this type neither assumes nor requires uniform fixed-size
 * chunking. It only describes a layout some producer already decided on; it
 * has no chunking algorithm of its own.
 *
 * @param chunks the ordered chunk descriptors. Defensively copied. Must be
 *   non-empty, indexed `0` through `chunks.size - 1` in order, with each
 *   chunk's [AssetChunkDescriptor.offsetBytes] immediately following the
 *   previous chunk's end — no gaps, no overlaps.
 */
public class AssetChunkLayout(
    chunks: List<AssetChunkDescriptor>,
) {
    public val chunks: List<AssetChunkDescriptor> = chunks.toList()

    /** Number of chunks in this layout. */
    public val chunkCount: Int
        get() = chunks.size

    /** Sum of every chunk's [AssetChunkDescriptor.lengthBytes]. */
    public val totalSizeBytes: Long
        get() = chunks.sumOf { it.lengthBytes }

    init {
        require(this.chunks.isNotEmpty()) { "AssetChunkLayout.chunks must not be empty." }
        var expectedOffset = 0L
        this.chunks.forEachIndexed { position, chunk ->
            require(chunk.index == position) {
                "AssetChunkLayout.chunks must be indexed 0..n-1 in order; " +
                    "chunk at position $position has index ${chunk.index}."
            }
            require(chunk.offsetBytes == expectedOffset) {
                "AssetChunkLayout.chunks must be contiguous with no gaps or overlaps; " +
                    "chunk $position expected offsetBytes $expectedOffset but was ${chunk.offsetBytes}."
            }
            expectedOffset += chunk.lengthBytes
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetChunkLayout) return false
        return chunks == other.chunks
    }

    override fun hashCode(): Int = chunks.hashCode()

    override fun toString(): String =
        "AssetChunkLayout(chunkCount=$chunkCount, totalSizeBytes=$totalSizeBytes)"
}

/**
 * An asset compression algorithm identifier, for example `gzip` or `zstd`.
 *
 * A bounded token, not a closed enum. Which compression algorithms V1
 * actually supports is an open product decision this type does not make —
 * see [AssetManifest]'s "Compression and encryption" documentation. This
 * type only lets a manifest record, immutably, which label a producer
 * already used.
 */
@JvmInline
public value class AssetCompressionAlgorithm(
    public val value: String,
) {
    init {
        require(isBoundedToken(value, MAX_ASSET_TOKEN_LENGTH, ::isAssetTokenCharacter)) {
            "AssetCompressionAlgorithm must be a bounded token."
        }
    }

    override fun toString(): String = value
}

/**
 * Compression metadata for an [AssetManifest].
 *
 * Presence of this type on a manifest means the asset's transferred bytes
 * are compressed under [algorithm]; absence ([AssetManifest.compression] is
 * `null`) means uncompressed. DataLoom never compresses or decompresses on
 * the strength of this metadata — it only records which algorithm a
 * producer used.
 *
 * @param algorithm the compression algorithm used.
 * @param uncompressedSizeBytes the asset's size before compression, if
 *   known. Must be non-negative when present. Not required to equal
 *   [AssetManifest.sizeBytes] here — [AssetManifest.sizeBytes] is always the
 *   logical (decompressed) size; this field exists for producers that want
 *   to additionally record the pre-compression size explicitly, redundant
 *   or not.
 */
public data class AssetCompressionMetadata(
    public val algorithm: AssetCompressionAlgorithm,
    public val uncompressedSizeBytes: Long? = null,
) {
    init {
        require(uncompressedSizeBytes == null || uncompressedSizeBytes >= 0) {
            "AssetCompressionMetadata.uncompressedSizeBytes must not be negative, " +
                "but was $uncompressedSizeBytes."
        }
    }
}

/**
 * An asset encryption algorithm identifier, for example `AES-256-GCM`.
 *
 * A bounded token, not a closed enum, for the same reason as
 * [AssetCompressionAlgorithm]: which encryption algorithms V1 actually
 * supports is an open product decision this type does not make.
 */
@JvmInline
public value class AssetEncryptionAlgorithm(
    public val value: String,
) {
    init {
        require(isBoundedToken(value, MAX_ASSET_TOKEN_LENGTH, ::isAssetTokenCharacter)) {
            "AssetEncryptionAlgorithm must be a bounded token."
        }
    }

    override fun toString(): String = value
}

/**
 * Encryption metadata for an [AssetManifest].
 *
 * Presence of this type on a manifest means the asset's transferred bytes
 * are encrypted under [algorithm] using the key named by [keyReference].
 * DataLoom never performs encryption or decryption on the strength of this
 * metadata, and — mirroring [KeyReference] itself — never resolves
 * [keyReference] to key bytes. [nonce] is carried opaquely: because
 * [algorithm] is an open token rather than a closed enum, this type cannot
 * validate a fixed nonce length the way [DataLoomDigest] validates digest
 * length against a closed [io.dataloom.api.security.DigestAlgorithm].
 *
 * ## Defensive copying
 *
 * Same pattern as [DataLoomDigest] and
 * `io.dataloom.api.security.DataLoomMac`: the constructor defensively
 * copies [nonce], and [copyNonceBytes] returns a fresh defensive copy.
 *
 * ## Render safety
 *
 * [toString] never renders [nonce] bytes or [keyReference] beyond its own
 * already-safe label — only [algorithm] and the nonce's length.
 *
 * @param algorithm the encryption algorithm used.
 * @param keyReference opaque reference to the key used. Never resolved to
 *   key bytes by DataLoom.
 * @param nonce the nonce/IV used, defensively copied. Must not be empty.
 */
public class AssetEncryptionMetadata(
    public val algorithm: AssetEncryptionAlgorithm,
    public val keyReference: KeyReference,
    nonce: ByteArray,
) {
    private val nonce: ByteArray = nonce.copyOf()

    init {
        require(this.nonce.isNotEmpty()) { "AssetEncryptionMetadata.nonce must not be empty." }
    }

    /** Number of bytes in [nonce]. */
    public val nonceSize: Int
        get() = nonce.size

    /**
     * Returns a defensive copy of the internal nonce bytes.
     *
     * Mutating the returned array does not affect this metadata.
     */
    public fun copyNonceBytes(): ByteArray = nonce.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetEncryptionMetadata) return false
        return algorithm == other.algorithm &&
            keyReference == other.keyReference &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + keyReference.hashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }

    /** Never renders [nonce] bytes or key material — only [algorithm] and nonce length. */
    override fun toString(): String =
        "AssetEncryptionMetadata(algorithm=$algorithm, nonceSize=${nonce.size})"
}

private const val MAX_ASSET_TOKEN_LENGTH: Int = 256

private fun isAssetTokenCharacter(character: Char): Boolean =
    character in 'a'..'z' ||
        character in 'A'..'Z' ||
        character in '0'..'9' ||
        character == '.' ||
        character == '_' ||
        character == '-' ||
        character == '+' ||
        character == '/'
