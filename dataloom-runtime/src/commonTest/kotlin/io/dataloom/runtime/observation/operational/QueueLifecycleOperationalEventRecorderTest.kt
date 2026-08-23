package io.dataloom.runtime.observation.operational

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueueEntryExecutionOutcome
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Proves [QueueLifecycleOperationalEventRecorder] is a real, reachable caller
 * of [DurableOperationalEventOutbox] that durably appends a bridged envelope
 * for every witnessed transition, that append/envelope failures never
 * propagate (except [CancellationException]), and that the clock is read at
 * most once per witnessed transition, only when the bridge needs a fallback.
 */
class QueueLifecycleOperationalEventRecorderTest {

    private val scope = OperationalEventOutboxScope("test-queue-lifecycle-events")

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
        private val fakeError = object : DataLoomError {
            override val code = ErrorCode("STORE-DOWN")
            override val category = ErrorCategory.STORAGE
            override val severity = ErrorSeverity.ERROR
            override val recoverability = Recoverability.RECOVERABLE
            override val message = "Store unavailable in test."
            override val cause: Throwable? = null
        }

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

    private val t0 = DataLoomInstant(1_000_000L)
    private val t2 = DataLoomInstant(3_000_000L)

    private val sampleLeaseId = QueueLeaseId("lease-001")

    private val sampleEntry: QueueEntry = QueueEntry(
        id = QueueEntryId("entry-001"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("exec-001"),
                correlationId = CorrelationId("corr-001"),
            ),
        ),
        state = QueueEntryState.LEASED,
        enqueuedAt = t0,
        availableAt = t0,
        lease = QueueLease(
            id = sampleLeaseId,
            consumerId = QueueConsumerId("consumer-001"),
            acquiredAt = t0,
            expiresAt = t2,
        ),
    )

    // -------------------------------------------------------------------------
    // Real wiring: entries actually appear
    // -------------------------------------------------------------------------

    @Test
    fun onTransition_withRealStore_durablyAppendsAnEnvelope() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val recorder = QueueLifecycleOperationalEventRecorder(
            outbox = outbox,
            scope = scope,
            clock = FixedClock(),
        )

        recorder.onTransition(sampleEntry, sampleLeaseId, QueueEntryExecutionOutcome.Completed(t2))

        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(entries)
        assertEquals(1, entries.value.size)
        assertEquals("dataloom.queue.entry.completed", entries.value[0].type.value)
    }

    @Test
    fun onTransition_completed_neverReadsTheClock() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val clock = FixedClock()
        val recorder = QueueLifecycleOperationalEventRecorder(outbox = outbox, scope = scope, clock = clock)

        recorder.onTransition(sampleEntry, sampleLeaseId, QueueEntryExecutionOutcome.Completed(t2))

        assertEquals(0, clock.callCount, "Completed already carries completedAt; no clock read is needed.")
    }

    @Test
    fun onTransition_reschedule_readsTheClockExactlyOnce() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val clock = FixedClock(epochMs = 7_000_000L)
        val recorder = QueueLifecycleOperationalEventRecorder(outbox = outbox, scope = scope, clock = clock)
        val error = FakeError()

        recorder.onTransition(
            sampleEntry,
            sampleLeaseId,
            QueueEntryExecutionOutcome.Reschedule(RetryAttempt(1), t2, error),
        )

        assertEquals(1, clock.callCount)
        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(entries)
        assertEquals(DataLoomInstant(7_000_000L), entries.value.single().occurredAt)
    }

    // -------------------------------------------------------------------------
    // Swallow boundary: append/envelope failures never propagate
    // -------------------------------------------------------------------------

    @Test
    fun onTransition_persistenceFailureIsSwallowed_doesNotThrow() = runTest {
        val outbox = DurableOperationalEventOutbox(PersistenceFailureOperationalEventOutboxStore())
        val recorder = QueueLifecycleOperationalEventRecorder(outbox = outbox, scope = scope, clock = FixedClock())

        // Must not throw.
        recorder.onTransition(sampleEntry, sampleLeaseId, QueueEntryExecutionOutcome.Completed(t2))
    }

    @Test
    fun onTransition_storeThrowingOrdinaryException_isSwallowed() = runTest {
        val outbox = DurableOperationalEventOutbox(ThrowingOperationalEventOutboxStore())
        val recorder = QueueLifecycleOperationalEventRecorder(outbox = outbox, scope = scope, clock = FixedClock())

        // Must not throw.
        recorder.onTransition(sampleEntry, sampleLeaseId, QueueEntryExecutionOutcome.Completed(t2))
    }

    @Test
    fun onTransition_cancellationExceptionFromStore_stillPropagates() = runTest {
        val cancellation = CancellationException("Cancelled in store.")
        val outbox = DurableOperationalEventOutbox(ThrowingOperationalEventOutboxStore(cancellation))
        val recorder = QueueLifecycleOperationalEventRecorder(outbox = outbox, scope = scope, clock = FixedClock())

        var threw = false
        try {
            recorder.onTransition(sampleEntry, sampleLeaseId, QueueEntryExecutionOutcome.Completed(t2))
        } catch (expected: CancellationException) {
            threw = true
        }
        assertTrue(threw, "CancellationException must still propagate.")
    }

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-Q-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
