package io.dataloom.assets

import io.dataloom.api.asset.AssetChunkDescriptor
import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.transform.AssetTransferTransforms
import io.dataloom.assets.transform.AssetWireFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** How one call into [AssetTransferEngine] ended. */
public sealed interface AssetTransferOutcome {

    /** Verified and complete. [AssetTransferSession.manifest] describes the asset. */
    public data class Completed(public val session: AssetTransferSession) : AssetTransferOutcome

    /**
     * Stopped without a terminal decision (transient failure, unreachable
     * provider, retransmit needed). The session is intact and durable;
     * calling the same operation again resumes it, skipping committed chunks.
     * Retry policy (backoff, budgets, circuit breaking) belongs to the caller
     * or to the runtime's retry engine, not to this engine.
     */
    public class Interrupted(
        public val session: AssetTransferSession,
        public val error: DataLoomError,
    ) : AssetTransferOutcome

    /** Failed terminally; the session's [AssetTransferSession.failure] says why. Start a new session to retry. */
    public class Failed(public val session: AssetTransferSession) : AssetTransferOutcome {
        /** Sanitised error for the session's terminal failure kind. */
        public val error: AssetTransferError
            get() = AssetTransferError(checkNotNull(session.failure), "Asset transfer failed.")
    }

    /** Cancelled by the caller; partial work has been cleaned up. */
    public data class Cancelled(public val session: AssetTransferSession) : AssetTransferOutcome

    /** Nothing was started and no session exists (for example the asset was not found, or the source is empty). */
    public class NotStarted(public val error: DataLoomError) : AssetTransferOutcome

    /**
     * The [AssetTransferSessionStore] failed (durable storage error, contention
     * past its retry bound, or an unusable persisted session), so the engine
     * could not read or record session state and stopped without guessing.
     * Nothing beyond what was already durably recorded has been decided.
     * [error] classifies it: [io.dataloom.api.error.Recoverability.RECOVERABLE]
     * means the same call can simply be repeated to resume;
     * [AssetErrorKind.SESSION_STATE_CORRUPT] means that session id is unusable.
     */
    public class SessionStoreFailure(public val error: DataLoomError) : AssetTransferOutcome
}

