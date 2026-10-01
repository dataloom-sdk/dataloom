package io.dataloom.runtime.observation.operational

import io.dataloom.api.asset.AssetChunkDescriptor
import io.dataloom.api.asset.AssetChunkLayout
import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferOperation
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferPhase
import io.dataloom.assets.AssetTransferSession
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetErrorKind
import io.dataloom.runtime.operational.outboxTestClock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Proves [AssetTransferOperationalEventRecorder] is a real, reachable caller
 * of [DurableOperationalEventOutbox] that durably appends a bridged envelope
 * for every witnessed [AssetTransferOutcome], that append/envelope failures
 * never propagate (except [CancellationException]), and that the clock is
 * read exactly once per witnessed outcome.
 */
class AssetTransferOperationalEventRecorderTest {

    private val scope = OperationalEventOutboxScope("test-asset-transfer-events")

    private class FixedClock(private val epochMs: Long = 5_000_000L) : DataLoomClock {
        var callCount = 0
            private set

        override fun now(): DataLoomInstant {
            callCount++
            return DataLoomInstant(epochMs)
        }
    }

    /** Ordinary in-memory [DurableStateStore] fixture -- appends really persist. */
    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    /** [load] always returns [ProviderOperationResult.Failure] -- append reports PersistenceFailure, never throws. */
    private class PersistenceFailureOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val fakeError = FakeError()

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(fakeError)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(fakeError)
    }

    /** [load] throws an ordinary exception -- proves the recorder's swallow boundary, not just the outbox's own outcome type. */
    private class ThrowingOperationalEventOutboxStore(
        private val throwable: Throwable = IllegalStateException("Store threw in test."),
    ) : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            throw throwable
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            throw throwable
        }
    }

    private fun manifest(): AssetManifest = AssetManifest(
        assetId = AssetId("asset-1"),
        version = 1L,
        sizeBytes = 1_024L,
        mediaType = AssetMediaType("application/octet-stream"),
        checksum = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32)),
        chunkLayout = AssetChunkLayout(
            listOf(AssetChunkDescriptor(0, 0L, 1_024L, DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32)))),
        ),
    )

    private fun completedSession(sessionId: String = "session-001") = AssetTransferSession(
        sessionId = AssetTransferSessionId(sessionId),
        direction = AssetTransferDirection.UPLOAD,
        manifest = manifest(),
        phase = AssetTransferPhase.COMPLETED,
        committedChunks = setOf(0),
        revision = 2L,
    )

    // -------------------------------------------------------------------------
    // Real wiring: entries actually appear
    // -------------------------------------------------------------------------

    @Test
    fun onOutcome_withRealStore_durablyAppendsAnEnvelope() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val recorder = AssetTransferOperationalEventRecorder(outbox = outbox, scope = scope, clock = FixedClock())

        recorder.onOutcome(
            AssetTransferSessionId("session-001"),
            AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(completedSession()),
        )

        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(entries)
        assertEquals(1, entries.value.size)
        assertEquals("dataloom.asset.transfer.upload.completed", entries.value[0].type.value)
    }

    @Test
    fun onOutcome_readsTheClockExactlyOnce() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val clock = FixedClock(epochMs = 7_000_000L)
        val recorder = AssetTransferOperationalEventRecorder(outbox = outbox, scope = scope, clock = clock)

        recorder.onOutcome(
            AssetTransferSessionId("session-001"),
            AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(completedSession()),
        )

        assertEquals(1, clock.callCount)
        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(entries)
        assertEquals(DataLoomInstant(7_000_000L), entries.value.single().occurredAt)
    }

    @Test
    fun onOutcome_notStartedWithNoSession_stillDurablyAppendsAnEnvelope() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val recorder = AssetTransferOperationalEventRecorder(outbox = outbox, scope = scope, clock = FixedClock())

        recorder.onOutcome(
            AssetTransferSessionId("session-002"),
            AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.NotStarted(AssetTransferError(AssetErrorKind.EMPTY_ASSET, "empty")),
        )

        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(entries)
        assertEquals(1, entries.value.size)
        assertEquals("dataloom.asset.transfer.upload.not_started", entries.value[0].type.value)
    }

    // -------------------------------------------------------------------------
    // Swallow boundary: append/envelope failures never propagate
    // -------------------------------------------------------------------------

    @Test
    fun onOutcome_persistenceFailureIsSwallowed_doesNotThrow() = runTest {
        val outbox = DurableOperationalEventOutbox(PersistenceFailureOperationalEventOutboxStore(), outboxTestClock)
        val recorder = AssetTransferOperationalEventRecorder(outbox = outbox, scope = scope, clock = FixedClock())

        // Must not throw.
        recorder.onOutcome(
            AssetTransferSessionId("session-001"),
            AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(completedSession()),
        )
    }

    @Test
    fun onOutcome_storeThrowingOrdinaryException_isSwallowed() = runTest {
        val outbox = DurableOperationalEventOutbox(ThrowingOperationalEventOutboxStore(), outboxTestClock)
        val recorder = AssetTransferOperationalEventRecorder(outbox = outbox, scope = scope, clock = FixedClock())

        // Must not throw.
        recorder.onOutcome(
            AssetTransferSessionId("session-001"),
            AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(completedSession()),
        )
    }

    @Test
    fun onOutcome_cancellationExceptionFromStore_stillPropagates() = runTest {
        val cancellation = CancellationException("Cancelled in store.")
        val outbox = DurableOperationalEventOutbox(ThrowingOperationalEventOutboxStore(cancellation), outboxTestClock)
        val recorder = AssetTransferOperationalEventRecorder(outbox = outbox, scope = scope, clock = FixedClock())

        var threw = false
        try {
            recorder.onOutcome(
                AssetTransferSessionId("session-001"),
                AssetTransferOperation.UPLOAD,
                AssetTransferOutcome.Completed(completedSession()),
            )
        } catch (expected: CancellationException) {
            threw = true
        }
        assertTrue(threw, "CancellationException must still propagate.")
    }

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-ASSET-FAKE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
