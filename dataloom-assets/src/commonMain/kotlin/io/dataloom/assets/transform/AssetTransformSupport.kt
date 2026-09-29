package io.dataloom.assets.transform

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.security.KeyReference

/**
 * Thrown by a transform that this platform cannot run (for example
 * [AesGcmAssetChunkCipher] on Apple platforms, where Kotlin/Native exposes no
 * AES-GCM binding). Typed rather than a silent degradation: the engine maps it
 * to [io.dataloom.assets.AssetErrorKind.TRANSFORM_UNSUPPORTED]. Messages never
 * contain key or content bytes.
 */
public class AssetTransformUnsupportedException(message: String) : RuntimeException(message)

/**
 * Thrown by a cipher when the host's [AssetKeyResolver] could not supply the
 * key (it threw, or the keystore is momentarily unavailable). The engine maps
 * it to the resumable [io.dataloom.assets.AssetErrorKind.ENCRYPTION_KEY_UNAVAILABLE].
 */
public class AssetKeyUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown by a cipher when the host supplied key material of the wrong shape
 * (for example not 32 bytes for AES-256). The engine maps it to the terminal
 * [io.dataloom.assets.AssetErrorKind.ENCRYPTION_KEY_INVALID].
 */
public class AssetKeyInvalidException(message: String) : RuntimeException(message)

/**
 * Host-supplied key lookup for [AssetChunkCipher] implementations.
 *
 * DataLoom never generates, stores, caches, rotates or persists key material
 * (see `integrity-and-key-references.md`). A [KeyReference] is an opaque label
 * the host assigned; the host resolves it to key bytes here, typically from a
 * platform keystore or KMS, at the moment a chunk is sealed or opened.
 *
 * Contract:
 * - Return the raw key bytes. AES-256-GCM needs exactly 32.
 * - The SDK uses the array for the duration of one chunk operation, does not
 *   retain it and does not modify it; the host stays the owner of the array.
 * - Throw (any non-cancellation exception) if the key is not available right
 *   now; the transfer is then left resumable.
 * - The same reference must resolve to the same key for the life of an asset
 *   version, otherwise its chunks cannot be opened.
 */
public fun interface AssetKeyResolver {
    /** Returns the key named by [keyReference]. */
    public suspend fun resolve(keyReference: KeyReference): ByteArray
}

/**
 * The transforms a transfer engine applies to every chunk of assets it
 * uploads: compress, then encrypt (ADR-0014). On download the manifest decides
 * which transforms apply and the engine reverses them from what is configured
 * here.
 *
 * Both parts are optional and independent. [NONE] transfers chunks as-is.
 *
 * @param compressor per-chunk compression, or `null` for none.
 * @param cipher per-chunk authenticated encryption, or `null` for none.
 * @param keyReference names the key [cipher] uses for uploads; required
 *   exactly when [cipher] is set. Recorded in the manifest so a downloader
 *   knows which key to resolve. DataLoom never sees the key itself.
 */
public class AssetTransferTransforms(
    public val compressor: AssetCompressor? = null,
    public val cipher: AssetChunkCipher? = null,
    public val keyReference: KeyReference? = null,
) {
    init {
        require((cipher == null) == (keyReference == null)) {
            "AssetTransferTransforms needs a keyReference exactly when a cipher is configured."
        }
        require(cipher !is IdentityAssetCipher) {
            "IdentityAssetCipher provides no confidentiality and cannot be used as an encryption transform."
        }
    }

    /** `true` when neither compression nor encryption is configured. */
    public val isEmpty: Boolean get() = compressor == null && cipher == null

    override fun toString(): String =
        "AssetTransferTransforms(compression=${compressor?.algorithm}, encryption=${cipher?.algorithm})"

    public companion object {
        /** No transforms: chunks are transferred exactly as read. */
        public val NONE: AssetTransferTransforms = AssetTransferTransforms()
    }
}

/**
 * Wire form of chunks of assets that are compressed and/or encrypted.
 *
 * An untransformed asset transfers its chunks byte for byte, as the manifest
 * describes them. When [AssetManifest.compression] or
 * [AssetManifest.encryption] is present, every chunk travels as a *frame*
 * (see ADR-0014) whose length differs from the manifest descriptor's logical
 * length, and the manifest's digests describe the logical bytes, so a
 * provider cannot verify them.
 */
public object AssetWireFormat {

    /** The frame format version this SDK writes and accepts. */
    public const val FRAME_VERSION: Int = 1

    /**
     * Upper bound on how many bytes a frame may add to its chunk's logical
     * length. The shipped algorithms add at most 31 (3 header + 12 nonce + 16
     * tag; compression never enlarges a chunk because an incompressible chunk
     * is stored raw). A provider uses this to reject absurd chunks.
     */
    public const val MAX_FRAME_OVERHEAD_BYTES: Int = 256

    /** `true` if chunks of [manifest] travel as transform frames rather than as the raw logical bytes. */
    public fun isTransformed(manifest: AssetManifest): Boolean =
        manifest.compression != null || manifest.encryption != null
}
