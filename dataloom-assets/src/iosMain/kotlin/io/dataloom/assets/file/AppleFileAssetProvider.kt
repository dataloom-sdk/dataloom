@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.assets.file

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
import io.dataloom.assets.transform.AssetWireFormat
import kotlinx.cinterop.convert
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Real filesystem-backed [AssetProvider] reference implementation — the
 * Apple/POSIX counterpart of the JVM's `FileAssetProvider` (FR-ASSET-009):
 * behaviourally identical (it passes the same
 * [io.dataloom.assets.testkit.AssetProviderContractKit]), built on POSIX file
 * descriptors ([AppleFileAssetIo]) instead of `java.nio.file`/`RandomAccessFile`.
 * See the JVM class's KDoc for the full rationale (layout, atomic promotion,
 * cleanup); this class mirrors its structure and error semantics exactly.
 *
 * ## Layout
 *
 * ```
 * baseDirectory/uploads/<safe sessionId>/chunk-<index>            in-flight; never exposed
 * baseDirectory/committed/<safe assetId>/<version>/asset.bin      exposed only after completeUpload
 * ```
 *
 * Apple/iOS only (`iosMain`). Compile-verified only; POSIX calls cannot run
 * on a Windows host — see the module's `iosTest` sources and the PR
 * description for exactly what was and was not verified.
 *
 * @param baseDirectory absolute POSIX path of the root directory this
 *   provider owns; created if missing. Two instances must not share one
 *   [baseDirectory] concurrently.
 * @param digests digest calculator used to verify chunks and assembled
 *   objects; must support incremental hashing.
 * @param quota limits enforced on uploads; see [AssetQuota].
 * @param chunkSizeBounds accepted chunk sizes. Defaults to `1..64 MiB`,
 *   matching [io.dataloom.assets.memory.InMemoryAssetProvider].
 * @param readBufferBytes buffer size used while assembling a completed
 *   upload; the memory bound of that operation.
 */
