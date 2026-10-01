package io.dataloom.assets

import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proof suite for [AssetTransferEngine]'s `maxConcurrency` > 1 mode
 * (FR-ASSET-006: parallel chunk transfer with concurrency limits and
 * fairness). Uses [ConcurrencyProbe] (a provider decorator) to make
 * concurrency observable rather than assumed: a chunk call blocks until a
 * target number of calls are genuinely overlapping, and throws immediately if
 * more than the configured limit are ever in flight at once.
 *
 * Every positive test here has a matching falsification: run the exact same
 * scenario with `maxConcurrency = 1` (the pre-existing sequential path) and
 * confirm it does *not* reach the required overlap — see "sequential mode
 * never overlaps chunks" below. Without that control, a test that merely
 * "doesn't crash" would be worthless as proof.
 */
class ParallelAssetTransferEngineTest {

    private val digests = platformDigests()
    private val mediaType = octetStream
    private val chunkSize = 100

    private fun engine(provider: AssetProvider, maxConcurrency: Int) = AssetTransferEngine(
        provider,
        InMemoryAssetTransferSessionStore(),
        digests,
        chunkSizeBytes = chunkSize,
        maxConcurrency = maxConcurrency,
    )

    // --------------------------------------------------------- upload proofs

    @Test
    fun `parallel upload runs exactly maxConcurrency chunks at once and never more`() = runTest {
        val limit = 3
        val chunkCount = 6 // an exact multiple of `limit`: two full waves, no partial-wave edge case
        val probe = ConcurrencyProbe(limit)
        val provider = ConcurrencyProbingProvider(InMemoryAssetProvider(digests), probe)
        val bytes = patternBytes(chunkSize * chunkCount)

        val outcome = withTimeout(10_000) {
            engine(provider, maxConcurrency = limit)
                .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        }

        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(limit, probe.maxObserved, "expected the full concurrency allowance to be used at least once")
    }

    @Test
    fun `parallel download runs exactly maxConcurrency chunks at once and never more`() = runTest {
        val limit = 3
        val chunkCount = 6
        val asset = testAsset(id = "dl-asset", size = chunkSize * chunkCount, chunkSize = chunkSize)
        val realProvider = InMemoryAssetProvider(digests)
        uploaded(realProvider, asset)

        val probe = ConcurrencyProbe(limit)
        val provider = ConcurrencyProbingProvider(realProvider, probe)
        val sink = InMemoryAssetSink()

        val outcome = withTimeout(10_000) {
            engine(provider, maxConcurrency = limit).download(sessionId("d"), asset.assetId, 1, sink)
        }

        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(limit, probe.maxObserved, "expected the full concurrency allowance to be used at least once")
        assertContentEquals(asset.bytes, sink.snapshot())
    }

