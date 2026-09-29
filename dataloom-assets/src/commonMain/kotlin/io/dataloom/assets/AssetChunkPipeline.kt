package io.dataloom.assets

import io.dataloom.api.asset.AssetCompressionMetadata
import io.dataloom.api.asset.AssetEncryptionMetadata
import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.security.KeyReference
import io.dataloom.assets.transform.AssetChunkAuthenticationException
import io.dataloom.assets.transform.AssetChunkCipher
import io.dataloom.assets.transform.AssetCompressor
import io.dataloom.assets.transform.AssetKeyInvalidException
import io.dataloom.assets.transform.AssetKeyUnavailableException
import io.dataloom.assets.transform.AssetSealedChunk
import io.dataloom.assets.transform.AssetTransferTransforms
import io.dataloom.assets.transform.AssetTransformUnsupportedException
import io.dataloom.assets.transform.AssetWireFormat
import kotlinx.coroutines.CancellationException

/** A transform frame could not be parsed or contradicts the manifest. Mapped to [AssetErrorKind.TRANSFORM_FRAME_INVALID]. */
internal class AssetFrameInvalidException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Turns one logical chunk into the frame a provider stores, and back
 * (ADR-0014). Upload is compress-then-encrypt, download the reverse.
 *
 * ## Frame layout (version 1)
 *
 * ```
 * byte 0        frame version (1)
 * byte 1        flags: bit 0 = payload is DEFLATE-compressed, bit 1 = payload is sealed
 * byte 2        nonce length N (0 unless sealed)
 * bytes 3..3+N  nonce
 * rest          payload: the (compressed) chunk, or ciphertext || tag when sealed
 * ```
 *
 * A chunk that does not shrink under compression is stored raw with bit 0
 * clear, so a frame never exceeds its logical length by more than the header,
 * nonce and tag. The flags a reader sees must agree with the manifest (a sealed
 * asset never accepts an unsealed frame, so a provider cannot downgrade it).
 *
 * ## Associated data
 *
 * `version, flags, chunk index, chunk count, asset version, asset size, asset id`
 * are bound as the cipher's associated data, so flipping a flag or moving a
 * chunk anywhere else (position, asset, version) fails authentication. The
 * transfer session id is deliberately not bound: uploader and downloader use
 * different session ids, so it could never be reproduced on download (ADR-0014).
 */
