package io.dataloom.assets.transform

import io.dataloom.api.asset.AssetEncryptionAlgorithm
import io.dataloom.api.random.DataLoomSecureRandom
import io.dataloom.api.security.KeyReference
import kotlinx.coroutines.CancellationException

/**
 * Per-chunk AES-256-GCM authenticated encryption (FR-ASSET-008), the algorithm
 * ADR-0014 chose.
 *
 * - **Keys** come from the host through [keys]; DataLoom never generates,
 *   stores or persists key material. AES-256 needs exactly 32 bytes
 *   ([AssetKeyInvalidException] otherwise).
 * - **Nonce**: a fresh random 96-bit nonce per sealed chunk from
 *   [random] (a [DataLoomSecureRandom]), returned in the [AssetSealedChunk] and
 *   stored in the chunk's frame. With random nonces a single key must not seal
 *   more than about 2^32 chunks (NIST SP 800-38D); at the default 1 MiB chunk
 *   size that is petabytes, but hosts rotate keys long before.
 * - **Associated data**: the 4-byte big-endian [chunkIndex] followed by the
 *   caller's `associatedData` (the transfer pipeline passes the frame version and
 *   flags, asset id, asset version, chunk count and asset size). Opening with
 *   any different index or associated data fails authentication, so chunks
 *   cannot be reordered, swapped between assets or versions, or replayed.
 * - **Output** of [seal] is `ciphertext || 16-byte tag`; [open] verifies the
 *   tag and never returns unauthenticated plaintext.
 *
 * ## Platform support
 *
 * JVM and Android use `javax.crypto` (`AES/GCM/NoPadding`). **Apple platforms
 * are unsupported**: Kotlin/Native's bundled `platform.CoreCrypto` binding
 * exposes only CBC/ECB/CFB/CTR/OFB modes, and CryptoKit is Swift-only, so
 * [isSupported] is `false` there and [seal]/[open] throw
 * [AssetTransformUnsupportedException]. The engine turns that into
 * [io.dataloom.assets.AssetErrorKind.TRANSFORM_UNSUPPORTED]; it never falls
 * back to plaintext. Hand-rolling GCM on top of AES-CTR was rejected (see ADR-0014).
 *
 * @param keys the host's key lookup.
 * @param random source of nonces; inject a real CSPRNG such as
 *   `SystemDataLoomSecureRandom`/`AppleDataLoomSecureRandom`.
 */
public class AesGcmAssetChunkCipher(
    private val keys: AssetKeyResolver,
    private val random: DataLoomSecureRandom,
) : AssetChunkCipher {

    override val algorithm: AssetEncryptionAlgorithm = LABEL

    override val isSupported: Boolean get() = platformAesGcmSupported()

    override suspend fun seal(
        keyReference: KeyReference,
        chunkIndex: Int,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): AssetSealedChunk {
        requireSupported()
        require(chunkIndex >= 0) { "chunkIndex must not be negative, but was $chunkIndex." }
        val key = resolveKey(keyReference)
        val nonce = random.nextBytes(NONCE_BYTES)
        val sealed = platformAesGcmSeal(key, nonce, boundAssociatedData(chunkIndex, associatedData), plaintext)
        return AssetSealedChunk(sealed, nonce)
    }

    override suspend fun open(
        keyReference: KeyReference,
        chunkIndex: Int,
        sealed: AssetSealedChunk,
        associatedData: ByteArray,
    ): ByteArray {
        requireSupported()
        require(chunkIndex >= 0) { "chunkIndex must not be negative, but was $chunkIndex." }
        val nonce = sealed.copyNonce()
        val ciphertext = sealed.copyCiphertext()
        if (nonce.size != NONCE_BYTES || ciphertext.size < TAG_BYTES) {
            throw AssetChunkAuthenticationException("Sealed chunk is malformed.")
        }
        val key = resolveKey(keyReference)
        return platformAesGcmOpen(key, nonce, boundAssociatedData(chunkIndex, associatedData), ciphertext)
    }

    private fun requireSupported() {
        if (!platformAesGcmSupported()) {
            throw AssetTransformUnsupportedException("AES-256-GCM is not available on this platform.")
        }
    }

    private suspend fun resolveKey(keyReference: KeyReference): ByteArray {
        val key = try {
            keys.resolve(keyReference)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AssetKeyUnavailableException("The encryption key could not be resolved.", e)
        }
        if (key.size != KEY_BYTES) throw AssetKeyInvalidException("The encryption key must be $KEY_BYTES bytes.")
        return key
    }

    private fun boundAssociatedData(chunkIndex: Int, associatedData: ByteArray): ByteArray {
        val bound = ByteArray(4 + associatedData.size)
        bound[0] = (chunkIndex ushr 24).toByte()
        bound[1] = (chunkIndex ushr 16).toByte()
        bound[2] = (chunkIndex ushr 8).toByte()
        bound[3] = chunkIndex.toByte()
        associatedData.copyInto(bound, 4)
        return bound
    }

    public companion object {
        /** The label recorded in the manifest's encryption metadata. */
        public val LABEL: AssetEncryptionAlgorithm = AssetEncryptionAlgorithm("AES-256-GCM")

        /** AES-256 key length. */
        public const val KEY_BYTES: Int = 32

        /** GCM nonce length: 96 bits. */
        public const val NONCE_BYTES: Int = 12

        /** GCM tag length: 128 bits. */
        public const val TAG_BYTES: Int = 16
    }
}

// AES-256-GCM primitive: the only platform-specific part of AesGcmAssetChunkCipher.

/** Whether this platform can run AES-256-GCM. */
internal expect fun platformAesGcmSupported(): Boolean

/** Returns `ciphertext || tag`. */
internal expect fun platformAesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    plaintext: ByteArray,
): ByteArray

/**
 * Verifies and decrypts `ciphertext || tag`.
 *
 * @throws AssetChunkAuthenticationException if authentication fails.
 */
internal expect fun platformAesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    sealed: ByteArray,
): ByteArray
