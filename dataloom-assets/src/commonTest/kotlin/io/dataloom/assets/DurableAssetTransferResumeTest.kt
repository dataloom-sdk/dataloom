package io.dataloom.assets

import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The sequential engine over [DurableAssetTransferSessionStore] on an
 * [EncodingDurableStateStore]. Every "restart" builds a brand-new engine and a
 * brand-new store *instance* over the same persisted rows, so the only state
 * that survives is what was durably recorded through the codec.
 */
class DurableAssetTransferResumeTest {

    private val digests = platformDigests()
    private val media = octetStream

    private fun engine(provider: AssetProvider, rows: EncodingDurableStateStore) = AssetTransferEngine(
        provider, DurableAssetTransferSessionStore(rows), digests, chunkSizeBytes = 1_024, verifyBufferBytes = 256,
    )

    private val id = sessionId("u")

    private suspend fun persisted(rows: EncodingDurableStateStore, sid: AssetTransferSessionId = id): AssetTransferSession =
        checkNotNull(DurableAssetTransferSessionStore(rows).load(sid))

    // --------------------------------------------------------- crash and resume

    @Test
    fun `a crash between chunks resumes from the durable record and sends only what is missing`() = runTest {
        val rows = EncodingDurableStateStore()
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val bytes = patternBytes(10_000)
        provider.interceptUploadChunk = { if (it.index == 5) throw CrashException() else null }
        assertFailsWith<CrashException> {
            engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes))
        }
        val afterCrash = persisted(rows)
        assertEquals(AssetTransferPhase.TRANSFERRING, afterCrash.phase)
        assertEquals(setOf(0, 1, 2, 3, 4), afterCrash.committedChunks)

        // Restart: no shared objects with the crashed run except the rows.
        provider.interceptUploadChunk = { null }
        provider.uploadAttempts.clear()
        val outcome = engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(listOf(5, 6, 7, 8, 9), provider.uploadAttempts)
        assertEquals(AssetTransferPhase.COMPLETED, persisted(rows).phase)

        val sink = InMemoryAssetSink()
        assertIs<AssetTransferOutcome.Completed>(
            engine(provider, EncodingDurableStateStore()).download(sessionId("d"), AssetId("a"), 1, sink),
        )
        assertContentEquals(bytes, sink.snapshot())
    }

    @Test
    fun `a crash after the provider accepted a chunk but before it was recorded does not resend it`() = runTest {
        val rows = EncodingDurableStateStore()
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val bytes = patternBytes(10_000)
        // Writes: 1 create, 2 Start (the empty Reconcile writes nothing), then one per chunk. The 7th is chunk 4's record.
        rows.crashOnCompareAndSet = 7
        assertFailsWith<CrashException> {
            engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes))
        }
        assertEquals(setOf(0, 1, 2, 3), persisted(rows).committedChunks, "chunk 4 reached the provider but not the record")
        assertTrue(4 in provider.uploadsAccepted)

        provider.uploadAttempts.clear()
        val outcome = engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(listOf(5, 6, 7, 8, 9), provider.uploadAttempts, "the provider's committed set reconciled the record")
    }

    @Test
    fun `a session interrupted while VERIFYING resumes without resending any chunk`() = runTest {
        val rows = EncodingDurableStateStore()
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val bytes = patternBytes(4_000)
        provider.interceptComplete = { failure(AssetErrorKind.PROVIDER_UNAVAILABLE) }
        assertIs<AssetTransferOutcome.Interrupted>(
            engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes)),
        )
        val verifying = persisted(rows)
        assertEquals(AssetTransferPhase.VERIFYING, verifying.phase)
        assertTrue(verifying.isFullyCommitted)
        // What verification needs after a restart is in the record: the whole-object digest.
        assertEquals(digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, bytes), verifying.manifest.checksum)

        provider.interceptComplete = { null }
        provider.uploadAttempts.clear()
        assertIs<AssetTransferOutcome.Completed>(
            engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes)),
        )
        assertEquals(emptyList(), provider.uploadAttempts)
    }

    @Test
    fun `an interrupted download resumes after a restart and verifies the whole object`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val asset = testAsset(id = "doc", size = 10_000)
        engine(provider, EncodingDurableStateStore()).upload(sessionId("seed"), asset.assetId, 1, media, InMemoryAssetSource(asset.bytes))
        val rows = EncodingDurableStateStore()
        val sink = InMemoryAssetSink()
        provider.interceptReadChunk = { index, bytes ->
            if (index == 4) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else ProviderOperationResult.Success(bytes)
        }
        val did = sessionId("d")
        assertIs<AssetTransferOutcome.Interrupted>(engine(provider, rows).download(did, asset.assetId, 1, sink))
        assertEquals(setOf(0, 1, 2, 3), persisted(rows, did).committedChunks)

        provider.interceptReadChunk = { _, bytes -> ProviderOperationResult.Success(bytes) }
        provider.readChunkCalls.clear()
        assertIs<AssetTransferOutcome.Completed>(engine(provider, rows).download(did, asset.assetId, 1, sink))
        assertEquals(listOf(4, 5, 6, 7, 8, 9), provider.readChunkCalls)
        assertContentEquals(asset.bytes, sink.snapshot())
    }

    @Test
    fun `a download whose verification was cut short resumes from the sink without refetching chunks`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val asset = testAsset(id = "doc", size = 5_000)
        engine(provider, EncodingDurableStateStore()).upload(sessionId("seed"), asset.assetId, 1, media, InMemoryAssetSource(asset.bytes))
        val rows = EncodingDurableStateStore()
        val staged = InMemoryAssetSink()
        // The sink cannot be read back during verification (as if the process died there); the
        // engine reports that as an interruption, and the VERIFYING phase is already durable.
        val unreadable = object : AssetSink by staged {
            override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
                throw CrashException()
        }
        val did = sessionId("d")
        val first = engine(provider, rows).download(did, asset.assetId, 1, unreadable)
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(AssetErrorKind.SINK_FAILURE.code, first.error.code)
        assertEquals(AssetTransferPhase.VERIFYING, persisted(rows, did).phase)

        provider.readChunkCalls.clear()
        assertIs<AssetTransferOutcome.Completed>(engine(provider, rows).download(did, asset.assetId, 1, staged))
        assertEquals(emptyList(), provider.readChunkCalls)
        assertContentEquals(asset.bytes, staged.snapshot())
    }

    @Test
    fun `terminal states survive a restart and are not resumed`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests, AssetQuota(maxAssetSizeBytes = 100)))
        val rows = EncodingDurableStateStore()
        val failed = engine(provider, rows).upload(sessionId("q"), AssetId("a"), 1, media, InMemoryAssetSource(patternBytes(3_000)))
        assertIs<AssetTransferOutcome.Failed>(failed)
        val again = engine(provider, rows).upload(sessionId("q"), AssetId("a"), 1, media, InMemoryAssetSource(patternBytes(3_000)))
        assertIs<AssetTransferOutcome.Failed>(again)
        assertEquals(AssetErrorKind.QUOTA_EXCEEDED, again.session.failure)

        val flaky = InterceptingProvider(InMemoryAssetProvider(digests))
        flaky.interceptUploadChunk = { if (it.index == 2) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        assertIs<AssetTransferOutcome.Interrupted>(
            engine(flaky, rows).upload(id, AssetId("b"), 1, media, InMemoryAssetSource(patternBytes(5_000))),
        )
        assertIs<AssetTransferOutcome.Cancelled>(engine(flaky, rows).cancel(id))
        flaky.uploadAttempts.clear()
        assertIs<AssetTransferOutcome.Cancelled>(
            engine(flaky, rows).upload(id, AssetId("b"), 1, media, InMemoryAssetSource(patternBytes(5_000))),
        )
        assertEquals(emptyList(), flaky.uploadAttempts)

        val done = engine(flaky, rows).upload(sessionId("ok"), AssetId("c"), 1, media, InMemoryAssetSource(patternBytes(2_000)))
        assertIs<AssetTransferOutcome.Completed>(done)
        val opens = flaky.openCalls
        assertIs<AssetTransferOutcome.Completed>(
            engine(flaky, rows).upload(sessionId("ok"), AssetId("c"), 1, media, InMemoryAssetSource(patternBytes(2_000))),
        )
        assertEquals(opens, flaky.openCalls, "a completed session replays from the record without touching the provider")
    }

    // ------------------------------------------------------------ two workers

    @Test
    fun `two workers driving the same session converge and the asset is intact`() = runTest {
        val rows = EncodingDurableStateStore()
        rows.yieldAfterLoad = true
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        provider.interceptUploadChunk = { yield(); null }
        val bytes = patternBytes(12_000)
        val outcomes = (1..2).map {
            async { engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes)) }
        }.awaitAll()
        assertTrue(outcomes.all { it is AssetTransferOutcome.Completed }, "outcomes: $outcomes")
        assertTrue(rows.conflictsReported > 0, "the workers must actually have raced on the record")
        val final = persisted(rows)
        assertEquals(AssetTransferPhase.COMPLETED, final.phase)
        assertEquals((0 until 12).toSet(), final.committedChunks)

        val sink = InMemoryAssetSink()
        assertIs<AssetTransferOutcome.Completed>(
            engine(provider, EncodingDurableStateStore()).download(sessionId("d"), AssetId("a"), 1, sink),
        )
        assertContentEquals(bytes, sink.snapshot())
    }

    // ---------------------------------------------------------- store failures

    @Test
    fun `a store failure mid-transfer is reported and the same call resumes once the store recovers`() = runTest {
        val rows = EncodingDurableStateStore()
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val bytes = patternBytes(6_000)
        // 1 create, 2 Start, 3 Reconcile, 4 chunk 0, 5 chunk 1 ... fail the write for chunk 2.
        provider.interceptUploadChunk = { if (it.index == 2) rows.failNextCompareAndSets = 1; null }
        val first = engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.SessionStoreFailure>(first)
        assertEquals(Recoverability.RECOVERABLE, first.error.recoverability)
        assertEquals(setOf(0, 1), persisted(rows).committedChunks)

        provider.uploadAttempts.clear()
        assertIs<AssetTransferOutcome.Completed>(engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes)))
        assertEquals(listOf(3, 4, 5), provider.uploadAttempts, "chunk 2 was accepted by the provider; the record is reconciled to it")
    }

    @Test
    fun `an unreadable store fails the call cleanly for upload download and cancel`() = runTest {
        val rows = EncodingDurableStateStore()
        val provider = InMemoryAssetProvider(digests)
        rows.failNextLoads = 1
        assertIs<AssetTransferOutcome.SessionStoreFailure>(
            engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(patternBytes(3_000))),
        )
        rows.failNextLoads = 1
        assertIs<AssetTransferOutcome.SessionStoreFailure>(engine(provider, rows).download(sessionId("d"), AssetId("a"), null, InMemoryAssetSink()))
        rows.failNextLoads = 1
        assertIs<AssetTransferOutcome.SessionStoreFailure>(engine(provider, rows).cancel(id))
    }

    @Test
    fun `contention past the retry bound is reported as a recoverable store failure`() = runTest {
        val rows = EncodingDurableStateStore()
        rows.forcedConflicts = 1_000
        val outcome = AssetTransferEngine(
            InMemoryAssetProvider(digests), DurableAssetTransferSessionStore(rows, maximumStateUpdateAttempts = 2), digests,
        ).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(patternBytes(3_000)))
        assertIs<AssetTransferOutcome.SessionStoreFailure>(outcome)
        assertEquals(AssetErrorKind.SESSION_STORE_FAILURE.code, outcome.error.code)
        assertTrue(rows.rows.isEmpty())
    }

    @Test
    fun `a corrupt persisted session is reported and never resumed or overwritten`() = runTest {
        val rows = EncodingDurableStateStore()
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val bytes = patternBytes(5_000)
        provider.interceptUploadChunk = { if (it.index == 3) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        assertIs<AssetTransferOutcome.Interrupted>(engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes)))
        val row = rows.rows.getValue("u")
        val corrupted = EncodingDurableStateStore.Row(row.payload.replace("TRANSFERRING", "COMPLETED"), row.version, row.schemaVersion)
        rows.rows["u"] = corrupted

        provider.interceptUploadChunk = { null }
        provider.uploadAttempts.clear()
        val outcome = engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.SessionStoreFailure>(outcome)
        assertEquals(emptyList(), provider.uploadAttempts)
        assertEquals(corrupted.payload, rows.rows.getValue("u").payload, "corrupt data is left for inspection, not overwritten")
    }

    @Test
    fun `a session record for a different scope is reported as corrupt`() = runTest {
        val rows = EncodingDurableStateStore()
        val provider = InMemoryAssetProvider(digests)
        engine(provider, rows).upload(sessionId("other"), AssetId("x"), 1, media, InMemoryAssetSource(patternBytes(2_000)))
        rows.rows["u"] = rows.rows.getValue("other")
        val outcome = engine(provider, rows).upload(id, AssetId("x"), 1, media, InMemoryAssetSource(patternBytes(2_000)))
        assertIs<AssetTransferOutcome.SessionStoreFailure>(outcome)
        assertEquals(AssetErrorKind.SESSION_STATE_CORRUPT.code, outcome.error.code)
        assertEquals(Recoverability.NON_RECOVERABLE, outcome.error.recoverability)
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun `no persisted row ever contains asset bytes`() = runTest {
        val marker = "TOP-SECRET-CONTENT-0123456789"
        val bytes = ByteArray(6_000) { marker[it % marker.length].code.toByte() }
        val rows = EncodingDurableStateStore()
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        provider.interceptUploadChunk = { if (it.index == 3) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes))
        val hex = marker.encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        for (row in rows.rows.values) {
            assertFalse(marker in row.payload)
            assertFalse(hex in row.payload)
        }
        engine(provider, rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(bytes))
        for (row in rows.rows.values) assertFalse(marker in row.payload)
    }

    @Test
    fun `each applied transition is one durable write`() = runTest {
        val rows = EncodingDurableStateStore()
        val outcome = engine(InMemoryAssetProvider(digests), rows).upload(id, AssetId("a"), 1, media, InMemoryAssetSource(patternBytes(3_000)))
        assertIs<AssetTransferOutcome.Completed>(outcome)
        // Creation, Start, 3 chunks, BeginVerification and VerificationSucceeded are 7 writes (revisions 0 to 6);
        // the empty ReconcileCommitted is an idempotent no-op and writes nothing.
        assertEquals(6L, outcome.session.revision)
        assertEquals(7, rows.updates)
        assertEquals(outcome.session, persisted(rows))
    }
}
