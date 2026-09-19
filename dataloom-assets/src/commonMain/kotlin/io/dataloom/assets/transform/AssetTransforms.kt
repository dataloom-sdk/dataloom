package io.dataloom.assets.transform

import io.dataloom.api.asset.AssetCompressionAlgorithm
import io.dataloom.api.asset.AssetEncryptionAlgorithm
import io.dataloom.api.security.KeyReference

/**
 * Per-chunk compression SPI (FR-ASSET-007).
 *
 * This slice ships the contract and an [IdentityAssetCompressor] only;
 * choosing and implementing a real algorithm (for example deflate) is a
 * later slice, made against this contract.
 *
 * Compression is applied to one chunk at a time so memory stays bounded by
 * the chunk size.
 */
public interface AssetCompressor {

    /** The label recorded in `AssetCompressionMetadata.algorithm`. */
    public val algorithm: AssetCompressionAlgorithm

    /** Compresses one chunk. */
    public suspend fun compress(chunk: ByteArray): ByteArray

    /**
     * Decompresses one chunk that must expand to exactly [expectedSizeBytes].
     *
     * Implementations must never produce more than [expectedSizeBytes] bytes
     * (a decompression-bomb guard: the size comes from the integrity-checked
     * manifest, not from the compressed stream) and must throw
     * [IllegalArgumentException] if the output would differ from it.
     */
    public suspend fun decompress(compressed: ByteArray, expectedSizeBytes: Int): ByteArray
}

/** A sealed (encrypted and authenticated) chunk and the nonce it was sealed under. */
public class AssetSealedChunk(ciphertext: ByteArray, nonce: ByteArray) {
    private val ciphertext: ByteArray = ciphertext.copyOf()
    private val nonce: ByteArray = nonce.copyOf()

    /** A defensive copy of the sealed bytes. */
    public fun copyCiphertext(): ByteArray = ciphertext.copyOf()

    /** A defensive copy of the nonce; empty only for the identity cipher. */
    public fun copyNonce(): ByteArray = nonce.copyOf()

    /** Never renders content or nonce bytes. */
    override fun toString(): String =
        "AssetSealedChunk(ciphertextSize=${ciphertext.size}, nonceSize=${nonce.size})"

    override fun equals(other: Any?): Boolean =
        other is AssetSealedChunk && ciphertext.contentEquals(other.ciphertext) && nonce.contentEquals(other.nonce)

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + nonce.contentHashCode()
}

/** Thrown by [AssetChunkCipher.open] when authentication of a sealed chunk fails. */
public class AssetChunkAuthenticationException(message: String) : RuntimeException(message)

/**
 * AEAD-style (authenticated encryption with associated data) per-chunk cipher
 * SPI (FR-ASSET-008).
 *
 * The cipher is addressed by [KeyReference]: DataLoom never sees key bytes, so
 * key resolution is the implementation's business (typically a platform
 * keystore). Nonce management is also the implementation's: [seal] returns the
 * nonce it chose, which the caller records in
 * `AssetEncryptionMetadata.nonce`. Callers pass the chunk index and any
 * further context (asset id, version) as [associatedData] to bind a sealed
 * chunk to its position, so chunks cannot be swapped or replayed undetected.
 *
 * This slice ships only the contract and an [IdentityAssetCipher]; choosing
 * and implementing a real algorithm (for example AES-256-GCM) is a later
 * slice. Whether integrity digests cover logical (plaintext) or transferred
 * (sealed) bytes when a cipher is in use is decided in that slice; see
 * `docs/adr/ADR-0003-asset-transfer-and-streaming-digest.md`.
 */
public interface AssetChunkCipher {

    /** The label recorded in `AssetEncryptionMetadata.algorithm`. */
    public val algorithm: AssetEncryptionAlgorithm

    /** Seals [plaintext] under the key named by [keyReference], binding [associatedData]. */
    public suspend fun seal(
        keyReference: KeyReference,
        chunkIndex: Int,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): AssetSealedChunk

    /**
     * Opens a chunk sealed by [seal].
     *
     * @throws AssetChunkAuthenticationException if the chunk, nonce, key, or
     *   [associatedData] do not authenticate. Implementations must not return
     *   unauthenticated plaintext.
     */
    public suspend fun open(
        keyReference: KeyReference,
        chunkIndex: Int,
        sealed: AssetSealedChunk,
        associatedData: ByteArray,
    ): ByteArray
}

/** Pass-through [AssetCompressor]: output equals input. Label `identity`. */
public class IdentityAssetCompressor : AssetCompressor {
    override val algorithm: AssetCompressionAlgorithm = AssetCompressionAlgorithm("identity")

    override suspend fun compress(chunk: ByteArray): ByteArray = chunk.copyOf()

    override suspend fun decompress(compressed: ByteArray, expectedSizeBytes: Int): ByteArray {
        require(compressed.size == expectedSizeBytes) {
            "Identity decompression expected $expectedSizeBytes bytes but got ${compressed.size}."
        }
        return compressed.copyOf()
    }
}

/**
 * Pass-through [AssetChunkCipher]: the "sealed" chunk is the plaintext and the
 * nonce is empty. Label `identity`.
 *
 * **This provides no confidentiality and no authentication.** It exists so
 * the transfer pipeline can be built and tested against the cipher contract
 * before a real algorithm is chosen, and must never be used to satisfy an
 * encryption requirement or recorded in `AssetEncryptionMetadata`.
 */
public class IdentityAssetCipher : AssetChunkCipher {
    override val algorithm: AssetEncryptionAlgorithm = AssetEncryptionAlgorithm("identity")

    override suspend fun seal(
        keyReference: KeyReference,
        chunkIndex: Int,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): AssetSealedChunk = AssetSealedChunk(plaintext, ByteArray(0))

    override suspend fun open(
        keyReference: KeyReference,
        chunkIndex: Int,
        sealed: AssetSealedChunk,
        associatedData: ByteArray,
    ): ByteArray = sealed.copyCiphertext()
}
