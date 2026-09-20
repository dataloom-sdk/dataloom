package io.dataloom.assets

import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
}

/**
 * Sequential, bounded-memory upload/download engine over an [AssetProvider],
 * an [AssetTransferSessionStore] and the [AssetTransferSession] state machine.
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
 * At most one chunk buffer (the chunk size) is live at a time on the transfer
 * path, and verification streams through a fixed-size buffer; memory does not
 * grow with the asset size.
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
 * ## Out of scope for this slice
 *
 * Parallel chunk transfer, fairness controls, compression/encryption wiring,
 * content-policy hooks, and durable persistence of sessions are later slices.
 * The engine is not connected to `DataLoomBuilder`.
 *
 * @param chunkSizeBytes requested chunk size for new uploads; clamped into the
 *   provider's [AssetProvider.chunkSizeBounds].
 * @param digestAlgorithm algorithm for the chunk and whole-object digests of
 *   new uploads.
 * @param verifyBufferBytes streaming buffer size for whole-object verification.
 */
public class AssetTransferEngine(
    private val provider: AssetProvider,
    private val sessions: AssetTransferSessionStore,
    digests: DataLoomIncrementalDigestCalculator,
    private val chunkSizeBytes: Int = AssetChunkSizeBounds.DEFAULT_CHUNK_SIZE_BYTES,
    private val digestAlgorithm: DigestAlgorithm = DigestAlgorithm.SHA_256,
    verifyBufferBytes: Int = AssetIntegrityVerifier.DEFAULT_READ_BUFFER_BYTES,
) {
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
                is ProviderOperationResult.Success -> createSession(
                    AssetTransferSession(sessionId, AssetTransferDirection.DOWNLOAD, manifest.value),
                )
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
        val manifest = try {
            val size = source.sizeBytes()
            if (size <= 0) {
                return refused(AssetErrorKind.EMPTY_ASSET, "Asset has no bytes.")
            }
            val plan = AssetChunkPlan.negotiate(size, chunkSizeBytes, provider.chunkSizeBounds)
            verifier.prepareManifest(assetId, version, mediaType, source, plan, digestAlgorithm)
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
            val buffer = ByteArray(chunks.maxOf { it.lengthBytes }.toInt())
            for (index in session.missingChunks) {
                currentCoroutineContext().ensureActive()
                session = reload(sessionId)
                terminalOutcome(session)?.let { return it }
                if (index in session.committedChunks) continue

                val descriptor = chunks[index]
                val length = descriptor.lengthBytes.toInt()
                val read = when (
                    val result = attempt(AssetErrorKind.SOURCE_FAILURE) {
                        source.readFully(descriptor.offsetBytes, buffer, 0, length)
                    }
                ) {
                    is Attempt.Err -> return onError(sessionId, result.error, abortProvider)
                    is Attempt.Ok -> result.value
                }
                val chunk = if (length == buffer.size) buffer else buffer.copyOf(length)
                if (read != length || !verifier.verifyChunk(descriptor, chunk)) {
                    return onError(
                        sessionId,
                        AssetTransferError(AssetErrorKind.SOURCE_CONTENT_CHANGED, "Source content no longer matches the manifest."),
                        abortProvider,
                    )
                }
                when (val uploaded = provider.uploadChunk(AssetChunkUpload(sessionId, index, chunk))) {
                    is ProviderOperationResult.Failure -> return onError(sessionId, uploaded.error, abortProvider)
                    is ProviderOperationResult.Success ->
                        session = advance(sessionId, AssetTransferEvent.ChunkCommitted(index))
                }
            }
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

    // -------------------------------------------------------------- download

    private suspend fun driveDownload(initial: AssetTransferSession, sink: AssetSink): AssetTransferOutcome {
        val sessionId = initial.sessionId
        var session = initial
        terminalOutcome(session)?.let { return it }
        val discardSink: suspend () -> Unit = { sink.discard() }
        val manifest = session.manifest

        (attempt(AssetErrorKind.SINK_FAILURE) { sink.reserve(manifest.sizeBytes) } as? Attempt.Err)
            ?.let { return onError(sessionId, it.error, discardSink) }
        session = advance(sessionId, AssetTransferEvent.Start)

        if (session.phase == AssetTransferPhase.TRANSFERRING) {
            for (index in session.missingChunks) {
                currentCoroutineContext().ensureActive()
                session = reload(sessionId)
                terminalOutcome(session)?.let { return it }
                if (index in session.committedChunks) continue

                val descriptor = manifest.chunkLayout.chunks[index]
                val bytes = when (val read = provider.readChunk(manifest.assetId, manifest.version, index)) {
                    is ProviderOperationResult.Failure -> return onError(sessionId, read.error, discardSink)
                    is ProviderOperationResult.Success -> read.value
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
                session = advance(sessionId, AssetTransferEvent.ChunkCommitted(index))
            }
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

    // --------------------------------------------------------------- helpers

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
            Attempt.Err(AssetTransferError(failureKind, "Local asset I/O failed.", e))
        }
}
