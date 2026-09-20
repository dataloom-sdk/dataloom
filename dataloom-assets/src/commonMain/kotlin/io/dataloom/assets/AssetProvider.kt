package io.dataloom.assets

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult

/**
 * Storage limits a provider enforces on uploads (FR-ASSET-010). Supplied to
 * the provider as configuration — it is the provider that admits or rejects
 * an upload, not the client — and an upload that would exceed either limit is
 * rejected with [AssetErrorKind.QUOTA_EXCEEDED].
 *
 * @param maxAssetSizeBytes largest single asset accepted, or `null` for no limit.
 * @param maxTotalBytes largest total the provider stores across all committed
 *   assets and all in-flight upload reservations, or `null` for no limit.
 */
public data class AssetQuota(
    public val maxAssetSizeBytes: Long? = null,
    public val maxTotalBytes: Long? = null,
) {
    init {
        require(maxAssetSizeBytes == null || maxAssetSizeBytes >= 0) {
            "AssetQuota.maxAssetSizeBytes must not be negative, but was $maxAssetSizeBytes."
        }
        require(maxTotalBytes == null || maxTotalBytes >= 0) {
            "AssetQuota.maxTotalBytes must not be negative, but was $maxTotalBytes."
        }
    }

    public companion object {
        /** No limits. */
        public val UNLIMITED: AssetQuota = AssetQuota()
    }
}

/**
 * Request to open (or resume) an upload session on the provider.
 *
 * @param sessionId the caller-chosen session id; also the local
 *   [AssetTransferSession]'s id.
 * @param manifest the complete manifest of the asset being uploaded, including
 *   every chunk digest and the whole-object digest. The provider verifies
 *   chunks and the assembled object against it.
 */
public data class AssetUploadRequest(
    public val sessionId: AssetTransferSessionId,
    public val manifest: AssetManifest,
)

/**
 * The provider's view of an upload session: which chunks it has durably
 * committed. This is authoritative on resume — the client reconciles its own
 * record to it.
 */
public class AssetUploadStatus(
    public val sessionId: AssetTransferSessionId,
    committedChunks: Set<Int>,
) {
    /** Indices of chunks the provider has committed. */
    public val committedChunks: Set<Int> = committedChunks.toSet()

    override fun equals(other: Any?): Boolean =
        other is AssetUploadStatus && sessionId == other.sessionId && committedChunks == other.committedChunks

    override fun hashCode(): Int = 31 * sessionId.hashCode() + committedChunks.hashCode()

    override fun toString(): String =
        "AssetUploadStatus(sessionId=$sessionId, committedChunks=$committedChunks)"
}

/**
 * One chunk of an upload.
 *
 * @param bytes exactly the chunk's bytes: its size must equal the manifest
 *   descriptor's length. The provider must not retain this array after the
 *   call returns (the caller reuses buffers to keep memory bounded), so a
 *   provider that stores the chunk copies it.
 */
public class AssetChunkUpload(
    public val sessionId: AssetTransferSessionId,
    public val index: Int,
    public val bytes: ByteArray,
) {
    init {
        require(index >= 0) { "AssetChunkUpload.index must not be negative, but was $index." }
    }

    override fun toString(): String = "AssetChunkUpload(sessionId=$sessionId, index=$index, size=${bytes.size})"
}

