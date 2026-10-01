package io.dataloom.assets

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Proves [AssetTransferEngine] notifies its configured [AssetTransferObserver]
 * exactly once per [AssetTransferEngine.upload]/`.download`/`.cancel` call,
 * with the same [sessionId], the correct [AssetTransferOperation], and the
 * exact [AssetTransferOutcome] instance the call itself returns -- including
 * for [AssetTransferOutcome.NotStarted], which carries no session of its own.
 * A `null` observer (the default) leaves every existing behavior unchanged.
 */
class AssetTransferEngineObserverTest {

    private val digests = platformDigests()
    private val mediaType = octetStream

    private class RecordingObserver : AssetTransferObserver {
        data class Call(
            val sessionId: AssetTransferSessionId,
            val operation: AssetTransferOperation,
            val outcome: AssetTransferOutcome,
        )

        val calls = mutableListOf<Call>()

        override suspend fun onOutcome(
            sessionId: AssetTransferSessionId,
            operation: AssetTransferOperation,
            outcome: AssetTransferOutcome,
        ) {
            calls += Call(sessionId, operation, outcome)
        }
    }

    private fun engine(
        provider: AssetProvider,
        store: AssetTransferSessionStore,
        observer: AssetTransferObserver?,
    ) = AssetTransferEngine(provider, store, digests, chunkSizeBytes = 1_024, verifyBufferBytes = 256, observer = observer)

    @Test
    fun uploadNotifiesTheObserverExactlyOnce_withTheSameOutcomeItReturns() = runTest {
        val observer = RecordingObserver()
        val provider = InMemoryAssetProvider(digests)
        val outcome = engine(provider, InMemoryAssetTransferSessionStore(), observer).upload(
            sessionId("up"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000)),
        )

        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(1, observer.calls.size)
        val call = observer.calls.single()
        assertEquals(sessionId("up"), call.sessionId)
        assertEquals(AssetTransferOperation.UPLOAD, call.operation)
        assertSame(outcome, call.outcome)
    }

    @Test
    fun downloadNotifiesTheObserverExactlyOnce_withTheSameOutcomeItReturns() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val bytes = patternBytes(5_000)
        engine(provider, InMemoryAssetTransferSessionStore(), observer = null).upload(
            sessionId("seed"), AssetId("a"), 1, mediaType, InMemoryAssetSource(bytes),
        )

        val observer = RecordingObserver()
        val sink = InMemoryAssetSink()
        val outcome = engine(provider, InMemoryAssetTransferSessionStore(), observer)
            .download(sessionId("down"), AssetId("a"), null, sink)

        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(1, observer.calls.size)
        val call = observer.calls.single()
        assertEquals(sessionId("down"), call.sessionId)
        assertEquals(AssetTransferOperation.DOWNLOAD, call.operation)
        assertSame(outcome, call.outcome)
    }

    @Test
    fun cancelNotifiesTheObserverExactlyOnce_withTheSameOutcomeItReturns() = runTest {
        val observer = RecordingObserver()
        val provider = InMemoryAssetProvider(digests)
        val store = InMemoryAssetTransferSessionStore()
        // Insert a non-terminal session directly (bypassing upload, which this
        // in-memory setup would otherwise drive straight to COMPLETED, leaving
        // nothing left to cancel).
        val asset = testAsset(size = 5_000, chunkSize = 1_024)
        store.save(
            AssetTransferSession(
                sessionId = sessionId("to-cancel"),
                direction = AssetTransferDirection.UPLOAD,
                manifest = asset.manifest,
                phase = AssetTransferPhase.TRANSFERRING,
            ),
            expectedRevision = null,
        )

        val outcome = engine(provider, store, observer).cancel(sessionId("to-cancel"))

        assertIs<AssetTransferOutcome.Cancelled>(outcome)
        assertEquals(1, observer.calls.size)
        val call = observer.calls.single()
        assertEquals(sessionId("to-cancel"), call.sessionId)
        assertEquals(AssetTransferOperation.CANCEL, call.operation)
        assertSame(outcome, call.outcome)
    }

    @Test
    fun notStartedStillNotifiesTheObserver_eventThoughTheOutcomeCarriesNoSession() = runTest {
        val observer = RecordingObserver()
        val provider = InMemoryAssetProvider(digests)
        val outcome = engine(provider, InMemoryAssetTransferSessionStore(), observer).upload(
            sessionId("empty"), AssetId("a"), 1, mediaType, InMemoryAssetSource(ByteArray(0)),
        )

        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(1, observer.calls.size)
        val call = observer.calls.single()
        assertEquals(sessionId("empty"), call.sessionId, "sessionId must still be reported even with no session.")
        assertEquals(AssetTransferOperation.UPLOAD, call.operation)
        assertSame(outcome, call.outcome)
    }

    @Test
    fun sessionStoreFailureStillNotifiesTheObserver_eventThoughTheOutcomeCarriesNoSession() = runTest {
        val observer = RecordingObserver()
        val provider = InMemoryAssetProvider(digests)
        val failingStore = object : AssetTransferSessionStore {
            override suspend fun load(sessionId: AssetTransferSessionId): AssetTransferSession? =
                throw AssetTransferSessionStoreException(FakeError())

            override suspend fun save(updated: AssetTransferSession, expectedRevision: Long?): Boolean =
                throw AssetTransferSessionStoreException(FakeError())
        }
        val outcome = engine(provider, failingStore, observer).upload(
            sessionId("store-failure"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(1_000)),
        )

        assertIs<AssetTransferOutcome.SessionStoreFailure>(outcome)
        assertEquals(1, observer.calls.size)
        val call = observer.calls.single()
        assertEquals(sessionId("store-failure"), call.sessionId)
        assertEquals(AssetTransferOperation.UPLOAD, call.operation)
        assertSame(outcome, call.outcome)
    }

    @Test
    fun aNullObserverIsNeverCalledAndChangesNothing() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val outcome = engine(provider, InMemoryAssetTransferSessionStore(), observer = null).upload(
            sessionId("up"), AssetId("a"), 1, mediaType, InMemoryAssetSource(patternBytes(5_000)),
        )
        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertTrue(true, "No observer call is possible to assert on; absence of a crash is the proof.")
    }

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-ASSET-FAKE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "fake store failure",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