/**
 * Resumable, bounded-memory upload/download engine over an [AssetProvider],
 * an [AssetTransferSessionStore] and the [AssetTransferSession] state machine.
 * With [maxConcurrency] `1` (the default) chunks transfer one at a time, in
 * order; with a larger value, up to that many transfer concurrently (see
 * "Parallel transfer" below).
 *
 * ## Resumability
 *
 * [upload] and [download] are idempotent by session id: the first call
 * creates the session; a later call with the same id — after an
 * [AssetTransferOutcome.Interrupted], a coroutine cancellation, or a process
 * restart with a durable store — resumes it and only transfers the chunks that
 * are not yet committed. On upload resume the provider's own committed set is
 * authoritative and the local record is reconciled to it.
 *
 * ## Bounded memory
 *
 * At most [maxConcurrency] chunk buffers (each the chunk size) are live at a
 * time on the transfer path — one, by default — and verification streams
 * through a fixed-size buffer; memory does not grow with the asset size.
 *
 * ## Cancellation
 *
 * Cancelling the calling coroutine is *cooperative* and leaves the session
 * resumable (`TRANSFERRING`, committed chunks kept). [cancel] is the explicit,
 * permanent decision: it moves the session to `CANCELLED` and cleans up the
 * provider-side upload (releasing its quota reservation) or the download sink.
 * A running transfer notices a concurrent [cancel] between chunks.
 *
 * ## Integrity
 *
 * Every chunk is verified against the manifest before it is sent (upload) or
 * written (download); the whole object is verified before the session may
 * complete. A whole-object mismatch fails the session and discards the
 * download sink; nothing corrupted is ever reported as completed.
 *
 * ## Transforms (compression and encryption)
 *
 * With [transforms] configured, an upload compresses then encrypts each chunk
 * into a versioned frame (see [AssetWireFormat]) and records the algorithms in
 * the manifest; a download reverses them, driven by the manifest. Digests stay
 * over the *logical* bytes and are verified by this engine, never by a
 * provider that stores ciphertext (ADR-0014, D24): the upload verifies each
 * source chunk against the manifest before transforming it, and the download
 * verifies each decoded chunk and the whole assembled object. Peak memory is a
 * small constant number of chunk-sized buffers (the chunk, its compressed
 * form, its frame), still independent of the asset size. A transform this
 * platform cannot run fails with [AssetErrorKind.TRANSFORM_UNSUPPORTED]
 * before anything is transferred; it is never skipped.
 *
 * ## Parallel transfer (FR-ASSET-006)
 *
 * With [maxConcurrency] greater than `1`, up to that many chunks transfer at
 * once: one coroutine is launched per missing chunk index, and a shared
 * [kotlinx.coroutines.sync.Semaphore] sized to [maxConcurrency] admits only
 * that many of them to actually run at a time — a bounded worker pool
 * draining the session's missing-chunk list. `Semaphore` is documented as
 * fair, servicing waiters in strict FIFO order, so **fairness strategy**: no
 * queued chunk can be starved behind others cutting in ahead of it, no
 * matter how long any one in-flight chunk's I/O takes — the longest-waiting
 * chunk is always the next one admitted once a slot frees up.
 *
 * **Failure semantics**, chosen to match this engine's existing sequential
 * behaviour exactly rather than introduce a second policy: this engine has
 * always stopped at the *first* chunk-level error (recoverable or not)
 * without attempting further chunks, trivially "fail-fast" when sequential
 * since nothing else is ever in flight. Parallel mode preserves that: the
 * first chunk to report a terminal outcome cancels every other in-flight or
 * still-queued chunk coroutine (ordinary structured-concurrency cancellation
 * — `coroutineScope` cancels its remaining children as soon as one throws)
 * and that outcome is returned. A chunk whose provider call is already past
 * its last suspension point when the cancellation arrives runs to completion
 * (the same cooperative-cancellation granularity the sequential engine's
 * "notices a concurrent cancel between chunks" always had), but can never
 * corrupt the session's recorded outcome: [AssetTransferSessionStore.save]
 * is compare-and-set, so concurrent `ChunkCommitted`/`Fail` events from
 * racing workers converge safely (see that store's KDoc) instead of one
 * silently clobbering another.
 *
 * **Bounded memory** scales with concurrency: each concurrently in-flight
 * chunk allocates its own chunk-sized buffer, so peak memory is
 * `maxConcurrency` chunk buffers (still a small constant, independent of
 * asset size), not the single buffer sequential mode uses.
 *
 * Content-policy hooks (FR-ASSET-012) remain a later slice. Session
 * persistence is the caller's choice of [AssetTransferSessionStore]: with
 * [DurableAssetTransferSessionStore] a restart resumes where the last
 * durably recorded chunk left off.
 *
 * @param chunkSizeBytes requested chunk size for new uploads; clamped into the
 *   provider's [AssetProvider.chunkSizeBounds].
 * @param digestAlgorithm algorithm for the chunk and whole-object digests of
 *   new uploads.
 * @param verifyBufferBytes streaming buffer size for whole-object verification.
 * @param transforms compression and encryption applied to uploads and reversed
 *   on download; [AssetTransferTransforms.NONE] transfers chunks as-is.
 * @param maxConcurrency largest number of chunks transferred at once. `1`
 *   (the default) is the original sequential behaviour: chunks run strictly
 *   one at a time, in order. Must be at least `1`.
 * @param observer optional hook notified once per [upload]/[download]/[cancel]
 *   call with the outcome it is about to return (see [AssetTransferObserver]'s
 *   own class doc). `null` (the default) leaves behavior exactly as before
 *   this parameter existed -- no notification, no extra work.
 */