    @Test
    fun `sequential mode never overlaps chunks and thereby falsifies the parallel proofs above`() = runTest {
        // Same scenario as the upload proof, but maxConcurrency = 1: the original,
        // unchanged sequential path. A probe demanding 3-way overlap can never be
        // satisfied by it, so the call hangs on the overlap barrier and the
        // surrounding timeout (scheduled via `delay`, which runTest auto-advances
        // once nothing else can make progress) cancels it. This is the "naive
        // non-concurrent stub" the proof discipline calls for: the exact same
        // probe and assertions that pass for maxConcurrency = 3 above must fail
        // here, or the probe would not actually be proving anything.
        val limit = 3
        val chunkCount = 6
        val probe = ConcurrencyProbe(limit)
        val provider = ConcurrencyProbingProvider(InMemoryAssetProvider(digests), probe)
        val bytes = patternBytes(chunkSize * chunkCount)

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(5_000) {
                engine(provider, maxConcurrency = 1)
                    .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
            }
        }
        assertEquals(1, probe.maxObserved, "sequential mode must never run more than one chunk at a time")
    }

    // ------------------------------------------------------------- fail-fast

    @Test
    fun `a failing chunk cancels the other in-flight uploads and commits nothing past it`() = runTest {
        val limit = 3
        val chunkCount = 6
        val cancelledIndices = ArrayList<Int>()
        val mutex = Mutex()
        var blockedCount = 0
        // Completes only once chunks 0 and 2 have both genuinely reached their blocking point.
        // Without this, a single-threaded cooperative dispatcher can run chunk 1 (which fails
        // synchronously, no suspension) to completion before chunk 2's launch is even dispatched,
        // so chunk 2 would never truly be "in flight" and this would not be proof of anything.
        val bothBlocked = CompletableDeferred<Unit>()

        val delegate = InMemoryAssetProvider(digests)
        val provider = InterceptingProvider(delegate)
        provider.interceptUploadChunk = { request ->
            when (request.index) {
                1 -> {
                    bothBlocked.await()
                    failure(AssetErrorKind.PROVIDER_REJECTED)
                }
                0, 2 -> {
                    mutex.withLock { blockedCount++; if (blockedCount == 2) bothBlocked.complete(Unit) }
                    try {
                        awaitCancellation()
                    } catch (e: Throwable) {
                        mutex.withLock { cancelledIndices += request.index }
                        throw e
                    }
                }
                else -> null
            }
        }

        val bytes = patternBytes(chunkSize * chunkCount)
        val outcome = withTimeout(10_000) {
            engine(provider, maxConcurrency = limit)
                .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        }

        assertIs<AssetTransferOutcome.Failed>(outcome, "outcome was $outcome")
        assertEquals(AssetErrorKind.PROVIDER_REJECTED, outcome.session.failure)
        // Chunks 0 and 2 were genuinely in flight (blocked, not finished) when chunk 1 failed, so
        // they must never have committed, and chunks 3, 4, 5 (queued behind the first wave) must
        // never even have been attempted.
        assertTrue(0 !in outcome.session.committedChunks)
        assertTrue(2 !in outcome.session.committedChunks)
        for (queued in listOf(3, 4, 5)) {
            assertTrue(queued !in provider.uploadAttempts, "chunk $queued should never have been attempted")
        }
        mutex.withLock {
            assertEquals(setOf(0, 2), cancelledIndices.toSet(), "both blocked chunks must have been cancelled")
        }
    }

    @Test
    fun `a failing chunk cancels the other in-flight downloads and commits nothing past it`() = runTest {
        val limit = 3
        val chunkCount = 6
        val asset = testAsset(id = "dl-fail", size = chunkSize * chunkCount, chunkSize = chunkSize)
        val realProvider = InMemoryAssetProvider(digests)
        uploaded(realProvider, asset)

        val cancelledIndices = ArrayList<Int>()
        val mutex = Mutex()
        var blockedCount = 0
        // See the matching upload test for why both blocked chunks must be synchronized before
        // chunk 1 is allowed to fail.
        val bothBlocked = CompletableDeferred<Unit>()
        val provider = InterceptingProvider(realProvider)
        provider.interceptReadChunk = { index, bytes ->
            when (index) {
                1 -> {
                    bothBlocked.await()
                    failure(AssetErrorKind.PROVIDER_REJECTED)
                }
                0, 2 -> {
                    mutex.withLock { blockedCount++; if (blockedCount == 2) bothBlocked.complete(Unit) }
                    try {
                        awaitCancellation()
                    } catch (e: Throwable) {
                        mutex.withLock { cancelledIndices += index }
                        throw e
                    }
                }
                else -> ProviderOperationResult.Success(bytes)
            }
        }

        val sink = InMemoryAssetSink()
        val outcome = withTimeout(10_000) {
            engine(provider, maxConcurrency = limit).download(sessionId("d"), asset.assetId, 1, sink)
        }

        assertIs<AssetTransferOutcome.Failed>(outcome, "outcome was $outcome")
        assertEquals(AssetErrorKind.PROVIDER_REJECTED, outcome.session.failure)
        assertTrue(0 !in outcome.session.committedChunks)
        assertTrue(2 !in outcome.session.committedChunks)
        for (queued in listOf(3, 4, 5)) {
            assertTrue(queued !in provider.readChunkCalls, "chunk $queued should never have been attempted")
        }
        mutex.withLock {
            assertEquals(setOf(0, 2), cancelledIndices.toSet(), "both blocked chunks must have been cancelled")
        }
    }

    // ------------------------------------------------------------- fairness

    @Test
    fun `one slow chunk does not starve the others sharing the other concurrency slots`() = runTest {
        val limit = 2
        val chunkCount = 5
        val releaseSlowChunk = CompletableDeferred<Unit>()
        val othersAttempted = mutableSetOf<Int>()
        val othersDone = CompletableDeferred<Unit>()
        val mutex = Mutex()
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        provider.interceptUploadChunk = { request ->
            if (request.index == 0) {
                // Occupies one of the two slots for the whole transfer; never completes on its own.
                releaseSlowChunk.await()
            } else {
                // Push-based, not polled: completes the instant the last of chunks 1..4 reaches the
                // provider, so this test suspends cleanly (rather than busy-spinning) while waiting,
                // which lets runTest's virtual clock advance its surrounding timeout if this hangs.
                mutex.withLock {
                    othersAttempted += request.index
                    if (othersAttempted.size == chunkCount - 1) othersDone.complete(Unit)
                }
            }
            null
        }

        val bytes = patternBytes(chunkSize * chunkCount)
        val upload = async {
            engine(provider, maxConcurrency = limit)
                .upload(sessionId("u"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes))
        }

        // The other concurrency slot must be free to drain chunks 1..4 to completion even though
        // chunk 0 never finishes: if the implementation instead waited for a whole "batch" of
        // `limit` chunks before admitting the next one, this would deadlock (and time out) with
        // chunk 0 never releasing its batch-mate.
        withTimeout(10_000) { othersDone.await() }
        assertEquals(setOf(1, 2, 3, 4), othersAttempted)

        releaseSlowChunk.complete(Unit)
        val outcome = withTimeout(10_000) { upload.await() }
        assertIs<AssetTransferOutcome.Completed>(outcome)
    }

    // ----------------------------------------------------------- end to end

    @Test
    fun `parallel upload then download round trips the exact bytes through the in-memory provider`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val bytes = patternBytes(chunkSize * 9 + 7)
        val up = engine(provider, maxConcurrency = 4)
            .upload(sessionId("u"), AssetId("rt"), 1, mediaType, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(up)

        val sink = InMemoryAssetSink()
        val down = engine(provider, maxConcurrency = 4)
            .download(sessionId("d"), AssetId("rt"), null, sink)
        assertIs<AssetTransferOutcome.Completed>(down)
        assertContentEquals(bytes, sink.snapshot())
    }

    private suspend fun uploaded(provider: AssetProvider, asset: TestAsset) {
        val outcome = AssetTransferEngine(provider, InMemoryAssetTransferSessionStore(), digests, chunkSizeBytes = chunkSize)
            .upload(sessionId("seed-${asset.assetId.value}"), asset.assetId, asset.version, mediaType, InMemoryAssetSource(asset.bytes))
        assertIs<AssetTransferOutcome.Completed>(outcome)
    }
}