internal class AssetChunkPipeline private constructor(
    private val manifest: AssetManifest,
    private val compressor: AssetCompressor?,
    private val cipher: AssetChunkCipher?,
    private val keyReference: KeyReference?,
) {

    /** Logical chunk bytes to the frame the provider stores. */
    suspend fun toWire(index: Int, logical: ByteArray): ByteArray {
        var flags = 0
        var payload = logical
        if (compressor != null) {
            val compressed = compressor.compress(logical)
            if (compressed.size < logical.size) {
                payload = compressed
                flags = flags or FLAG_COMPRESSED
            }
        }
        if (cipher == null) return frame(flags, ByteArray(0), payload)
        flags = flags or FLAG_SEALED
        val sealed = cipher.seal(checkNotNull(keyReference), index, payload, associatedData(index, flags))
        return frame(flags, sealed.copyNonce(), sealed.copyCiphertext())
    }

    /**
     * The logical chunk of exactly [expectedLength] bytes from a stored frame.
     *
     * @throws AssetFrameInvalidException for a malformed frame or one that contradicts the manifest.
     * @throws AssetChunkAuthenticationException if the sealed payload does not authenticate.
     */
    suspend fun fromWire(index: Int, wire: ByteArray, expectedLength: Int): ByteArray {
        if (wire.size < HEADER_BYTES) throw AssetFrameInvalidException("Frame is truncated.")
        if ((wire[0].toInt() and 0xFF) != AssetWireFormat.FRAME_VERSION) {
            throw AssetFrameInvalidException("Unsupported frame version.")
        }
        val flags = wire[1].toInt() and 0xFF
        if (flags and (FLAG_COMPRESSED or FLAG_SEALED).inv() != 0) throw AssetFrameInvalidException("Unknown frame flags.")
        val compressed = flags and FLAG_COMPRESSED != 0
        val sealed = flags and FLAG_SEALED != 0
        if (sealed != (cipher != null)) throw AssetFrameInvalidException("Frame encryption contradicts the manifest.")
        if (compressed && compressor == null) throw AssetFrameInvalidException("Frame compression contradicts the manifest.")
        val nonceLength = wire[2].toInt() and 0xFF
        if (!sealed && nonceLength != 0) throw AssetFrameInvalidException("Unsealed frame carries a nonce.")
        if (wire.size < HEADER_BYTES + nonceLength) throw AssetFrameInvalidException("Frame is truncated.")
        val payload = wire.copyOfRange(HEADER_BYTES + nonceLength, wire.size)

        val plain = if (cipher != null) {
            val nonce = wire.copyOfRange(HEADER_BYTES, HEADER_BYTES + nonceLength)
            cipher.open(checkNotNull(keyReference), index, AssetSealedChunk(payload, nonce), associatedData(index, flags))
        } else {
            payload
        }
        val logical = if (compressed) {
            try {
                checkNotNull(compressor).decompress(plain, expectedLength)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw AssetFrameInvalidException("Compressed payload is invalid.", e)
            }
        } else {
            plain
        }
        if (logical.size != expectedLength) throw AssetFrameInvalidException("Frame length contradicts the manifest.")
        return logical
    }

    private fun frame(flags: Int, nonce: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + nonce.size + payload.size)
        out[0] = AssetWireFormat.FRAME_VERSION.toByte()
        out[1] = flags.toByte()
        out[2] = nonce.size.toByte()
        nonce.copyInto(out, HEADER_BYTES)
        payload.copyInto(out, HEADER_BYTES + nonce.size)
        return out
    }

    private fun associatedData(index: Int, flags: Int): ByteArray {
        val id = manifest.assetId.value.encodeToByteArray()
        val out = ByteArray(2 + 4 + 4 + 8 + 8 + id.size)
        out[0] = AssetWireFormat.FRAME_VERSION.toByte()
        out[1] = flags.toByte()
        var at = 2
        at = putInt(out, at, index)
        at = putInt(out, at, manifest.chunkLayout.chunkCount)
        at = putLong(out, at, manifest.version)
        at = putLong(out, at, manifest.sizeBytes)
        id.copyInto(out, at)
        return out
    }

    private fun putInt(out: ByteArray, at: Int, value: Int): Int {
        for (i in 0 until 4) out[at + i] = (value ushr (24 - 8 * i)).toByte()
        return at + 4
    }

    private fun putLong(out: ByteArray, at: Int, value: Long): Int {
        for (i in 0 until 8) out[at + i] = (value ushr (56 - 8 * i)).toByte()
        return at + 8
    }

    companion object {
        private const val FLAG_COMPRESSED = 1
        private const val FLAG_SEALED = 2
        private const val HEADER_BYTES = 3

        /** Why the configured [transforms] cannot run on this platform, or `null` if they can. */
        fun availability(transforms: AssetTransferTransforms): AssetTransferError? =
            unsupported(transforms.compressor?.isSupported, transforms.cipher?.isSupported)?.error

        /** Upload: [manifest] (built over the logical bytes) annotated with the configured transforms. */
        fun describe(manifest: AssetManifest, transforms: AssetTransferTransforms): AssetManifest =
            manifest.copy(
                compression = transforms.compressor?.let {
                    AssetCompressionMetadata(it.algorithm, uncompressedSizeBytes = manifest.sizeBytes)
                },
                // Nonces are per chunk and live in each frame, so the manifest records none.
                encryption = transforms.cipher?.let {
                    AssetEncryptionMetadata(it.algorithm, checkNotNull(transforms.keyReference), ByteArray(0))
                },
            )

        /**
         * The pipeline for an existing [manifest]. For an upload the configured
         * transforms must equal what the manifest records (a session is never
         * silently resumed with different transforms, in particular never
         * without the encryption the host now asks for). For a download the
         * manifest is authoritative and the configured transforms must be able to
         * reverse it.
         */
        fun forManifest(manifest: AssetManifest, transforms: AssetTransferTransforms, upload: Boolean): Resolution {
            val wantCompression = manifest.compression?.algorithm
            val wantEncryption = manifest.encryption
            val compressor = transforms.compressor
            val cipher = transforms.cipher
            if (upload) {
                if (wantCompression != compressor?.algorithm) return Resolution.Refused(mismatch())
                if (wantEncryption?.algorithm != cipher?.algorithm) return Resolution.Refused(mismatch())
                if (wantEncryption != null && wantEncryption.keyReference != transforms.keyReference) {
                    return Resolution.Refused(mismatch())
                }
            } else {
                if (wantCompression != null && wantCompression != compressor?.algorithm) {
                    return Resolution.Refused(mismatch())
                }
                if (wantEncryption != null && wantEncryption.algorithm != cipher?.algorithm) {
                    return Resolution.Refused(mismatch())
                }
            }
            val usedCompressor = if (wantCompression != null) compressor else null
            val usedCipher = if (wantEncryption != null) cipher else null
            unsupported(usedCompressor?.isSupported, usedCipher?.isSupported)?.let { return it }
            val pipeline = if (usedCompressor == null && usedCipher == null) {
                null
            } else {
                AssetChunkPipeline(manifest, usedCompressor, usedCipher, wantEncryption?.keyReference)
            }
            return Resolution.Ready(pipeline)
        }

        private fun unsupported(compressor: Boolean?, cipher: Boolean?): Resolution.Refused? =
            if (compressor == false || cipher == false) {
                Resolution.Refused(
                    AssetTransferError(AssetErrorKind.TRANSFORM_UNSUPPORTED, "A configured transform is not available on this platform."),
                )
            } else {
                null
            }

        private fun mismatch() = AssetTransferError(
            AssetErrorKind.TRANSFORM_UNSUPPORTED,
            "Configured transforms do not match the asset's manifest.",
        )
    }

    sealed interface Resolution {
        /** [pipeline] is `null` when the manifest has no transform: chunks travel as-is. */
        class Ready(val pipeline: AssetChunkPipeline?) : Resolution
        class Refused(val error: AssetTransferError) : Resolution
    }
}

/** Maps a transform failure to the sanitised [AssetTransferError] the engine reports, or `null` if [e] is not one. */
internal fun transformError(e: Throwable): AssetTransferError? = when (e) {
    is AssetTransformUnsupportedException ->
        AssetTransferError(AssetErrorKind.TRANSFORM_UNSUPPORTED, "A configured transform is not available on this platform.", e)
    is AssetKeyUnavailableException ->
        AssetTransferError(AssetErrorKind.ENCRYPTION_KEY_UNAVAILABLE, "The encryption key is not available.", e)
    is AssetKeyInvalidException ->
        AssetTransferError(AssetErrorKind.ENCRYPTION_KEY_INVALID, "The encryption key is not valid for the cipher.", e)
    is AssetChunkAuthenticationException ->
        AssetTransferError(AssetErrorKind.CHUNK_AUTHENTICATION_FAILED, "Downloaded chunk failed authentication.", e)
    is AssetFrameInvalidException ->
        AssetTransferError(AssetErrorKind.TRANSFORM_FRAME_INVALID, "Downloaded chunk frame is invalid.", e)
    else -> null
}
