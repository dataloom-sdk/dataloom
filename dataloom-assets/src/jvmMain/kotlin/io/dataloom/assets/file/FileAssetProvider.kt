package io.dataloom.assets.file

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Real filesystem-backed [AssetProvider] reference implementation
 * (FR-ASSET-009): the remote/reference side of chunked, resumable,
 * integrity-verified transfer, behaviourally identical to
 * [io.dataloom.assets.memory.InMemoryAssetProvider] (it passes the same
 * [io.dataloom.assets.testkit.AssetProviderContractKit]) but storing chunk
 * bytes as real files under [baseDirectory] instead of in memory, and — not
 * bounded-memory in [io.dataloom.assets.memory.InMemoryAssetProvider]'s sense
 * of retaining every committed asset's bytes in the JVM heap — bounded by one
 * chunk-sized buffer during assembly, independent of asset size.
 *
 * ## Layout
 *
 * ```
 * baseDirectory/uploads/<safe sessionId>/chunk-<index>            in-flight; never exposed
 * baseDirectory/committed/<safe assetId>/<version>/asset.bin      exposed only after completeUpload
 * baseDirectory/committed/<safe assetId>/<version>/manifest.dlc   the commit marker; see Restart recovery
 * ```
 *
 * `<safe ...>` is [FileAssetIo.safeName]: session and asset ids are
 * caller-chosen strings and may contain characters a path segment cannot.
 *
 * ## Atomic promotion
 *
 * [completeUpload] assembles the session's already-verified chunk files into
 * one object in a secure temp file — streamed through one
 * [readBufferBytes]-sized buffer, so memory is independent of the asset's
 * size — verifies the whole-object digest for an untransformed manifest
 * exactly like [io.dataloom.assets.memory.InMemoryAssetProvider], and only
 * then promotes that temp file onto the committed path with one atomic
 * rename ([FileAssetIo.promoteAtomically]). A committed path is therefore
 * never observable half-written: either assembly is still in the temp file
 * (nothing at the committed path yet, or a previous version's committed
 * file, untouched) or the rename has completed and the whole verified object
 * is there.
 *
 * ## Restart recovery
 *
 * The committed index is in memory, but it is rebuilt from disk, so a fresh
 * instance over the same [baseDirectory] serves everything an earlier
 * instance committed. At commit, after the asset file is promoted,
 * [completeUpload] atomically writes `manifest.dlc` beside it (a
 * [CommittedAssetRecord]: the manifest plus each chunk's stored length). That
 * file is the commit marker; the first operation on a new instance scans
 * `committed/` once, under the same mutex as everything else, and indexes
 * only entries that pass every check: the record decodes under the real
 * manifest invariants, the directory names match the manifest's asset id and
 * version, and `asset.bin`'s size equals the recorded stored lengths. Anything
 * else (no manifest because a crash hit between the two steps, a corrupt or
 * foreign manifest, a truncated asset file) is skipped, never served; a later
 * upload of that id and version simply replaces it. Because the scan is lazy,
 * constructing a provider does no I/O. Whole-object bytes are not re-hashed at
 * scan time (cost would grow with total stored data); instead [readChunk]
 * re-verifies each untransformed chunk against its manifest digest before
 * returning it. A transformed asset's frames are opaque to the provider, so
 * their integrity remains the client's authenticated-decryption / digest
 * check, exactly as at upload time.
 *
 * In-flight uploads are not recovered: `uploads/` entries are never indexed
 * after a restart (a client resumes by opening a new session), so an
 * uncommitted upload is never exposed; [sweepAbandonedUploads] reclaims its
 * bytes.
 *
 * ## Cleanup
 *
 * Two mechanisms, both bounded and explicit (ADR-0006 open decision 3;
 * see `docs/adr/ADR-0016-file-backed-asset-transfer-storage.md`):
 * - **Eager, on a session's terminal transition.** [abortUpload] deletes the
 *   session's upload directory immediately. [completeUpload] deletes it too,
 *   once its chunk files have been read into the newly committed asset file
 *   — the bytes now live at the committed path, so the per-chunk temp files
 *   serve no further purpose.
 * - **A bounded, host-driven sweep for genuinely abandoned sessions**, where
 *   the client crashes or is uninstalled and never calls [abortUpload]:
 *   [sweepAbandonedUploads] deletes upload directories whose last filesystem
 *   modification is older than a caller-supplied age. This class runs no
 *   timer of its own; the host decides when and how often to call it (for
 *   example a periodic job, or once at startup).
 *
 * Chunk bytes on disk are exactly the bytes [uploadChunk] received — the raw
 * logical chunk, or an opaque transform frame; see [AssetProvider]'s
 * transformed-manifest contract — so this provider never handles plaintext
 * under encryption any more than a real wire-transport provider would.
 *
 * JVM/Android only (Android consumes this `jvmMain` source set; the module
 * has no separate Android target). No Apple implementation in this slice.
 *
 * @param baseDirectory root directory this provider owns; created if missing.
 *   Two instances must not share one [baseDirectory] concurrently — like
 *   [io.dataloom.assets.memory.InMemoryAssetProvider], this class assumes it
 *   is the sole owner of its storage.
 * @param digests digest calculator used to verify chunks and assembled
 *   objects; must support incremental hashing.
 * @param quota limits enforced on uploads; see [AssetQuota].
 * @param chunkSizeBounds accepted chunk sizes. Defaults to `1..64 MiB`,
 *   matching [io.dataloom.assets.memory.InMemoryAssetProvider].
 * @param readBufferBytes buffer size used while assembling a completed
 *   upload and while re-reading it for [readChunk]; the memory bound of
 *   those operations.
 */
public class FileAssetProvider(
    private val baseDirectory: Path,
    private val digests: DataLoomIncrementalDigestCalculator,
    private val quota: AssetQuota = AssetQuota.UNLIMITED,
    override val chunkSizeBounds: AssetChunkSizeBounds = AssetChunkSizeBounds(1, 64 * 1024 * 1024),
    private val readBufferBytes: Int = 64 * 1024,
) : AssetProvider {

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.assets.file.jvm-file-asset-provider"),
        name = ProviderName("FileAssetProvider"),
        type = ProviderType.ASSET,
        version = ProviderVersion("1.0.0"),
    )

    override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    private class Upload(val manifest: AssetManifest, val directory: Path) {
        var completed: Boolean = false

        fun committedIndices(): Set<Int> {
            val count = manifest.chunkLayout.chunkCount
            return if (completed) {
                (0 until count).toCollection(LinkedHashSet())
            } else {
                (0 until count).filterTo(LinkedHashSet()) { Files.exists(chunkFile(directory, it)) }
            }
        }
    }

    private class StoredAsset(
        val manifest: AssetManifest,
        val assetFile: Path,
        val chunkOffsets: LongArray,
        val chunkLengths: IntArray,
    )

    private val mutex = Mutex()
    private val uploads = HashMap<AssetTransferSessionId, Upload>()
    private val committed = HashMap<AssetId, MutableMap<Long, StoredAsset>>()
    private var committedLoaded = false

    /**
     * How many on-disk committed entries the restart scan refused to serve
     * (missing or corrupt manifest, or an `asset.bin` whose size disagrees with
     * it). Meaningful once any operation has run; for tests and diagnostics.
     */
    internal var skippedCommittedEntryCount: Int = 0
        private set

    private val uploadsRoot: Path = baseDirectory.resolve("uploads")
    private val committedRoot: Path = baseDirectory.resolve("committed")

    override suspend fun openUpload(request: AssetUploadRequest): ProviderOperationResult<AssetUploadStatus> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ensureCommittedLoaded()
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
                    Files.createDirectories(directory)
                    FileAssetIo.touch(directory)
                    val upload = Upload(manifest, directory)
                    uploads[request.sessionId] = upload
                    ok(status(request.sessionId, upload))
                } catch (_: IOException) {
                    fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
                }
            }
        }

    override suspend fun uploadChunk(request: AssetChunkUpload): ProviderOperationResult<AssetUploadStatus> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val upload = uploads[request.sessionId]
                        ?: return@withLock fail(AssetErrorKind.SESSION_NOT_FOUND, "Upload session not found.")
                    val descriptor = upload.manifest.chunkLayout.chunks.getOrNull(request.index)
                        ?: return@withLock fail(AssetErrorKind.CHUNK_OUT_OF_RANGE, "Chunk index is outside the manifest.")
                    val chunkPath = chunkFile(upload.directory, request.index)
                    if (upload.completed || Files.exists(chunkPath)) {
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
                    FileAssetIo.touch(upload.directory)
                    ok(status(request.sessionId, upload))
                } catch (_: IOException) {
                    fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
                }
            }
        }

    override suspend fun completeUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<AssetManifest> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ensureCommittedLoaded()
                    val upload = uploads[sessionId]
                        ?: return@withLock fail(AssetErrorKind.SESSION_NOT_FOUND, "Upload session not found.")
                    if (upload.completed) return@withLock ok(upload.manifest)
                    val manifest = upload.manifest
                    val chunkCount = manifest.chunkLayout.chunkCount
                    val chunkFiles = (0 until chunkCount).map { chunkFile(upload.directory, it) }
                    if (chunkFiles.any { !Files.exists(it) }) {
                        return@withLock fail(AssetErrorKind.INCOMPLETE_UPLOAD, "Not every chunk is committed.")
                    }

                    // A transformed asset's stored bytes are frames, not the logical bytes the
                    // whole-object digest covers, so only the client can verify it (ADR-0014, D24).
                    val transformed = AssetWireFormat.isTransformed(manifest)
                    val tempAssembled = FileAssetIo.createSecureTempFile(uploadsRoot, "assemble-", ".tmp")
                    val offsets = LongArray(chunkCount)
                    val lengths = IntArray(chunkCount)
                    val accumulator = if (!transformed) digests.newAccumulator(manifest.checksum.algorithm) else null
                    try {
                        val buffer = ByteArray(readBufferBytes)
                        RandomAccessFile(tempAssembled.toFile(), "rw").use { out ->
                            var offset = 0L
                            for (index in 0 until chunkCount) {
                                offsets[index] = offset
                                var length = 0
                                RandomAccessFile(chunkFiles[index].toFile(), "r").use { input ->
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        out.write(buffer, 0, read)
                                        accumulator?.update(buffer, 0, read)
                                        length += read
                                    }
                                }
                                lengths[index] = length
                                offset += length
                            }
                        }
                    } catch (e: Exception) {
                        accumulator?.close()
                        FileAssetIo.deleteQuietly(tempAssembled)
                        throw e
                    }

                    if (accumulator != null) {
                        val wholeDigest = accumulator.finish()
                        if (!(manifest.checksum contentEquals wholeDigest)) {
                            // Never expose a corrupted asset: drop the session, its reservation and its temp files.
                            uploads.remove(sessionId)
                            FileAssetIo.deleteQuietly(tempAssembled)
                            FileAssetIo.deleteDirectoryQuietly(upload.directory)
                            return@withLock fail(AssetErrorKind.OBJECT_DIGEST_MISMATCH, "Assembled object digest differs from the manifest.")
                        }
                    }

                    val versions = committed.getOrPut(manifest.assetId) { HashMap() }
                    if (versions.containsKey(manifest.version)) {
                        FileAssetIo.deleteQuietly(tempAssembled)
                        uploads.remove(sessionId)
                        return@withLock fail(AssetErrorKind.ASSET_VERSION_CONFLICT, "Asset version is already committed.")
                    }
                    val finalAsset = assetFile(manifest.assetId, manifest.version)
                    val finalManifest = manifestFile(manifest.assetId, manifest.version)
                    // Commit order: the manifest file is the commit marker, written after asset.bin.
                    // Dropping any stale manifest first means a crash between the two steps leaves an
                    // asset.bin with no manifest (skipped on restart), never a new asset.bin paired
                    // with an old manifest.
                    FileAssetIo.deleteQuietly(finalManifest)
                    FileAssetIo.promoteAtomically(tempAssembled, finalAsset)
                    try {
                        writeManifestFileAtomically(finalManifest, CommittedAssetRecord(manifest, lengths))
                    } catch (e: Exception) {
                        FileAssetIo.deleteQuietly(finalAsset)
                        throw e
                    }
                    versions[manifest.version] = StoredAsset(manifest, finalAsset, offsets, lengths)
                    upload.completed = true
                    // The chunk files' bytes now live in the committed asset file; the staging
                    // directory is no longer needed (eager cleanup on this terminal transition).
                    FileAssetIo.deleteDirectoryQuietly(upload.directory)
                    ok(manifest)
                } catch (_: IOException) {
                    fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
                }
            }
        }

    override suspend fun abortUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // A completed session's asset is committed; aborting must never remove it or its bytes.
                val upload = uploads[sessionId]
                if (upload != null && !upload.completed) {
                    uploads.remove(sessionId)
                    FileAssetIo.deleteDirectoryQuietly(upload.directory)
                }
                ok(Unit)
            }
        }

    override suspend fun readManifest(assetId: AssetId, version: Long?): ProviderOperationResult<AssetManifest> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ensureCommittedLoaded()
                    val versions = committed[assetId]
                    val stored = if (version == null) versions?.maxByOrNull { it.key }?.value else versions?.get(version)
                    stored?.let { ok(it.manifest) } ?: fail(AssetErrorKind.ASSET_NOT_FOUND, "Asset is not committed.")
                } catch (_: IOException) {
                    fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
                }
            }
        }

    override suspend fun readChunk(assetId: AssetId, version: Long, index: Int): ProviderOperationResult<ByteArray> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ensureCommittedLoaded()
                    val stored = committed[assetId]?.get(version)
                        ?: return@withLock fail(AssetErrorKind.ASSET_NOT_FOUND, "Asset is not committed.")
                    if (index !in stored.chunkOffsets.indices) {
                        return@withLock fail(AssetErrorKind.CHUNK_OUT_OF_RANGE, "Chunk index is outside the manifest.")
                    }
                    val bytes = ByteArray(stored.chunkLengths[index])
                    RandomAccessFile(stored.assetFile.toFile(), "r").use { file ->
                        file.seek(stored.chunkOffsets[index])
                        file.readFully(bytes)
                    }
                    // An untransformed chunk's stored bytes are the logical bytes its descriptor digests,
                    // so never serve bytes that no longer match (bit-rot or tampering after commit,
                    // possibly across a restart). A transformed frame is opaque to the provider; the
                    // client's authenticated decryption / digest check covers it (ADR-0014, D24).
                    if (!AssetWireFormat.isTransformed(stored.manifest)) {
                        val descriptor = stored.manifest.chunkLayout.chunks[index]
                        if (!(descriptor.checksum contentEquals digests.digest(descriptor.checksum.algorithm, bytes))) {
                            return@withLock fail(AssetErrorKind.OBJECT_DIGEST_MISMATCH, "Stored chunk digest differs from the manifest.")
                        }
                    }
                    ok(bytes)
                } catch (_: IOException) {
                    fail(AssetErrorKind.PROVIDER_REJECTED, "Local asset storage I/O failed.")
                }
            }
        }

    /**
     * Deletes upload directories under `baseDirectory/uploads` whose last
     * filesystem modification is older than [olderThan] — the bounded,
     * host-driven backstop for a session that was abandoned outright (the
     * client crashed or was uninstalled and never called [abortUpload]).
     * Filesystem timestamps are used rather than this instance's in-memory
     * [uploads] map on purpose: a restarted process remembers none of its
     * previous sessions, which is exactly the case this sweep exists for.
     *
     * Bounded work: one directory listing of `uploads/` plus one recursive
     * delete per stale entry; no unbounded scan and no timer of its own. The
     * host decides when to call it.
     *
     * @return the number of upload directories removed.
     */
    public suspend fun sweepAbandonedUploads(olderThan: Duration): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!Files.exists(uploadsRoot)) return@withLock 0
            val cutoffMillis = System.currentTimeMillis() - olderThan.inWholeMilliseconds
            var removed = 0
            try {
                Files.newDirectoryStream(uploadsRoot).use { entries ->
                    for (entry in entries) {
                        try {
                            if (!Files.isDirectory(entry)) continue
                            if (Files.getLastModifiedTime(entry).toMillis() <= cutoffMillis) {
                                FileAssetIo.deleteDirectoryQuietly(entry)
                                uploads.entries.removeIf { (_, upload) -> upload.directory == entry }
                                removed++
                            }
                        } catch (_: IOException) {
                            // Best-effort per entry: a concurrent abort may have already removed it.
                        }
                    }
                }
            } catch (_: IOException) {
                // Best-effort: report what was removed before the listing failed.
            }
            removed
        }
    }

    private fun sessionDirectory(sessionId: AssetTransferSessionId): Path =
        uploadsRoot.resolve(FileAssetIo.safeName(sessionId.value))

    private fun assetFile(assetId: AssetId, version: Long): Path =
        committedRoot.resolve(FileAssetIo.safeName(assetId.value)).resolve(version.toString()).resolve("asset.bin")

    private fun manifestFile(assetId: AssetId, version: Long): Path =
        assetFile(assetId, version).resolveSibling(MANIFEST_FILE_NAME)

    private fun writeManifestFileAtomically(path: Path, record: CommittedAssetRecord) {
        val directory = checkNotNull(path.parent) { "Manifest path must have a parent directory." }
        val temp = FileAssetIo.createSecureTempFile(directory, "${path.fileName}-", ".part")
        try {
            Files.write(temp, CommittedAssetRecordCodec.encode(record).encodeToByteArray())
            FileAssetIo.promoteAtomically(temp, path)
        } catch (e: Exception) {
            FileAssetIo.deleteQuietly(temp)
            throw e
        }
    }

    /**
     * Rebuilds [committed] from disk the first time any operation needs it
     * (call with [mutex] held). Lazy rather than in the constructor so
     * construction stays free of I/O and a failing directory listing surfaces
     * as a typed `PROVIDER_REJECTED` result instead of a constructor
     * exception; [committedLoaded] stays `false` on such a failure, so the
     * next call retries. Every entry is validated before it is indexed; one
     * that fails is skipped (counted in [skippedCommittedEntryCount]), never
     * served, and never aborts the scan of the others.
     */
    private fun ensureCommittedLoaded() {
        if (committedLoaded) return
        if (Files.isDirectory(committedRoot)) {
            Files.newDirectoryStream(committedRoot).use { assetDirectories ->
                for (assetDirectory in assetDirectories) {
                    if (!Files.isDirectory(assetDirectory)) continue
                    try {
                        Files.newDirectoryStream(assetDirectory).use { versionDirectories ->
                            for (versionDirectory in versionDirectories) loadCommittedEntry(assetDirectory, versionDirectory)
                        }
                    } catch (_: IOException) {
                        skippedCommittedEntryCount++
                    }
                }
            }
        }
        committedLoaded = true
    }

    private fun loadCommittedEntry(assetDirectory: Path, versionDirectory: Path) {
        try {
            val version = versionDirectory.fileName.toString().toLong()
            val manifestPath = versionDirectory.resolve(MANIFEST_FILE_NAME)
            val assetPath = versionDirectory.resolve("asset.bin")
            require(Files.isRegularFile(manifestPath) && Files.isRegularFile(assetPath))
            require(Files.size(manifestPath) <= CommittedAssetRecordCodec.MAX_ENCODED_LENGTH)
            val record = CommittedAssetRecordCodec.decode(Files.readAllBytes(manifestPath).decodeToString(throwOnInvalidSequence = true))
            val manifest = record.manifest
            // The path is the identity: a manifest copied or swapped under another asset id / version is not trusted.
            require(manifest.version == version && FileAssetIo.safeName(manifest.assetId.value) == assetDirectory.fileName.toString())
            // Truncation or growth since commit: the bytes are not the ones the manifest describes.
            require(Files.size(assetPath) == record.storedSizeBytes)
            val offsets = LongArray(record.storedChunkLengths.size)
            var offset = 0L
            for (index in offsets.indices) {
                offsets[index] = offset
                offset += record.storedChunkLengths[index]
            }
            committed.getOrPut(manifest.assetId) { HashMap() }[version] =
                StoredAsset(manifest, assetPath, offsets, record.storedChunkLengths)
        } catch (_: Exception) {
            skippedCommittedEntryCount++
        }
    }

    private fun writeChunkFileAtomically(path: Path, bytes: ByteArray) {
        val directory = checkNotNull(path.parent) { "Chunk path must have a parent directory." }
        val temp = FileAssetIo.createSecureTempFile(directory, "${path.fileName}-", ".part")
        try {
            Files.write(temp, bytes)
            FileAssetIo.promoteAtomically(temp, path)
        } catch (e: Exception) {
            FileAssetIo.deleteQuietly(temp)
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

private fun chunkFile(directory: Path, index: Int): Path = directory.resolve("chunk-$index")

private const val MANIFEST_FILE_NAME: String = "manifest.dlc"
