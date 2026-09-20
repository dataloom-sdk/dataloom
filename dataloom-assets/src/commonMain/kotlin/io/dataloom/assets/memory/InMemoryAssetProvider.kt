package io.dataloom.assets.memory

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.assets.AssetChunkSizeBounds
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetQuota
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.AssetUploadStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reference [AssetProvider] holding everything in memory. It defines the
 * behavioural contract other providers must match (see
 * `io.dataloom.assets.testkit.AssetProviderContractKit`, which this class
 * passes) and backs the transfer engine's tests. It is **not** a bounded-memory
 * store — it retains whole assets — so it is for tests, samples and
 * development, never production.
 *
 * All state is guarded by one mutex, so it is safe to share across
 * coroutines.
 *
 * @param digests digest calculator used to verify chunks and assembled objects;
 *   must support incremental hashing.
 * @param quota limits enforced on uploads; see [AssetQuota].
 * @param chunkSizeBounds accepted chunk sizes. Defaults to `1..64 MiB` so tests
 *   can use tiny chunks; a real provider would use realistic bounds.
 */
public class InMemoryAssetProvider(
    private val digests: DataLoomIncrementalDigestCalculator,
    private val quota: AssetQuota = AssetQuota.UNLIMITED,
    override val chunkSizeBounds: AssetChunkSizeBounds = AssetChunkSizeBounds(1, 64 * 1024 * 1024),
) : AssetProvider {

    private class Upload(val manifest: AssetManifest) {
        val chunks: Array<ByteArray?> = arrayOfNulls(manifest.chunkLayout.chunkCount)
        var completed: Boolean = false

        fun committedIndices(): Set<Int> = chunks.indices.filterTo(LinkedHashSet()) { chunks[it] != null }
    }

    private class StoredAsset(val manifest: AssetManifest, val chunks: List<ByteArray>)

    private val mutex = Mutex()
    private val uploads = HashMap<AssetTransferSessionId, Upload>()
    private val committed = HashMap<AssetId, MutableMap<Long, StoredAsset>>()

    override suspend fun openUpload(request: AssetUploadRequest): ProviderOperationResult<AssetUploadStatus> =
        mutex.withLock {
            val manifest = request.manifest
            val existing = uploads[request.sessionId]
            if (existing != null) {
                return@withLock if (existing.manifest == manifest) {
                    ok(status(request.sessionId, existing))
                } else {
                    fail(AssetErrorKind.SESSION_CONFLICT, "Session was opened with a different manifest.")
                }
            }
            if (committed[manifest.assetId]?.containsKey(manifest.version) == true) {
                return@withLock fail(AssetErrorKind.ASSET_VERSION_CONFLICT, "Asset version is already committed.")
            }
            chunkSizeViolation(manifest)?.let { return@withLock ProviderOperationResult.Failure(it) }
            quotaViolation(manifest.sizeBytes)?.let { return@withLock ProviderOperationResult.Failure(it) }
            val upload = Upload(manifest)
            uploads[request.sessionId] = upload
            ok(status(request.sessionId, upload))
        }

    override suspend fun uploadChunk(request: AssetChunkUpload): ProviderOperationResult<AssetUploadStatus> =
        mutex.withLock {
            val upload = uploads[request.sessionId]
                ?: return@withLock fail(AssetErrorKind.SESSION_NOT_FOUND, "Upload session not found.")
            val descriptor = upload.manifest.chunkLayout.chunks.getOrNull(request.index)
                ?: return@withLock fail(AssetErrorKind.CHUNK_OUT_OF_RANGE, "Chunk index is outside the manifest.")
            // A completed or already-committed chunk is an idempotent redelivery.
            if (upload.completed || upload.chunks[request.index] != null) {
                return@withLock ok(status(request.sessionId, upload))
            }
            if (request.bytes.size.toLong() != descriptor.lengthBytes) {
                return@withLock fail(AssetErrorKind.CHUNK_LENGTH_MISMATCH, "Chunk length differs from the manifest.")
            }
            if (!(descriptor.checksum contentEquals digests.digest(descriptor.checksum.algorithm, request.bytes))) {
                return@withLock fail(AssetErrorKind.CHUNK_DIGEST_MISMATCH, "Chunk digest differs from the manifest.")
            }
            upload.chunks[request.index] = request.bytes.copyOf()
            ok(status(request.sessionId, upload))
        }

    override suspend fun completeUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<AssetManifest> =
        mutex.withLock {
            val upload = uploads[sessionId]
                ?: return@withLock fail(AssetErrorKind.SESSION_NOT_FOUND, "Upload session not found.")
            if (upload.completed) return@withLock ok(upload.manifest)
            val chunks = upload.chunks.map { it }
            if (chunks.any { it == null }) {
                return@withLock fail(AssetErrorKind.INCOMPLETE_UPLOAD, "Not every chunk is committed.")
            }
            val manifest = upload.manifest
            val wholeDigest = digests.newAccumulator(manifest.checksum.algorithm).use { accumulator ->
                chunks.forEach { accumulator.update(it!!) }
                accumulator.finish()
            }
            if (!(manifest.checksum contentEquals wholeDigest)) {
                // Never expose a corrupted asset: drop the session and its reservation.
                uploads.remove(sessionId)
                return@withLock fail(AssetErrorKind.OBJECT_DIGEST_MISMATCH, "Assembled object digest differs from the manifest.")
            }
            val versions = committed.getOrPut(manifest.assetId) { HashMap() }
            if (versions.containsKey(manifest.version)) {
                uploads.remove(sessionId)
                return@withLock fail(AssetErrorKind.ASSET_VERSION_CONFLICT, "Asset version is already committed.")
            }
            versions[manifest.version] = StoredAsset(manifest, chunks.map { it!! })
            upload.completed = true
            ok(manifest)
        }

    override suspend fun abortUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<Unit> =
        mutex.withLock {
            // A completed session's asset is committed; aborting must never remove it.
            if (uploads[sessionId]?.completed == false) uploads.remove(sessionId)
            ok(Unit)
        }

    override suspend fun readManifest(assetId: AssetId, version: Long?): ProviderOperationResult<AssetManifest> =
        mutex.withLock {
            val versions = committed[assetId]
            val stored = if (version == null) versions?.maxByOrNull { it.key }?.value else versions?.get(version)
            stored?.let { ok(it.manifest) } ?: fail(AssetErrorKind.ASSET_NOT_FOUND, "Asset is not committed.")
        }

    override suspend fun readChunk(assetId: AssetId, version: Long, index: Int): ProviderOperationResult<ByteArray> =
        mutex.withLock {
            val stored = committed[assetId]?.get(version)
                ?: return@withLock fail(AssetErrorKind.ASSET_NOT_FOUND, "Asset is not committed.")
            val chunk = stored.chunks.getOrNull(index)
                ?: return@withLock fail(AssetErrorKind.CHUNK_OUT_OF_RANGE, "Chunk index is outside the manifest.")
            ok(chunk.copyOf())
        }

    /** Bytes currently reserved by in-flight (not yet completed) uploads. */
    private fun reservedBytes(): Long = uploads.values.filter { !it.completed }.sumOf { it.manifest.sizeBytes }

    private fun committedBytes(): Long = committed.values.sumOf { versions -> versions.values.sumOf { it.manifest.sizeBytes } }

    private fun quotaViolation(sizeBytes: Long): AssetTransferError? {
        val maxAsset = quota.maxAssetSizeBytes
        if (maxAsset != null && sizeBytes > maxAsset) {
            return AssetTransferError(AssetErrorKind.QUOTA_EXCEEDED, "Asset exceeds the maximum asset size.")
        }
        val maxTotal = quota.maxTotalBytes
        if (maxTotal != null && committedBytes() + reservedBytes() + sizeBytes > maxTotal) {
            return AssetTransferError(AssetErrorKind.QUOTA_EXCEEDED, "Upload would exceed the total storage quota.")
        }
        return null
    }

    private fun chunkSizeViolation(manifest: AssetManifest): AssetTransferError? {
        val chunks = manifest.chunkLayout.chunks
        val outOfBounds = chunks.any { it.lengthBytes > chunkSizeBounds.maxBytes } ||
            chunks.dropLast(1).any { it.lengthBytes < chunkSizeBounds.minBytes }
        return if (outOfBounds) {
            AssetTransferError(AssetErrorKind.PROVIDER_REJECTED, "Chunk sizes are outside the provider's bounds.")
        } else {
            null
        }
    }

    private fun status(sessionId: AssetTransferSessionId, upload: Upload) =
        AssetUploadStatus(sessionId, upload.committedIndices())

    private fun <T> ok(value: T): ProviderOperationResult<T> = ProviderOperationResult.Success(value)

    private fun <T> fail(kind: AssetErrorKind, message: String): ProviderOperationResult<T> =
        ProviderOperationResult.Failure(AssetTransferError(kind, message))
}