public class AssetTransferEngine(
    private val provider: AssetProvider,
    private val sessions: AssetTransferSessionStore,
    digests: DataLoomIncrementalDigestCalculator,
    private val chunkSizeBytes: Int = AssetChunkSizeBounds.DEFAULT_CHUNK_SIZE_BYTES,
    private val digestAlgorithm: DigestAlgorithm = DigestAlgorithm.SHA_256,
    verifyBufferBytes: Int = AssetIntegrityVerifier.DEFAULT_READ_BUFFER_BYTES,
    private val transforms: AssetTransferTransforms = AssetTransferTransforms.NONE,
    private val maxConcurrency: Int = 1,
    private val observer: AssetTransferObserver? = null,
) {
    init {
        require(maxConcurrency >= 1) { "AssetTransferEngine.maxConcurrency must be at least 1, but was $maxConcurrency." }
    }

    private val verifier = AssetIntegrityVerifier(digests, verifyBufferBytes)

    /**
     * Uploads [source] as [assetId] at [version], creating session [sessionId]
     * or resuming it if it already exists.
     *
     * A new session first reads [source] once to build its manifest
     * (per-chunk digests plus the whole-object digest) in bounded memory. On
     * resume the stored manifest is reused and each chunk read from [source]
     * is re-verified against it, so a source that changed since the session
     * began fails with [AssetErrorKind.SOURCE_CONTENT_CHANGED] instead of
     * uploading mismatched data.
     */
    public suspend fun upload(
        sessionId: AssetTransferSessionId,
        assetId: AssetId,
        version: Long,
        mediaType: AssetMediaType,
        source: AssetSource,
    ): AssetTransferOutcome {
        val outcome = guardStore { uploadUnguarded(sessionId, assetId, version, mediaType, source) }
        notifyObserver(sessionId, AssetTransferOperation.UPLOAD, outcome)
        return outcome
    }

    private suspend fun uploadUnguarded(
        sessionId: AssetTransferSessionId,
        assetId: AssetId,
        version: Long,
        mediaType: AssetMediaType,
        source: AssetSource,
    ): AssetTransferOutcome {
        val existing = sessions.load(sessionId)
        val session = if (existing != null) {
            require(existing.direction == AssetTransferDirection.UPLOAD) {
                "Session $sessionId is not an upload session."
            }
            require(existing.manifest.assetId == assetId && existing.manifest.version == version) {
                "Session $sessionId belongs to a different asset or version."
            }
            existing
        } else {
            when (val prepared = prepareUploadSession(sessionId, assetId, version, mediaType, source)) {
                is Prepared.Ready -> prepared.session
                is Prepared.Refused -> return prepared.outcome
            }
        }
        return driveUpload(session, source)
    }

    /**
     * Downloads [assetId] (at [version], or its latest committed version when
     * `null`) into [sink], creating session [sessionId] or resuming it.
     *
     * Bytes are written to [sink] as chunks are verified; the whole object is
     * re-read from [sink] for verification, which is what makes a resumed
     * download safe even if the staged bytes were lost or corrupted.
     */
    public suspend fun download(
        sessionId: AssetTransferSessionId,
        assetId: AssetId,
        version: Long?,
        sink: AssetSink,
    ): AssetTransferOutcome {
        val outcome = guardStore { downloadUnguarded(sessionId, assetId, version, sink) }
        notifyObserver(sessionId, AssetTransferOperation.DOWNLOAD, outcome)
        return outcome
    }

    private suspend fun downloadUnguarded(
        sessionId: AssetTransferSessionId,
        assetId: AssetId,
        version: Long?,
        sink: AssetSink,
    ): AssetTransferOutcome {
        val existing = sessions.load(sessionId)
        val session = if (existing != null) {
            require(existing.direction == AssetTransferDirection.DOWNLOAD) {
                "Session $sessionId is not a download session."
            }
            require(existing.manifest.assetId == assetId && (version == null || existing.manifest.version == version)) {
                "Session $sessionId belongs to a different asset or version."
            }
            existing
        } else {
            when (val manifest = provider.readManifest(assetId, version)) {
                is ProviderOperationResult.Failure -> return AssetTransferOutcome.NotStarted(manifest.error)
                is ProviderOperationResult.Success -> {
                    // Refuse before creating a session if this engine cannot reverse the asset's transforms.
                    val resolved = AssetChunkPipeline.forManifest(manifest.value, transforms, upload = false)
                    if (resolved is AssetChunkPipeline.Resolution.Refused) {
                        return AssetTransferOutcome.NotStarted(resolved.error)
                    }
                    createSession(
                        AssetTransferSession(sessionId, AssetTransferDirection.DOWNLOAD, manifest.value),
                    )
                }
            }
        }
        return driveDownload(session, sink)
    }

    /**
     * Permanently cancels session [sessionId].
     *
     * An upload's provider-side session is aborted (releasing its quota
     * reservation); a download's [sink], when supplied, is discarded. Both are
     * idempotent, so cancelling twice is harmless. Cancelling a session that
     * is already `COMPLETED` or `FAILED` changes nothing and reports that
     * terminal outcome — a completed transfer is never falsely cancelled.
     */
    public suspend fun cancel(sessionId: AssetTransferSessionId, sink: AssetSink? = null): AssetTransferOutcome {
        val outcome = guardStore { cancelUnguarded(sessionId, sink) }
        notifyObserver(sessionId, AssetTransferOperation.CANCEL, outcome)
        return outcome
    }

    private suspend fun cancelUnguarded(sessionId: AssetTransferSessionId, sink: AssetSink?): AssetTransferOutcome {
        if (sessions.load(sessionId) == null) {
            return AssetTransferOutcome.NotStarted(
                AssetTransferError(AssetErrorKind.SESSION_NOT_FOUND, "No such transfer session."),
            )
        }
        val session = advance(sessionId, AssetTransferEvent.Cancel)
        if (session.phase == AssetTransferPhase.CANCELLED) {
            when (session.direction) {
                AssetTransferDirection.UPLOAD -> quietly { provider.abortUpload(sessionId) }
                AssetTransferDirection.DOWNLOAD -> if (sink != null) quietly { sink.discard() }
            }
        }
        return terminalOutcome(session) ?: interrupted(session)
    }

    // ---------------------------------------------------------------- upload

    private sealed interface Prepared {
        class Ready(val session: AssetTransferSession) : Prepared
        class Refused(val outcome: AssetTransferOutcome) : Prepared
    }

    private suspend fun prepareUploadSession(
        sessionId: AssetTransferSessionId,
        assetId: AssetId,
        version: Long,
        mediaType: AssetMediaType,
        source: AssetSource,
    ): Prepared {
        // Refuse an unusable transform before reading a single source byte.
        AssetChunkPipeline.availability(transforms)?.let { return Prepared.Refused(AssetTransferOutcome.NotStarted(it)) }
        val manifest = try {
            val size = source.sizeBytes()
            if (size <= 0) {
                return refused(AssetErrorKind.EMPTY_ASSET, "Asset has no bytes.")
            }
            val plan = AssetChunkPlan.negotiate(size, chunkSizeBytes, provider.chunkSizeBounds)
            val logical = verifier.prepareManifest(assetId, version, mediaType, source, plan, digestAlgorithm)
            AssetChunkPipeline.describe(logical, transforms)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetSourceChangedException) {
            return refused(AssetErrorKind.SOURCE_CONTENT_CHANGED, "Source content changed while preparing.", e)
        } catch (e: Exception) {
            return refused(AssetErrorKind.SOURCE_FAILURE, "Source could not be read.", e)
        }
        return Prepared.Ready(createSession(AssetTransferSession(sessionId, AssetTransferDirection.UPLOAD, manifest)))
    }

    private fun refused(kind: AssetErrorKind, message: String, cause: Throwable? = null): Prepared.Refused =
        Prepared.Refused(AssetTransferOutcome.NotStarted(AssetTransferError(kind, message, cause)))

    private suspend fun driveUpload(initial: AssetTransferSession, source: AssetSource): AssetTransferOutcome {
        val sessionId = initial.sessionId
        var session = initial
        terminalOutcome(session)?.let { return it }
        val abortProvider: suspend () -> Unit = { provider.abortUpload(sessionId) }
        val pipeline = when (val resolved = AssetChunkPipeline.forManifest(session.manifest, transforms, upload = true)) {
            is AssetChunkPipeline.Resolution.Refused -> return onError(sessionId, resolved.error, abortProvider)
            is AssetChunkPipeline.Resolution.Ready -> resolved.pipeline
        }

        // Open (or re-open) the provider session; its committed set is authoritative.
        val status = when (val opened = provider.openUpload(AssetUploadRequest(sessionId, session.manifest))) {
            is ProviderOperationResult.Failure -> return onError(sessionId, opened.error, abortProvider)
            is ProviderOperationResult.Success -> opened.value
        }
        session = advance(sessionId, AssetTransferEvent.Start)
        if (session.phase == AssetTransferPhase.TRANSFERRING) {
            session = advance(sessionId, AssetTransferEvent.ReconcileCommitted(status.committedChunks))
        }

        if (session.phase == AssetTransferPhase.TRANSFERRING) {
            val chunks = session.manifest.chunkLayout.chunks
            transferChunks(session.missingChunks) { index ->
                uploadOneChunk(sessionId, chunks, index, pipeline, source, abortProvider)
            }?.let { return it }
            session = advance(sessionId, AssetTransferEvent.BeginVerification)
        }

        if (session.phase == AssetTransferPhase.VERIFYING) {
            when (val completed = provider.completeUpload(sessionId)) {
                is ProviderOperationResult.Failure -> return onError(sessionId, completed.error, abortProvider)
                is ProviderOperationResult.Success ->
                    session = advance(sessionId, AssetTransferEvent.VerificationSucceeded)
            }
        }
        return terminalOutcome(session) ?: interrupted(session)
    }

    /**
     * Transfers (reads, verifies, transforms and uploads) chunk [index] of
     * [chunks], or skips it if some other caller already committed it.
     * Allocates its own chunk-sized buffer on every call rather than reusing
     * one across calls, so it is safe to run concurrently with other calls
     * for different indices — see [transferChunks].
     *
     * @return `null` on success (including "already committed"); the
     *   terminal outcome if the transfer must stop.
     */
    private suspend fun uploadOneChunk(
        sessionId: AssetTransferSessionId,
        chunks: List<AssetChunkDescriptor>,
        index: Int,
        pipeline: AssetChunkPipeline?,
        source: AssetSource,
        abortProvider: suspend () -> Unit,
    ): AssetTransferOutcome? {
        val current = reload(sessionId)
        terminalOutcome(current)?.let { return it }
        if (index in current.committedChunks) return null

        val descriptor = chunks[index]
        val length = descriptor.lengthBytes.toInt()
        val buffer = ByteArray(length)
        val read = when (
            val result = attempt(AssetErrorKind.SOURCE_FAILURE) {
                source.readFully(descriptor.offsetBytes, buffer, 0, length)
            }
        ) {
            is Attempt.Err -> return onError(sessionId, result.error, abortProvider)
            is Attempt.Ok -> result.value
        }
        if (read != length || !verifier.verifyChunk(descriptor, buffer)) {
            return onError(
                sessionId,
                AssetTransferError(AssetErrorKind.SOURCE_CONTENT_CHANGED, "Source content no longer matches the manifest."),
                abortProvider,
            )
        }
        val wire = if (pipeline == null) {
            buffer
        } else {
            when (val framed = attempt(AssetErrorKind.TRANSFORM_UNSUPPORTED) { pipeline.toWire(index, buffer) }) {
                is Attempt.Err -> return onError(sessionId, framed.error, abortProvider)
                is Attempt.Ok -> framed.value
            }
        }
        return when (val uploaded = provider.uploadChunk(AssetChunkUpload(sessionId, index, wire))) {
            is ProviderOperationResult.Failure -> onError(sessionId, uploaded.error, abortProvider)
            is ProviderOperationResult.Success -> {
                advance(sessionId, AssetTransferEvent.ChunkCommitted(index))
                null
            }
        }
    }

    // -------------------------------------------------------------- download

    private suspend fun driveDownload(initial: AssetTransferSession, sink: AssetSink): AssetTransferOutcome {
        val sessionId = initial.sessionId
        var session = initial
        terminalOutcome(session)?.let { return it }
        val discardSink: suspend () -> Unit = { sink.discard() }
        val manifest = session.manifest
        val pipeline = when (val resolved = AssetChunkPipeline.forManifest(manifest, transforms, upload = false)) {
            is AssetChunkPipeline.Resolution.Refused -> return onError(sessionId, resolved.error, discardSink)
            is AssetChunkPipeline.Resolution.Ready -> resolved.pipeline
        }

        (attempt(AssetErrorKind.SINK_FAILURE) { sink.reserve(manifest.sizeBytes) } as? Attempt.Err)
            ?.let { return onError(sessionId, it.error, discardSink) }
        session = advance(sessionId, AssetTransferEvent.Start)

        if (session.phase == AssetTransferPhase.TRANSFERRING) {
            transferChunks(session.missingChunks) { index ->
                downloadOneChunk(sessionId, manifest, index, pipeline, sink, discardSink)
            }?.let { return it }
            session = advance(sessionId, AssetTransferEvent.BeginVerification)
        }

        if (session.phase == AssetTransferPhase.VERIFYING) {
            val verified = when (
                val result = attempt(AssetErrorKind.SINK_FAILURE) { verifier.verifyObject(manifest, sink) }
            ) {
                is Attempt.Err -> return onError(sessionId, result.error, discardSink)
                is Attempt.Ok -> result.value
            }
            if (verified) {
                session = advance(sessionId, AssetTransferEvent.VerificationSucceeded)
            } else {
                return onError(
                    sessionId,
                    AssetTransferError(AssetErrorKind.OBJECT_DIGEST_MISMATCH, "Assembled object does not match the manifest."),
                    discardSink,
                )
            }
        }
        return terminalOutcome(session) ?: interrupted(session)
    }

    /**
     * Downloads, verifies and writes chunk [index] of [manifest], or skips it
     * if some other caller already committed it. See [uploadOneChunk]'s
     * concurrency note — this is its download-side mirror.
     *
     * @return `null` on success (including "already committed"); the
     *   terminal outcome if the transfer must stop.
     */
    private suspend fun downloadOneChunk(
        sessionId: AssetTransferSessionId,
        manifest: AssetManifest,
        index: Int,
        pipeline: AssetChunkPipeline?,
        sink: AssetSink,
        discardSink: suspend () -> Unit,
    ): AssetTransferOutcome? {
        val current = reload(sessionId)
        terminalOutcome(current)?.let { return it }
        if (index in current.committedChunks) return null

        val descriptor = manifest.chunkLayout.chunks[index]
        val stored = when (val read = provider.readChunk(manifest.assetId, manifest.version, index)) {
            is ProviderOperationResult.Failure -> return onError(sessionId, read.error, discardSink)
            is ProviderOperationResult.Success -> read.value
        }
        val bytes = if (pipeline == null) {
            stored
        } else {
            val expected = descriptor.lengthBytes.toInt()
            when (val decoded = attempt(AssetErrorKind.TRANSFORM_FRAME_INVALID) { pipeline.fromWire(index, stored, expected) }) {
                is Attempt.Err -> return onError(sessionId, decoded.error, discardSink)
                is Attempt.Ok -> decoded.value
            }
        }
        if (!verifier.verifyChunk(descriptor, bytes)) {
            return onError(
                sessionId,
                AssetTransferError(AssetErrorKind.CHUNK_DIGEST_MISMATCH, "Downloaded chunk does not match the manifest."),
                discardSink,
            )
        }
        (attempt(AssetErrorKind.SINK_FAILURE) { sink.write(descriptor.offsetBytes, bytes, 0, bytes.size) } as? Attempt.Err)
            ?.let { return onError(sessionId, it.error, discardSink) }
        advance(sessionId, AssetTransferEvent.ChunkCommitted(index))
        return null
    }

    // --------------------------------------------------------------- helpers

    /**
     * Runs [transfer] once per entry of [indices], returning the first
     * non-null (terminal) outcome it produces, or `null` once every chunk has
     * succeeded. Dispatches to [transferSequential] or [transferParallel]
     * depending on [maxConcurrency]; see the class KDoc's "Parallel transfer"
     * section for the concurrency, fairness and failure-semantics design.
     */
    private suspend fun transferChunks(
        indices: List<Int>,
        transfer: suspend (Int) -> AssetTransferOutcome?,
    ): AssetTransferOutcome? = if (maxConcurrency <= 1) {
        transferSequential(indices, transfer)
    } else {
        transferParallel(indices, transfer)
    }

    /** [maxConcurrency] `1`: the original, unchanged behaviour — one chunk at a time, in order. */
    private suspend fun transferSequential(
        indices: List<Int>,
        transfer: suspend (Int) -> AssetTransferOutcome?,
    ): AssetTransferOutcome? {
        for (index in indices) {
            currentCoroutineContext().ensureActive()
            transfer(index)?.let { return it }
        }
        return null
    }

    /**
     * [maxConcurrency] greater than `1`: up to [maxConcurrency] chunks run at
     * once, each a coroutine launched for one index and gated by a shared
     * [Semaphore] of that size — a bounded worker pool draining the same
     * [indices] list. The first chunk whose [transfer] returns a non-null
     * outcome throws [ChunkTransferAborted] carrying it; `coroutineScope`
     * reacts to an uncaught exception from any child by cancelling the rest
     * (ordinary structured concurrency, not a bespoke mechanism), so every
     * other in-flight or still-queued chunk stops as soon as it next reaches
     * a cancellation point, and that first outcome is what this function
     * returns. [ChunkTransferAborted] deliberately is not a
     * [kotlinx.coroutines.CancellationException]: that keeps it from being
     * treated as ordinary cooperative cancellation (which `coroutineScope`
     * would swallow instead of propagating), so it reliably surfaces here.
     */
    private suspend fun transferParallel(
        indices: List<Int>,
        transfer: suspend (Int) -> AssetTransferOutcome?,
    ): AssetTransferOutcome? {
        if (indices.isEmpty()) return null
        return try {
            coroutineScope {
                val gate = Semaphore(maxConcurrency)
                for (index in indices) {
                    launch {
                        gate.withPermit {
                            currentCoroutineContext().ensureActive()
                            transfer(index)?.let { throw ChunkTransferAborted(it) }
                        }
                    }
                }
            }
            null
        } catch (e: ChunkTransferAborted) {
            e.outcome
        }
    }

    /**
     * Internal signal thrown by a [transferParallel] worker to report a
     * terminal outcome and have `coroutineScope` cancel its siblings. Caught
     * inside [transferParallel]; never escapes this class.
     */
    private class ChunkTransferAborted(val outcome: AssetTransferOutcome) : RuntimeException()

    /**
     * Converts a durable-store failure into an outcome. The store keeps the
     * last session state it successfully persisted and every provider
     * operation is idempotent, so the caller can simply repeat the call.
     */
    private suspend fun guardStore(block: suspend () -> AssetTransferOutcome): AssetTransferOutcome =
        try {
            block()
        } catch (e: AssetTransferSessionStoreException) {
            AssetTransferOutcome.SessionStoreFailure(e.error)
        }

    /**
     * Notifies [observer], when configured, with the already-decided
     * [outcome] a public operation is about to return. [observer] itself is
     * solely responsible for isolating its own failures (see
     * [AssetTransferObserver]'s own class doc) -- this engine neither catches
     * nor expects any exception here, exactly as
     * `io.dataloom.runtime.queue.DurableQueueExecutionProcessor` calls its own
     * transition observer directly.
     */
    private suspend fun notifyObserver(
        sessionId: AssetTransferSessionId,
        operation: AssetTransferOperation,
        outcome: AssetTransferOutcome,
    ) {
        observer?.onOutcome(sessionId, operation, outcome)
    }

    /** Stores a brand-new session, or returns the one a concurrent caller stored first. */
    private suspend fun createSession(session: AssetTransferSession): AssetTransferSession =
        if (sessions.save(session, expectedRevision = null)) session else reload(session.sessionId)

    private suspend fun reload(sessionId: AssetTransferSessionId): AssetTransferSession =
        checkNotNull(sessions.load(sessionId)) { "Asset transfer session $sessionId disappeared." }

    private suspend fun advance(sessionId: AssetTransferSessionId, event: AssetTransferEvent): AssetTransferSession =
        sessions.applyEvent(sessionId, event).session

    /**
     * Turns an error into an outcome. A non-terminal error ([Recoverability.RECOVERABLE]
     * or [Recoverability.UNKNOWN] — never destroy resumable state on an
     * ambiguous error) leaves the session as-is and reports it interrupted.
     * A [Recoverability.NON_RECOVERABLE] error fails the session and runs
     * [cleanup] (provider abort or sink discard) if the failure took effect.
     */
    private suspend fun onError(
        sessionId: AssetTransferSessionId,
        error: DataLoomError,
        cleanup: suspend () -> Unit,
    ): AssetTransferOutcome {
        if (error.recoverability != Recoverability.NON_RECOVERABLE) {
            return interrupted(reload(sessionId), error)
        }
        val kind = (error as? AssetTransferError)?.kind ?: AssetErrorKind.PROVIDER_REJECTED
        val session = advance(sessionId, AssetTransferEvent.Fail(kind))
        if (session.phase == AssetTransferPhase.FAILED) quietly(cleanup)
        return terminalOutcome(session) ?: interrupted(session, error)
    }

    private fun terminalOutcome(session: AssetTransferSession): AssetTransferOutcome? = when (session.phase) {
        AssetTransferPhase.COMPLETED -> AssetTransferOutcome.Completed(session)
        AssetTransferPhase.FAILED -> AssetTransferOutcome.Failed(session)
        AssetTransferPhase.CANCELLED -> AssetTransferOutcome.Cancelled(session)
        AssetTransferPhase.CREATED, AssetTransferPhase.TRANSFERRING, AssetTransferPhase.VERIFYING -> null
    }

    private fun interrupted(
        session: AssetTransferSession,
        error: DataLoomError = AssetTransferError(AssetErrorKind.PROVIDER_UNAVAILABLE, "Transfer interrupted before completion."),
    ): AssetTransferOutcome = AssetTransferOutcome.Interrupted(session, error)

    /** Best-effort cleanup: failures are ignored (the session outcome is already decided), cancellation is not. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cleanup is idempotent and retried by provider-side expiry of abandoned sessions.
        }
    }

    private sealed interface Attempt<out T> {
        class Ok<T>(val value: T) : Attempt<T>
        class Err(val error: DataLoomError) : Attempt<Nothing>
    }

    private suspend fun <T> attempt(failureKind: AssetErrorKind, block: suspend () -> T): Attempt<T> =
        try {
            Attempt.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetSinkFullException) {
            Attempt.Err(AssetTransferError(AssetErrorKind.QUOTA_EXCEEDED, "Local storage quota exceeded.", e))
        } catch (e: AssetSourceChangedException) {
            Attempt.Err(AssetTransferError(AssetErrorKind.SOURCE_CONTENT_CHANGED, "Source content changed.", e))
        } catch (e: Exception) {
            Attempt.Err(transformError(e) ?: AssetTransferError(failureKind, "Local asset I/O failed.", e))
        }
}
