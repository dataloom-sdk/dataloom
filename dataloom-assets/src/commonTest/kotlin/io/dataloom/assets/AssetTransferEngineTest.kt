package io.dataloom.assets

import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssetTransferEngineTest {

    private val digests = platformDigests()
    private val chunkSize = 1_024

    private fun engine(
        provider: AssetProvider,
        store: AssetTransferSessionStore,
        chunk: Int = chunkSize,
        verifyBuffer: Int = 256,
    ) = AssetTransferEngine(provider, store, digests, chunkSizeBytes = chunk, verifyBufferBytes = verifyBuffer)

    private val mediaType = octetStream

    /** Runs the block as a *new process*: fresh engine, same durable store and provider. */
    private fun restarted(provider: AssetProvider, store: AssetTransferSessionStore) = engine(provider, store)

    private suspend fun uploaded(provider: AssetProvider, asset: TestAsset, session: String = "seed") {
        val outcome = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId(session), asset.assetId, asset.version, mediaType, InMemoryAssetSource(asset.bytes))
        assertIs<AssetTransferOutcome.Completed>(outcome)
    }

    // ----------------------------------------------------------- happy paths

    @Test
    fun `upload then download reproduces the bytes exactly`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val bytes = patternBytes(10_000)
        val up = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("up"), AssetId("doc"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(up)
        assertEquals(AssetTransferPhase.COMPLETED, up.session.phase)
        assertEquals(10, up.session.manifest.chunkLayout.chunkCount)
        assertEquals(digests.digest(DigestAlgorithm.SHA_256, bytes), up.session.manifest.checksum)

        val sink = InMemoryAssetSink()
        val down = engine(provider, InMemoryAssetTransferSessionStore())
            .download(sessionId("down"), AssetId("doc"), null, sink)
        assertIs<AssetTransferOutcome.Completed>(down)
        assertContentEquals(bytes, sink.snapshot())
        assertEquals(up.session.manifest, down.session.manifest)
    }

    @Test
    fun `an asset smaller than one chunk and an exact multiple both round trip`() = runTest {
        for (size in listOf(1, 1_023, 1_024, 2_048, 2_049)) {
            val provider = InMemoryAssetProvider(digests)
            val bytes = patternBytes(size, seed = size)
            val up = engine(provider, InMemoryAssetTransferSessionStore())
                .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
            assertIs<AssetTransferOutcome.Completed>(up, "size $size")
            val sink = InMemoryAssetSink()
            assertIs<AssetTransferOutcome.Completed>(
                engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d"), AssetId("a"), 1, sink),
            )
            assertContentEquals(bytes, sink.snapshot(), "size $size")
        }
    }

    @Test
    fun `the requested chunk size is clamped into the provider's bounds`() = runTest {
        val provider = InMemoryAssetProvider(digests, chunkSizeBounds = AssetChunkSizeBounds(2_000, 3_000))
        val up = engine(provider, InMemoryAssetTransferSessionStore(), chunk = 100)
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(10_000)))
        assertIs<AssetTransferOutcome.Completed>(up)
        assertEquals(2_000L, up.session.manifest.chunkLayout.chunks.first().lengthBytes)
    }

    @Test
    fun `a completed session replays as completed without touching the provider again`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val e = engine(provider, store)
        val source = InMemoryAssetSource(patternBytes(3_000))
        val first = e.upload(sessionId("u"), AssetId("a"), 1, mediaType, source)
        assertIs<AssetTransferOutcome.Completed>(first)
        val calls = provider.openCalls
        val second = e.upload(sessionId("u"), AssetId("a"), 1, mediaType, source)
        assertIs<AssetTransferOutcome.Completed>(second)
        assertEquals(calls, provider.openCalls)
    }

    @Test
    fun `download of a specific version and of the latest version`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val v1 = testAsset(id = "doc", version = 1, size = 3_000, seed = 1)
        val v2 = testAsset(id = "doc", version = 2, size = 3_000, seed = 2)
        uploaded(provider, v1, "s1")
        uploaded(provider, v2, "s2")
        val latest = InMemoryAssetSink()
        engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d-latest"), AssetId("doc"), null, latest)
        assertContentEquals(v2.bytes, latest.snapshot())
        val first = InMemoryAssetSink()
        engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d-v1"), AssetId("doc"), 1, first)
        assertContentEquals(v1.bytes, first.snapshot())
    }

    // ---------------------------------------------------------------- resume

    @Test
    fun `an interrupted upload resumes without resending committed chunks`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(10_000)
        // The network drops after three chunks have been committed.
        provider.interceptUploadChunk = { request ->
            if (request.index == 3) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null
        }
        val first = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(setOf(0, 1, 2), first.session.committedChunks)
        assertEquals(AssetTransferPhase.TRANSFERRING, first.session.phase)
        assertEquals(Recoverability.RECOVERABLE, first.error.recoverability)

        // A restarted process: fresh engine, same durable store and provider, network back.
        provider.interceptUploadChunk = { null }
        provider.uploadAttempts.clear()
        val second = restarted(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(second)
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9), provider.uploadAttempts, "only the missing chunks are sent")
        val sink = InMemoryAssetSink()
        engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d"), AssetId("a"), 1, sink)
        assertContentEquals(bytes, sink.snapshot())
    }

    @Test
    fun `an upload resumes from the provider's view when the local session record was lost`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val bytes = patternBytes(10_000)
        provider.interceptUploadChunk = { if (it.index == 6) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        assertIs<AssetTransferOutcome.Interrupted>(
            engine(provider, InMemoryAssetTransferSessionStore())
                .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes)),
        )
        provider.interceptUploadChunk = { null }
        provider.uploadAttempts.clear()
        // Brand-new store: the local record is gone, the provider still holds six chunks.
        val outcome = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(listOf(6, 7, 8, 9), provider.uploadAttempts)
    }

    @Test
    fun `when the provider lost chunks the local record is reconciled and they are resent`() = runTest {
        val real = InMemoryAssetProvider(digests)
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(5_000)
        val flaky = InterceptingProvider(real)
        flaky.interceptUploadChunk = { if (it.index == 3) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        val first = engine(flaky, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertEquals(setOf(0, 1, 2), (first as AssetTransferOutcome.Interrupted).session.committedChunks)

        // The provider restarted with an empty state (a stale local session).
        val amnesiac = InterceptingProvider(InMemoryAssetProvider(digests))
        val second = engine(amnesiac, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(second)
        assertEquals((0 until 5).toList(), amnesiac.uploadAttempts, "everything the provider lost is resent")
    }

    @Test
    fun `cancelling the calling coroutine mid-transfer leaves a resumable session`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(10_000)
        val source = RecordingSource(InMemoryAssetSource(bytes))
        val reachedChunkThree = CompletableDeferred<Unit>()
        provider.interceptUploadChunk = { request ->
            if (request.index == 3) {
                reachedChunkThree.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            null
        }
        val job = launch { engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, source) }
        reachedChunkThree.await()
        job.cancel()
        job.join()

        val stored = store.load(sessionId("u"))!!
        assertEquals(AssetTransferPhase.TRANSFERRING, stored.phase, "cooperative cancellation is not a terminal state")
        assertEquals(setOf(0, 1, 2), stored.committedChunks)

        provider.interceptUploadChunk = { null }
        provider.uploadAttempts.clear()
        val resumed = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(resumed)
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9), provider.uploadAttempts)
    }

    @Test
    fun `an interrupted download resumes without re-downloading committed chunks`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val asset = testAsset(id = "doc", size = 10_000)
        uploaded(provider, asset)
        val store = InMemoryAssetTransferSessionStore()
        val sink = InMemoryAssetSink()
        provider.interceptReadChunk = { index, bytes ->
            if (index == 4) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else ProviderOperationResult.Success(bytes)
        }
        provider.readChunkCalls.clear()
        val first = engine(provider, store).download(sessionId("d"), asset.assetId, 1, sink)
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(setOf(0, 1, 2, 3), first.session.committedChunks)

        provider.interceptReadChunk = { _, bytes -> ProviderOperationResult.Success(bytes) }
        provider.readChunkCalls.clear()
        val second = restarted(provider, store).download(sessionId("d"), asset.assetId, 1, sink)
        assertIs<AssetTransferOutcome.Completed>(second)
        assertEquals(listOf(4, 5, 6, 7, 8, 9), provider.readChunkCalls)
        assertContentEquals(asset.bytes, sink.snapshot())
    }

    @Test
    fun `a resumed download whose staged bytes were lost fails verification and never completes`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val asset = testAsset(id = "doc", size = 10_000)
        uploaded(provider, asset)
        val store = InMemoryAssetTransferSessionStore()
        provider.interceptReadChunk = { index, bytes ->
            if (index == 5) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else ProviderOperationResult.Success(bytes)
        }
        assertIs<AssetTransferOutcome.Interrupted>(
            engine(provider, store).download(sessionId("d"), asset.assetId, 1, InMemoryAssetSink()),
        )
        provider.interceptReadChunk = { _, bytes -> ProviderOperationResult.Success(bytes) }
        // The process died and its temp storage went with it: a fresh, empty sink.
        val freshSink = InMemoryAssetSink()
        val outcome = restarted(provider, store).download(sessionId("d"), asset.assetId, 1, freshSink)
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.OBJECT_DIGEST_MISMATCH, outcome.session.failure)
        assertEquals(0, freshSink.snapshot().size, "the failed download is discarded")
    }

    // ------------------------------------------------------------- integrity

    @Test
    fun `a chunk corrupted in transit on upload is rejected and succeeds on retransmit`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(5_000)
        var corruptOnce = true
        provider.interceptUploadChunk = { request ->
            if (request.index == 2 && corruptOnce) {
                corruptOnce = false
                request.bytes[0] = (request.bytes[0] + 1).toByte() // flips a byte in the engine's own buffer copy
                null
            } else {
                null
            }
        }
        val first = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(AssetErrorKind.CHUNK_DIGEST_MISMATCH.code, first.error.code)
        assertTrue(2 !in first.session.committedChunks, "the corrupted chunk must not be recorded as committed")

        val second = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(second)
        val sink = InMemoryAssetSink()
        engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d"), AssetId("a"), 1, sink)
        assertContentEquals(bytes, sink.snapshot())
    }

    @Test
    fun `a source that changed since the session began fails and aborts the provider session`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests, AssetQuota(maxTotalBytes = 5_000)))
        val store = InMemoryAssetTransferSessionStore()
        val original = patternBytes(5_000)
        provider.interceptUploadChunk = { if (it.index == 2) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        assertIs<AssetTransferOutcome.Interrupted>(
            engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(original)),
        )
        provider.interceptUploadChunk = { null }
        val edited = original.copyOf().also { it[4_500] = (it[4_500] + 1).toByte() } // inside chunk 4
        val outcome = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(edited))
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.SOURCE_CONTENT_CHANGED, outcome.session.failure)
        assertEquals(1, provider.abortCalls, "the provider session is cleaned up")
        // The reservation is released: the same quota admits a fresh upload.
        val fresh = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("fresh"), AssetId("b"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000, seed = 3)))
        assertIs<AssetTransferOutcome.Completed>(fresh)
    }

    @Test
    fun `a corrupted download chunk is rejected before it reaches the sink and is retried`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val asset = testAsset(id = "doc", size = 5_000)
        uploaded(provider, asset)
        var corruptOnce = true
        provider.interceptReadChunk = { index, bytes ->
            if (index == 1 && corruptOnce) {
                corruptOnce = false
                ProviderOperationResult.Success(bytes.also { it[3] = (it[3] + 1).toByte() })
            } else {
                ProviderOperationResult.Success(bytes)
            }
        }
        val staged = InMemoryAssetSink()
        val sink = RecordingSink(staged)
        val store = InMemoryAssetTransferSessionStore()
        val first = engine(provider, store).download(sessionId("d"), asset.assetId, 1, sink)
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(AssetErrorKind.CHUNK_DIGEST_MISMATCH.code, first.error.code)
        assertEquals(listOf(0L), sink.writePositions, "the corrupted chunk was never written")
        val second = engine(provider, store).download(sessionId("d"), asset.assetId, 1, sink)
        assertIs<AssetTransferOutcome.Completed>(second)
        assertContentEquals(asset.bytes, staged.snapshot())
    }

    @Test
    fun `a whole-object mismatch fails the download and discards the sink so nothing corrupt is exposed`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val asset = testAsset(id = "doc", size = 5_000)
        uploaded(provider, asset)
        // Every chunk verifies against its own descriptor, but the manifest's whole-object digest is wrong.
        provider.interceptReadManifest = { it.copy(checksum = digests.digest(DigestAlgorithm.SHA_256, byteArrayOf(1, 2, 3))) }
        val sink = RecordingSink(InMemoryAssetSink())
        val outcome = engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d"), asset.assetId, 1, sink)
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.OBJECT_DIGEST_MISMATCH, outcome.session.failure)
        assertEquals(AssetTransferPhase.FAILED, outcome.session.phase)
        assertEquals(1, sink.discards)
        assertEquals(0, InMemoryAssetSink().snapshot().size)
    }

    @Test
    fun `an upload whose whole-object digest the provider rejects fails without a completed asset`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        provider.interceptComplete = { failure(AssetErrorKind.OBJECT_DIGEST_MISMATCH) }
        val outcome = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(3_000)))
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.OBJECT_DIGEST_MISMATCH, outcome.session.failure)
        assertEquals(1, provider.abortCalls)
    }

    @Test
    fun `a transient failure during completion leaves a VERIFYING session that resumes to completion`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(3_000)
        provider.interceptComplete = { failure(AssetErrorKind.PROVIDER_UNAVAILABLE) }
        val first = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(AssetTransferPhase.VERIFYING, first.session.phase)

        provider.interceptComplete = { null }
        provider.uploadAttempts.clear()
        val second = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(second)
        assertEquals(emptyList(), provider.uploadAttempts, "no chunk is resent when only verification was pending")
    }

    // ------------------------------------------------------------------ quota

    @Test
    fun `an upload over the provider's quota fails terminally and creates no committed asset`() = runTest {
        val provider = InMemoryAssetProvider(digests, AssetQuota(maxAssetSizeBytes = 4_999))
        val store = InMemoryAssetTransferSessionStore()
        val outcome = engine(provider, store)
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000)))
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.QUOTA_EXCEEDED, outcome.session.failure)
        assertEquals(AssetErrorKind.QUOTA_EXCEEDED.code, outcome.error.code)
        assertEquals(AssetTransferPhase.FAILED, store.load(sessionId("u"))!!.phase)
        assertIs<ProviderOperationResult.Failure>(provider.readManifest(AssetId("a"), null))
    }

    @Test
    fun `quota reserved by a cancelled upload is released for the next one`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests, AssetQuota(maxTotalBytes = 6_000)))
        val store = InMemoryAssetTransferSessionStore()
        provider.interceptUploadChunk = { if (it.index == 1) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        val stalled = engine(provider, store)
            .upload(sessionId("first"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000)))
        assertIs<AssetTransferOutcome.Interrupted>(stalled)
        provider.interceptUploadChunk = { null }

        // While "first" holds its reservation, "second" cannot fit.
        val blocked = engine(provider, store)
            .upload(sessionId("second"), AssetId("b"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000, seed = 2)))
        assertIs<AssetTransferOutcome.Failed>(blocked)
        assertEquals(AssetErrorKind.QUOTA_EXCEEDED, blocked.session.failure)

        val cancelled = engine(provider, store).cancel(sessionId("first"))
        assertIs<AssetTransferOutcome.Cancelled>(cancelled)
        val admitted = engine(provider, store)
            .upload(sessionId("third"), AssetId("b"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000, seed = 2)))
        assertIs<AssetTransferOutcome.Completed>(admitted)
    }

    @Test
    fun `a download into a sink without room fails on the preflight before any byte is written`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val asset = testAsset(id = "doc", size = 5_000)
        uploaded(provider, asset)
        val sink = RecordingSink(InMemoryAssetSink(maxBytes = 4_999))
        val outcome = engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d"), asset.assetId, 1, sink)
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.QUOTA_EXCEEDED, outcome.session.failure)
        assertEquals(emptyList(), sink.writePositions)
    }

    @Test
    fun `a sink that fills up mid-download fails terminally and is discarded`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val asset = testAsset(id = "doc", size = 5_000)
        uploaded(provider, asset)
        val inner = InMemoryAssetSink()
        val full = object : AssetSink by inner {
            var writes = 0
            override suspend fun write(position: Long, source: ByteArray, sourceOffset: Int, length: Int) {
                if (++writes == 3) throw AssetSinkFullException("disk full")
                inner.write(position, source, sourceOffset, length)
            }
        }
        val outcome = engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d"), asset.assetId, 1, full)
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.QUOTA_EXCEEDED, outcome.session.failure)
        assertEquals(setOf(0, 1), outcome.session.committedChunks)
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun `cancel moves an interrupted upload to CANCELLED and aborts the provider session`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        provider.interceptUploadChunk = { if (it.index == 2) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        assertIs<AssetTransferOutcome.Interrupted>(
            engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000))),
        )
        val outcome = engine(provider, store).cancel(sessionId("u"))
        assertIs<AssetTransferOutcome.Cancelled>(outcome)
        assertEquals(AssetTransferPhase.CANCELLED, store.load(sessionId("u"))!!.phase)
        assertEquals(1, provider.abortCalls)

        // Idempotent: a duplicate cancel is harmless.
        assertIs<AssetTransferOutcome.Cancelled>(engine(provider, store).cancel(sessionId("u")))

        // A cancelled session stays cancelled: "resuming" it does not restart the transfer.
        provider.uploadAttempts.clear()
        val again = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000)))
        assertIs<AssetTransferOutcome.Cancelled>(again)
        assertEquals(emptyList(), provider.uploadAttempts)
    }

    @Test
    fun `a cancel issued while a transfer is running is noticed between chunks`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val e = engine(provider, store)
        provider.interceptUploadChunk = { request ->
            if (request.index == 2) e.cancel(sessionId("u")) // an outside actor cancels mid-transfer
            null
        }
        val outcome = e.upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(10_000)))
        assertIs<AssetTransferOutcome.Cancelled>(outcome)
        assertEquals(listOf(0, 1, 2), provider.uploadAttempts, "no chunk is sent after the cancel is observed")
    }

    @Test
    fun `cancelling a download discards the sink`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val asset = testAsset(id = "doc", size = 5_000)
        uploaded(provider, asset)
        val store = InMemoryAssetTransferSessionStore()
        provider.interceptReadChunk = { index, bytes ->
            if (index == 2) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else ProviderOperationResult.Success(bytes)
        }
        val sink = RecordingSink(InMemoryAssetSink())
        assertIs<AssetTransferOutcome.Interrupted>(engine(provider, store).download(sessionId("d"), asset.assetId, 1, sink))
        val outcome = engine(provider, store).cancel(sessionId("d"), sink)
        assertIs<AssetTransferOutcome.Cancelled>(outcome)
        assertEquals(1, sink.discards)
    }

    @Test
    fun `cancelling a completed transfer never falsely cancels it`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val e = engine(provider, store)
        assertIs<AssetTransferOutcome.Completed>(
            e.upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(3_000))),
        )
        assertIs<AssetTransferOutcome.Completed>(e.cancel(sessionId("u")))
        assertEquals(0, provider.abortCalls)
        assertEquals(AssetTransferPhase.COMPLETED, store.load(sessionId("u"))!!.phase)
        assertTrue(provider.readManifest(AssetId("a"), null) is ProviderOperationResult.Success)
    }

    @Test
    fun `cancelling an unknown session reports NotStarted`() = runTest {
        val outcome = engine(InMemoryAssetProvider(digests), InMemoryAssetTransferSessionStore()).cancel(sessionId("nope"))
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(AssetErrorKind.SESSION_NOT_FOUND.code, outcome.error.code)
    }

    // --------------------------------------------------------- error handling

    @Test
    fun `a non-recoverable provider error fails the session and aborts the provider session`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        provider.interceptUploadChunk = { failure(AssetErrorKind.PROVIDER_REJECTED) }
        val outcome = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(3_000)))
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.PROVIDER_REJECTED, outcome.session.failure)
        assertEquals(1, provider.abortCalls)
    }

    @Test
    fun `a foreign non-recoverable error is recorded as a provider rejection`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        provider.interceptUploadChunk = { ProviderOperationResult.Failure(ForeignError(Recoverability.NON_RECOVERABLE)) }
        val outcome = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(3_000)))
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.PROVIDER_REJECTED, outcome.session.failure)
    }

    @Test
    fun `an error of unknown recoverability never destroys a resumable session`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        provider.interceptUploadChunk = { ProviderOperationResult.Failure(ForeignError(Recoverability.UNKNOWN)) }
        val outcome = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(3_000)))
        assertIs<AssetTransferOutcome.Interrupted>(outcome)
        assertEquals(AssetTransferPhase.TRANSFERRING, outcome.session.phase)
    }

    @Test
    fun `an unreadable source interrupts a session that is already started but refuses to start a new one`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val broken = object : AssetSource {
            override suspend fun sizeBytes(): Long = 5_000
            override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
                throw IllegalStateException("EIO")
        }
        val outcome = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, broken)
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(AssetErrorKind.SOURCE_FAILURE.code, outcome.error.code)
    }

    @Test
    fun `a source that fails after the session exists interrupts it and is resumable`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(5_000) // five 1 KiB chunks, so preparation makes exactly five reads
        val inner = InMemoryAssetSource(bytes)
        var reads = 0
        val failsDuringTransfer = object : AssetSource by inner {
            override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
                reads++
                if (reads == 8) throw IllegalStateException("EIO") // third read of the transfer phase
                return inner.read(position, destination, destinationOffset, length)
            }
        }
        val outcome = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, failsDuringTransfer)
        assertIs<AssetTransferOutcome.Interrupted>(outcome)
        assertEquals(AssetErrorKind.SOURCE_FAILURE.code, outcome.error.code)
        assertEquals(AssetTransferPhase.TRANSFERRING, outcome.session.phase)
        assertEquals(setOf(0, 1), outcome.session.committedChunks)

        val resumed = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, inner)
        assertIs<AssetTransferOutcome.Completed>(resumed)
    }

    @Test
    fun `an empty source is refused without creating a session`() = runTest {
        val store = InMemoryAssetTransferSessionStore()
        val outcome = engine(InMemoryAssetProvider(digests), store)
            .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(ByteArray(0)))
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(AssetErrorKind.EMPTY_ASSET.code, outcome.error.code)
        assertEquals(null, store.load(sessionId("u")))
    }

    @Test
    fun `downloading an unknown asset reports NotStarted with ASSET_NOT_FOUND`() = runTest {
        val outcome = engine(InMemoryAssetProvider(digests), InMemoryAssetTransferSessionStore())
            .download(sessionId("d"), AssetId("ghost"), null, InMemoryAssetSink())
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND.code, outcome.error.code)
    }

    @Test
    fun `reusing a session id for the wrong direction or asset is a caller error`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val store = InMemoryAssetTransferSessionStore()
        val e = engine(provider, store)
        e.upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(3_000)))
        assertFailsWith<IllegalArgumentException> { e.download(sessionId("u"), AssetId("a"), 1, InMemoryAssetSink()) }
        assertFailsWith<IllegalArgumentException> {
            e.upload(sessionId("u"), AssetId("other"), 1, mediaType, InMemoryAssetSource(patternBytes(3_000)))
        }
        assertFailsWith<IllegalArgumentException> {
            e.upload(sessionId("u"), AssetId("a"), 2, mediaType, InMemoryAssetSource(patternBytes(3_000)))
        }
    }

    // ------------------------------------------------------- concurrency, memory

    @Test
    fun `two concurrent calls for the same session both complete and the asset is intact`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        // Yield at every chunk so the two transfers genuinely interleave.
        provider.interceptUploadChunk = { kotlinx.coroutines.yield(); null }
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(20_000)
        val results = (1..2).map {
            async { engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes)) }
        }.awaitAll()
        assertTrue(results.all { it is AssetTransferOutcome.Completed }, "outcomes: $results")
        val sink = InMemoryAssetSink()
        engine(provider, InMemoryAssetTransferSessionStore()).download(sessionId("d"), AssetId("a"), 1, sink)
        assertContentEquals(bytes, sink.snapshot())
    }

    @Test
    fun `a large asset moves through bounded buffers regardless of its size`() = runTest {
        val size = 6 * 1024 * 1024
        val bytes = patternBytes(size)
        val chunk = 64 * 1024
        val verifyBuffer = 16 * 1024
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val source = RecordingSource(InMemoryAssetSource(bytes))
        val up = AssetTransferEngine(provider, InMemoryAssetTransferSessionStore(), digests, chunk, verifyBufferBytes = verifyBuffer)
            .upload(sessionId("big-up"), AssetId("big"), 1, mediaType, source)
        assertIs<AssetTransferOutcome.Completed>(up)
        assertEquals(96, up.session.manifest.chunkLayout.chunkCount)
        assertTrue(source.maxReadLength <= chunk, "source read ${source.maxReadLength} > chunk $chunk")
        assertTrue(provider.maxUploadedChunkSize <= chunk, "provider got ${provider.maxUploadedChunkSize} > chunk $chunk")

        val staged = InMemoryAssetSink()
        val sink = RecordingSink(staged)
        val down = AssetTransferEngine(provider, InMemoryAssetTransferSessionStore(), digests, chunk, verifyBufferBytes = verifyBuffer)
            .download(sessionId("big-down"), AssetId("big"), 1, sink)
        assertIs<AssetTransferOutcome.Completed>(down)
        assertTrue(provider.maxReadChunkSize <= chunk, "provider served ${provider.maxReadChunkSize} > chunk $chunk")
        assertTrue(sink.maxWriteLength <= chunk, "sink write ${sink.maxWriteLength} > chunk $chunk")
        assertTrue(sink.maxReadLength <= verifyBuffer, "verification read ${sink.maxReadLength} > buffer $verifyBuffer")
        assertContentEquals(bytes, staged.snapshot())
    }

    @Test
    fun `chunk size scales the number of chunks and not the per-chunk memory`() = runTest {
        val bytes = patternBytes(100_000)
        for (chunk in listOf(1_000, 10_000, 50_000)) {
            val provider = InterceptingProvider(InMemoryAssetProvider(digests))
            val outcome = engine(provider, InMemoryAssetTransferSessionStore(), chunk = chunk)
                .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
            assertIs<AssetTransferOutcome.Completed>(outcome)
            assertEquals(100_000 / chunk, outcome.session.manifest.chunkLayout.chunkCount)
            assertTrue(provider.maxUploadedChunkSize <= chunk)
        }
    }

    @Test
    fun `coroutine cancellation is never swallowed as a transfer failure`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        provider.interceptUploadChunk = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> {
            engine(provider, InMemoryAssetTransferSessionStore())
                .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(3_000)))
        }
    }
}