public class AppleFileAssetProvider(
    private val baseDirectory: String,
    private val digests: DataLoomIncrementalDigestCalculator,
    private val quota: AssetQuota = AssetQuota.UNLIMITED,
    override val chunkSizeBounds: AssetChunkSizeBounds = AssetChunkSizeBounds(1, 64 * 1024 * 1024),
    private val readBufferBytes: Int = 64 * 1024,
) : AssetProvider {

    private class Upload(val manifest: AssetManifest, val directory: String) {
        var completed: Boolean = false

        fun committedIndices(): Set<Int> {
            val count = manifest.chunkLayout.chunkCount
            return if (completed) {
                (0 until count).toCollection(LinkedHashSet())
            } else {
                (0 until count).filterTo(LinkedHashSet()) { AppleFileAssetIo.exists(chunkFile(directory, it)) }
            }
        }
    }

    private class StoredAsset(
        val manifest: AssetManifest,
        val assetFile: String,
        val chunkOffsets: LongArray,
        val chunkLengths: IntArray,
    )

    private val mutex = Mutex()
    private val uploads = HashMap<AssetTransferSessionId, Upload>()
    private val committed = HashMap<AssetId, MutableMap<Long, StoredAsset>>()

    private val uploadsRoot: String = "${baseDirectory.trimEnd('/')}/uploads"
    private val committedRoot: String = "${baseDirectory.trimEnd('/')}/committed"

    override suspend fun openUpload(request: AssetUploadRequest): ProviderOperationResult<AssetUploadStatus> =
        mutex.withLock {
            try {
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
                val directory = sessionDirectory(request.sessionId)
                AppleFileAssetIo.ensureDirectory(directory)
                AppleFileAssetIo.touch(directory)
                val upload = Upload(manifest, directory)
                uploads[request.sessionId] = upload
                ok(status(request.sessionId, upload))
            } catch (_: AppleFileAssetIoException) {
                fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
            }
        }

    override suspend fun uploadChunk(request: AssetChunkUpload): ProviderOperationResult<AssetUploadStatus> =
        mutex.withLock {
            try {
                val upload = uploads[request.sessionId]
                    ?: return@withLock fail(AssetErrorKind.SESSION_NOT_FOUND, "Upload session not found.")
                val descriptor = upload.manifest.chunkLayout.chunks.getOrNull(request.index)
                    ?: return@withLock fail(AssetErrorKind.CHUNK_OUT_OF_RANGE, "Chunk index is outside the manifest.")
                val chunkPath = chunkFile(upload.directory, request.index)
                if (upload.completed || AppleFileAssetIo.exists(chunkPath)) {
                    return@withLock ok(status(request.sessionId, upload))
                }
                if (AssetWireFormat.isTransformed(upload.manifest)) {
                    // Opaque transform frame: the manifest's length and digest describe the logical
                    // bytes, which storage of sealed bytes cannot check (ADR-0014, D24).
                    val size = request.bytes.size.toLong()
                    if (size < 1 || size > descriptor.lengthBytes + AssetWireFormat.MAX_FRAME_OVERHEAD_BYTES) {
                        return@withLock fail(AssetErrorKind.CHUNK_LENGTH_MISMATCH, "Chunk frame length is implausible for the manifest.")
                    }
                } else {
                    if (request.bytes.size.toLong() != descriptor.lengthBytes) {
                        return@withLock fail(AssetErrorKind.CHUNK_LENGTH_MISMATCH, "Chunk length differs from the manifest.")
                    }
                    if (!(descriptor.checksum contentEquals digests.digest(descriptor.checksum.algorithm, request.bytes))) {
                        return@withLock fail(AssetErrorKind.CHUNK_DIGEST_MISMATCH, "Chunk digest differs from the manifest.")
                    }
                }
                writeChunkFileAtomically(chunkPath, request.bytes)
                AppleFileAssetIo.touch(upload.directory)
                ok(status(request.sessionId, upload))
            } catch (_: AppleFileAssetIoException) {
                fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
            }
        }

    override suspend fun completeUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<AssetManifest> =
        mutex.withLock {
            try {
                val upload = uploads[sessionId]
                    ?: return@withLock fail(AssetErrorKind.SESSION_NOT_FOUND, "Upload session not found.")
                if (upload.completed) return@withLock ok(upload.manifest)
                val manifest = upload.manifest
                val chunkCount = manifest.chunkLayout.chunkCount
                val chunkFiles = (0 until chunkCount).map { chunkFile(upload.directory, it) }
                if (chunkFiles.any { !AppleFileAssetIo.exists(it) }) {
                    return@withLock fail(AssetErrorKind.INCOMPLETE_UPLOAD, "Not every chunk is committed.")
                }

                // A transformed asset's stored bytes are frames, not the logical bytes the
                // whole-object digest covers, so only the client can verify it (ADR-0014, D24).
                val transformed = AssetWireFormat.isTransformed(manifest)
                val tempAssembled = AppleFileAssetIo.createSecureTempFile(uploadsRoot, "assemble-", ".tmp")
                val offsets = LongArray(chunkCount)
                val lengths = IntArray(chunkCount)
                val accumulator = if (!transformed) digests.newAccumulator(manifest.checksum.algorithm) else null
                try {
                    val buffer = ByteArray(readBufferBytes)
                    val assembledFd = AppleFileAssetIo.openReadWrite(tempAssembled)
                    try {
                        var writeOffset = 0L
                        for (index in 0 until chunkCount) {
                            offsets[index] = writeOffset
                            val chunkFd = AppleFileAssetIo.openReadOnly(chunkFiles[index])
                            var length = 0
                            try {
                                val chunkSize = AppleFileAssetIo.sizeOfDescriptor(chunkFd)
                                var readOffset = 0L
                                while (readOffset < chunkSize) {
                                    val want = minOf(buffer.size.toLong(), chunkSize - readOffset).toInt()
                                    AppleFileAssetIo.readFully(chunkFd, readOffset, buffer, 0, want)
                                    AppleFileAssetIo.writeFully(assembledFd, writeOffset, buffer, 0, want)
                                    accumulator?.update(buffer, 0, want)
                                    readOffset += want
                                    writeOffset += want
                                    length += want
                                }
                            } finally {
                                AppleFileAssetIo.closeDescriptorQuietly(chunkFd)
                            }
                            lengths[index] = length
                        }
                    } finally {
                        AppleFileAssetIo.closeDescriptorQuietly(assembledFd)
                    }
                } catch (e: Exception) {
                    accumulator?.close()
                    AppleFileAssetIo.deleteQuietly(tempAssembled)
                    throw e
                }

                if (accumulator != null) {
                    val wholeDigest = accumulator.finish()
                    if (!(manifest.checksum contentEquals wholeDigest)) {
                        // Never expose a corrupted asset: drop the session, its reservation and its temp files.
                        uploads.remove(sessionId)
                        AppleFileAssetIo.deleteQuietly(tempAssembled)
                        AppleFileAssetIo.deleteDirectoryQuietly(upload.directory)
                        return@withLock fail(AssetErrorKind.OBJECT_DIGEST_MISMATCH, "Assembled object digest differs from the manifest.")
                    }
                }

                val versions = committed.getOrPut(manifest.assetId) { HashMap() }
                if (versions.containsKey(manifest.version)) {
                    AppleFileAssetIo.deleteQuietly(tempAssembled)
                    uploads.remove(sessionId)
                    return@withLock fail(AssetErrorKind.ASSET_VERSION_CONFLICT, "Asset version is already committed.")
                }
                val finalAsset = assetFile(manifest.assetId, manifest.version)
                AppleFileAssetIo.promoteAtomically(tempAssembled, finalAsset)
                versions[manifest.version] = StoredAsset(manifest, finalAsset, offsets, lengths)
                upload.completed = true
                // The chunk files' bytes now live in the committed asset file; the staging
                // directory is no longer needed (eager cleanup on this terminal transition).
                AppleFileAssetIo.deleteDirectoryQuietly(upload.directory)
                ok(manifest)
            } catch (_: AppleFileAssetIoException) {
                fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
            }
        }

    override suspend fun abortUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<Unit> =
        mutex.withLock {
            // A completed session's asset is committed; aborting must never remove it or its bytes.
            val upload = uploads[sessionId]
            if (upload != null && !upload.completed) {
                uploads.remove(sessionId)
                AppleFileAssetIo.deleteDirectoryQuietly(upload.directory)
            }
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
            try {
                val stored = committed[assetId]?.get(version)
                    ?: return@withLock fail(AssetErrorKind.ASSET_NOT_FOUND, "Asset is not committed.")
                if (index !in stored.chunkOffsets.indices) {
                    return@withLock fail(AssetErrorKind.CHUNK_OUT_OF_RANGE, "Chunk index is outside the manifest.")
                }
                val bytes = ByteArray(stored.chunkLengths[index])
                val fd = AppleFileAssetIo.openReadOnly(stored.assetFile)
                try {
                    AppleFileAssetIo.readFully(fd, stored.chunkOffsets[index], bytes, 0, bytes.size)
                } finally {
                    AppleFileAssetIo.closeDescriptorQuietly(fd)
                }
                ok(bytes)
            } catch (_: AppleFileAssetIoException) {
                fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
            }
        }

    /**
     * Deletes upload directories under `baseDirectory/uploads` whose last
     * filesystem modification is older than [olderThan] — the bounded,
     * host-driven backstop for a session that was abandoned outright,
     * exactly mirroring the JVM implementation's `sweepAbandonedUploads`.
     *
     * @return the number of upload directories removed.
     */
    public suspend fun sweepAbandonedUploads(olderThan: Duration): Int = mutex.withLock {
        if (!AppleFileAssetIo.exists(uploadsRoot)) return@withLock 0
        val cutoffMillis = currentTimeMillis() - olderThan.inWholeMilliseconds
        var removed = 0
        for (entry in AppleFileAssetIo.listImmediateChildren(uploadsRoot)) {
            try {
                if (!AppleFileAssetIo.isDirectory(entry)) continue
                if (AppleFileAssetIo.lastModifiedEpochMillis(entry) <= cutoffMillis) {
                    AppleFileAssetIo.deleteDirectoryQuietly(entry)
                    val iterator = uploads.entries.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().value.directory == entry) iterator.remove()
                    }
                    removed++
                }
            } catch (_: AppleFileAssetIoException) {
                // Best-effort per entry: a concurrent abort may have already removed it.
            }
        }
        removed
    }

    private fun sessionDirectory(sessionId: AssetTransferSessionId): String =
        "$uploadsRoot/${AppleFileAssetIo.safeName(sessionId.value)}"

    private fun assetFile(assetId: AssetId, version: Long): String =
        "$committedRoot/${AppleFileAssetIo.safeName(assetId.value)}/$version/asset.bin"

    private fun writeChunkFileAtomically(path: String, bytes: ByteArray) {
        val directory = AppleFileAssetIo.parentOf(path)
        check(directory.isNotEmpty()) { "Chunk path must have a parent directory." }
        val temp = AppleFileAssetIo.createSecureTempFile(directory, "${AppleFileAssetIo.fileNameOf(path)}-", ".part")
        try {
            val fd = AppleFileAssetIo.openReadWrite(temp)
            try {
                AppleFileAssetIo.writeFully(fd, 0L, bytes, 0, bytes.size)
            } finally {
                AppleFileAssetIo.closeDescriptorQuietly(fd)
            }
            AppleFileAssetIo.promoteAtomically(temp, path)
        } catch (e: Exception) {
            AppleFileAssetIo.deleteQuietly(temp)
            throw e
        }
    }

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

private fun chunkFile(directory: String, index: Int): String = "$directory/chunk-$index"

/** Current wall-clock time in epoch milliseconds, second precision (POSIX `time(2)`). */
private fun currentTimeMillis(): Long = platform.posix.time(null).convert<Long>() * 1000L