/**
 * Provider SPI for asset storage/transfer: the remote (or reference) side of
 * chunked, resumable, integrity-verified upload and download.
 *
 * ## Contract (every provider must satisfy; see
 * `io.dataloom.assets.testkit.AssetProviderContractKit`)
 *
 * **Idempotency.** Every operation is safe under duplicate delivery, retry,
 * and restart:
 * - [openUpload] with an already-open `sessionId` and an *equal* manifest
 *   returns the current [AssetUploadStatus] (this is how resume works) and
 *   does not reserve quota twice; with a *different* manifest it fails with
 *   [AssetErrorKind.SESSION_CONFLICT]. Opening a *new* session for an
 *   `(assetId, version)` that is already committed fails with
 *   [AssetErrorKind.ASSET_VERSION_CONFLICT] (as does [completeUpload] if a
 *   concurrent session committed it first): a committed version is immutable.
 * - [uploadChunk] of an already-committed chunk succeeds without effect.
 * - [completeUpload] of an already-completed session returns the committed
 *   manifest.
 * - [abortUpload] of an unknown or already-aborted session succeeds; it never
 *   removes an already-committed asset.
 *
 * **Integrity.** [uploadChunk] must verify the bytes against the manifest
 * descriptor's digest *before* committing them, and reject a mismatch with
 * [AssetErrorKind.CHUNK_DIGEST_MISMATCH] leaving the chunk uncommitted.
 * [completeUpload] must verify the whole-object digest over the assembled
 * bytes in bounded memory (see `DataLoomIncrementalDigestCalculator`). An
 * asset is *exposed* — readable through [readManifest]/[readChunk] — only
 * after [completeUpload] succeeds; a failed verification discards the
 * session's chunks and releases its reservation, so a corrupted asset is
 * never visible.
 *
 * **Quota.** The provider enforces the [AssetQuota] it was configured with.
 * [openUpload] is the preflight check against the manifest's declared size
 * and reserves that many bytes; the reservation is released by
 * [abortUpload], by a failed verification, and converted to committed usage
 * by [completeUpload]. Because the whole size is reserved up front,
 * subsequent chunk uploads cannot overshoot the quota.
 *
 * **Bounded memory.** Chunk sizes are bounded by [chunkSizeBounds]; the
 * provider must not require more than one chunk of a caller's memory per
 * call.
 *
 * ## Not a `DataLoomProvider` yet
 *
 * `AssetProvider` deliberately does not extend
 * [io.dataloom.api.provider.DataLoomProvider] in this slice.
 * `ProviderType` has no asset category, and giving asset providers a
 * lifecycle (`initialize`/`health`/`close`) plus a registry/binding slot is
 * part of the later `DataLoomBuilder` wiring slice
 * (`docs/adr/ADR-0006-asset-transfer-and-streaming-digest.md`).
 *
 * Failures are reported as [ProviderOperationResult.Failure] carrying an
 * [AssetTransferError]; providers must not throw for expected failures.
 * Implementations must be thread-safe.
 */
public interface AssetProvider {

    /** Chunk sizes this provider accepts. */
    public val chunkSizeBounds: AssetChunkSizeBounds

    /** Opens a new upload session, or resumes one; see the class-level idempotency contract. */
    public suspend fun openUpload(request: AssetUploadRequest): ProviderOperationResult<AssetUploadStatus>

    /**
     * Verifies and commits one chunk. Chunks may arrive in any order and more
     * than once. Returns the updated status.
     */
    public suspend fun uploadChunk(request: AssetChunkUpload): ProviderOperationResult<AssetUploadStatus>

    /**
     * Verifies that every chunk is committed and that the assembled object
     * matches the manifest's whole-object digest, then atomically exposes the
     * asset. Returns the committed manifest.
     */
    public suspend fun completeUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<AssetManifest>

    /** Discards an upload session and releases its quota reservation. */
    public suspend fun abortUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<Unit>

    /**
     * Returns the committed manifest for [assetId] at [version], or the
     * highest committed version when [version] is `null`. Fails with
     * [AssetErrorKind.ASSET_NOT_FOUND] if there is none.
     */
    public suspend fun readManifest(assetId: AssetId, version: Long? = null): ProviderOperationResult<AssetManifest>

    /**
     * Returns the bytes of chunk [index] of a committed asset. The result's
     * size equals the manifest descriptor's length.
     */
    public suspend fun readChunk(assetId: AssetId, version: Long, index: Int): ProviderOperationResult<ByteArray>
}