/**
 * Tracks how many calls into a wrapped [AssetProvider]'s chunk operations are
 * concurrently in flight, making overlap observable instead of assumed.
 *
 * The first `limit` calls to [enter] block until exactly `limit` are
 * concurrently blocked there — a self-releasing barrier, not a fixed delay:
 * whichever call's entry brings the shared counter up to `limit` completes
 * the shared gate, releasing itself and every other call waiting on it. Every
 * call after that gate is open passes through [enter] immediately, so later
 * waves (and sequential-mode calls, which must never reach the gate at all)
 * are unaffected by it.
 *
 * [enter] throws immediately, synchronously, the instant more than `limit`
 * calls are concurrently inside it — not merely recorded for a later
 * assertion — so a concurrency-limit bug surfaces as a precise failure at the
 * offending call rather than a vague end-of-test mismatch.
 */
class ConcurrencyProbe(private val limit: Int) {
    private val mutex = Mutex()
    private var inFlight = 0

    /** Largest number of calls ever observed concurrently inside [enter]..[exit]. */
    var maxObserved: Int = 0
        private set

    private val gateOpen = CompletableDeferred<Unit>()

    suspend fun enter() {
        mutex.withLock {
            inFlight++
            check(inFlight <= limit) { "concurrency limit $limit exceeded: $inFlight calls in flight" }
            if (inFlight > maxObserved) maxObserved = inFlight
            if (inFlight == limit) gateOpen.complete(Unit)
        }
        gateOpen.await()
    }

    suspend fun exit() {
        mutex.withLock { inFlight-- }
    }
}

/** Wraps [delegate], routing [uploadChunk] and [readChunk] through [probe]. */
class ConcurrencyProbingProvider(
    private val delegate: AssetProvider,
    private val probe: ConcurrencyProbe,
) : AssetProvider by delegate {

    override suspend fun uploadChunk(request: AssetChunkUpload): ProviderOperationResult<AssetUploadStatus> {
        probe.enter()
        try {
            return delegate.uploadChunk(request)
        } finally {
            probe.exit()
        }
    }

    override suspend fun readChunk(assetId: AssetId, version: Long, index: Int): ProviderOperationResult<ByteArray> {
        probe.enter()
        try {
            return delegate.readChunk(assetId, version, index)
        } finally {
            probe.exit()
        }
    }
}
