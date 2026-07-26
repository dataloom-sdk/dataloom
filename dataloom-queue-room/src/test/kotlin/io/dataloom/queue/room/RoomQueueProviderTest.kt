package io.dataloom.queue.room

import io.dataloom.api.context.DataLoomMetadata
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
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.QueueEntryDao
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [RoomQueueProvider] using mocked DAO.
 *
 * These tests verify the provider's contract behaviour — correct method dispatch,
 * return value mapping, error handling — without requiring a real Room database.
 */
class RoomQueueProviderTest {

    private val mockDatabase: DataLoomRoomDatabase = mock()
    private val mockDao: QueueEntryDao = mock()
    private val provider = RoomQueueProvider(mockDatabase)

    init {
        whenever(mockDatabase.queueEntryDao()).thenReturn(mockDao)
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    private fun makeEntry(
        id: String = "entry-1",
        state: QueueEntryState = QueueEntryState.PENDING,
        enqueuedAtMs: Long = 1_000_000L,
        availableAtMs: Long = 1_000_000L,
    ): QueueEntry {
        val ctx = ExecutionContext(
            executionId = ExecutionId("exec-1"),
            correlationId = CorrelationId("corr-1"),
        )
        val request = SynchronizationRequest(
            workflowId = WorkflowId("wf-1"),
            sessionId = SynchronizationSessionId("sess-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ctx,
        )
        return QueueEntry(
            id = QueueEntryId(id),
            synchronizationRequest = request,
            state = state,
            enqueuedAt = DataLoomInstant(enqueuedAtMs),
            availableAt = DataLoomInstant(availableAtMs),
        )
    }

    private fun makeLeasedEntry(
        id: String = "entry-1",
        leaseId: String = "lease-1",
        consumerId: String = "consumer-1",
        acquiredAtMs: Long = 1_000_000L,
        expiresAtMs: Long = 1_060_000L,
    ): QueueEntry {
        val ctx = ExecutionContext(
            executionId = ExecutionId("exec-1"),
            correlationId = CorrelationId("corr-1"),
        )
        val request = SynchronizationRequest(
            workflowId = WorkflowId("wf-1"),
            sessionId = SynchronizationSessionId("sess-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ctx,
        )
        val lease = QueueLease(
            id = QueueLeaseId(leaseId),
            consumerId = QueueConsumerId(consumerId),
            acquiredAt = DataLoomInstant(acquiredAtMs),
            expiresAt = DataLoomInstant(expiresAtMs),
        )
        return QueueEntry(
            id = QueueEntryId(id),
            synchronizationRequest = request,
            state = QueueEntryState.LEASED,
            enqueuedAt = DataLoomInstant(acquiredAtMs),
            availableAt = DataLoomInstant(acquiredAtMs),
            lease = lease,
        )
    }

    private fun makeError(code: String = "TEST_ERROR"): DataLoomError = object : DataLoomError {
        override val code = ErrorCode(code)
        override val category = ErrorCategory.QUEUE
        override val severity = ErrorSeverity.ERROR
        override val recoverability = Recoverability.RECOVERABLE
        override val message = "Test error"
        override val cause: Throwable? = null
    }

    // ── Descriptor ───────────────────────────────────────────────────────────

    @Test
    fun `descriptor has QUEUE type`() {
        assertEquals(
            io.dataloom.api.provider.ProviderType.QUEUE,
            provider.descriptor.type,
        )
    }

    // ── enqueue ──────────────────────────────────────────────────────────────

    @Test
    fun `enqueue returns Success when insert succeeds`() = runBlocking {
        val entry = makeEntry()
        whenever(mockDao.insert(any())).then { Unit }

        val result = provider.enqueue(QueueEnqueueRequest(entry))

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `enqueue returns Failure with duplicate-entry code when SQLiteConstraintException`() =
        runBlocking {
            val entry = makeEntry()
            whenever(mockDao.insert(any())).thenThrow(
                android.database.sqlite.SQLiteConstraintException("UNIQUE constraint failed"),
            )

            val result = provider.enqueue(QueueEnqueueRequest(entry))

            assertIs<ProviderOperationResult.Failure>(result)
            assertEquals("QUEUE_DUPLICATE_ENTRY", result.error.code.value)
        }

    @Test
    fun `enqueue returns Failure with database-failure code on generic exception`() =
        runBlocking {
            val entry = makeEntry()
            whenever(mockDao.insert(any())).thenThrow(RuntimeException("boom"))

            val result = provider.enqueue(QueueEnqueueRequest(entry))

            assertIs<ProviderOperationResult.Failure>(result)
            assertEquals("QUEUE_DATABASE_FAILURE", result.error.code.value)
        }

    // ── acquire ──────────────────────────────────────────────────────────────

    @Test
    fun `acquire returns NoEntries when DAO returns empty list`() = runBlocking {
        val request = QueueAcquireRequest(
            consumerId = QueueConsumerId("consumer-1"),
            leaseId = QueueLeaseId("lease-1"),
            acquiredAt = DataLoomInstant(1_000_000L),
            leaseExpiresAt = DataLoomInstant(1_060_000L),
            maxEntries = 10,
        )
        whenever(mockDao.acquireEntries(any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        val result = provider.acquire(request)

        assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(result)
        assertIs<QueueAcquireResult.NoEntries>(result.value)
    }

    @Test
    fun `acquire returns Failure on database exception`() = runBlocking {
        val request = QueueAcquireRequest(
            consumerId = QueueConsumerId("consumer-1"),
            leaseId = QueueLeaseId("lease-1"),
            acquiredAt = DataLoomInstant(1_000_000L),
            leaseExpiresAt = DataLoomInstant(1_060_000L),
            maxEntries = 10,
        )
        whenever(mockDao.acquireEntries(any(), any(), any(), any(), any(), any()))
            .thenThrow(RuntimeException("db error"))

        val result = provider.acquire(request)

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("QUEUE_DATABASE_FAILURE", result.error.code.value)
    }

    // ── complete ─────────────────────────────────────────────────────────────

    @Test
    fun `complete returns Success when DAO returns 1 affected row`() = runBlocking {
        whenever(mockDao.completeEntry(eq("entry-1"), eq("lease-1"))).thenReturn(1)

        val result = provider.complete(
            QueueCompletionRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("lease-1"),
                completedAt = DataLoomInstant(1_000_000L),
            ),
        )

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `complete returns Failure with stale-lease code when DAO returns 0`() = runBlocking {
        whenever(mockDao.completeEntry(any(), any())).thenReturn(0)

        val result = provider.complete(
            QueueCompletionRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("stale-lease"),
                completedAt = DataLoomInstant(1_000_000L),
            ),
        )

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("QUEUE_STALE_LEASE", result.error.code.value)
    }

    // ── reschedule ───────────────────────────────────────────────────────────

    @Test
    fun `reschedule returns Success when DAO returns 1`() = runBlocking {
        whenever(mockDao.rescheduleEntry(any(), any(), any(), any(), any(), any())).thenReturn(1)

        val result = provider.reschedule(
            QueueRescheduleRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("lease-1"),
                retryAttempt = RetryAttempt(1),
                availableAt = DataLoomInstant(2_000_000L),
                error = makeError(),
            ),
        )

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `reschedule returns Failure with stale-lease code when DAO returns 0`() = runBlocking {
        whenever(mockDao.rescheduleEntry(any(), any(), any(), any(), any(), any())).thenReturn(0)

        val result = provider.reschedule(
            QueueRescheduleRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("stale-lease"),
                retryAttempt = RetryAttempt(1),
                availableAt = DataLoomInstant(2_000_000L),
                error = makeError(),
            ),
        )

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("QUEUE_STALE_LEASE", result.error.code.value)
    }

    // ── fail ─────────────────────────────────────────────────────────────────

    @Test
    fun `fail with FAILED disposition returns Success when DAO returns 1`() = runBlocking {
        whenever(mockDao.failEntry(any(), any(), eq("FAILED"), any(), any())).thenReturn(1)

        val result = provider.fail(
            QueueFailureRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("lease-1"),
                error = makeError(),
                disposition = QueueFailureDisposition.FAILED,
            ),
        )

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `fail with DEAD_LETTER disposition returns Success when DAO returns 1`() = runBlocking {
        whenever(mockDao.failEntry(any(), any(), eq("DEAD_LETTER"), any(), any())).thenReturn(1)

        val result = provider.fail(
            QueueFailureRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("lease-1"),
                error = makeError(),
                disposition = QueueFailureDisposition.DEAD_LETTER,
            ),
        )

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `fail returns Failure with stale-lease code when DAO returns 0`() = runBlocking {
        whenever(mockDao.failEntry(any(), any(), any(), any(), any())).thenReturn(0)

        val result = provider.fail(
            QueueFailureRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("stale-lease"),
                error = makeError(),
                disposition = QueueFailureDisposition.FAILED,
            ),
        )

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("QUEUE_STALE_LEASE", result.error.code.value)
    }

    // ── cancel ───────────────────────────────────────────────────────────────

    @Test
    fun `cancel returns Success when DAO returns 1`() = runBlocking {
        whenever(mockDao.cancelEntry(eq("entry-1"))).thenReturn(1)

        val execCtx = ExecutionContext(
            executionId = ExecutionId("exec-1"),
            correlationId = CorrelationId("corr-1"),
        )
        val result = provider.cancel(
            QueueCancellationRequest(
                entryId = QueueEntryId("entry-1"),
                context = execCtx,
            ),
        )

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `cancel returns Failure with rejection code when DAO returns 0`() = runBlocking {
        whenever(mockDao.cancelEntry(any())).thenReturn(0)

        val execCtx = ExecutionContext(
            executionId = ExecutionId("exec-1"),
            correlationId = CorrelationId("corr-1"),
        )
        val result = provider.cancel(
            QueueCancellationRequest(
                entryId = QueueEntryId("entry-1"),
                context = execCtx,
            ),
        )

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("QUEUE_CANCELLATION_REJECTED", result.error.code.value)
    }

    // ── recoverExpiredLeases ─────────────────────────────────────────────────

    @Test
    fun `recoverExpiredLeases returns Success with recovered count`() = runBlocking {
        whenever(mockDao.recoverExpiredLeases(any())).thenReturn(3)

        val result = provider.recoverExpiredLeases(
            ExpiredLeaseRecoveryRequest(currentTime = DataLoomInstant(5_000_000L)),
        )

        assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(result)
        assertEquals(3, result.value.recoveredEntries)
    }

    @Test
    fun `recoverExpiredLeases returns Failure on database exception`() = runBlocking {
        whenever(mockDao.recoverExpiredLeases(any())).thenThrow(RuntimeException("db error"))

        val result = provider.recoverExpiredLeases(
            ExpiredLeaseRecoveryRequest(currentTime = DataLoomInstant(5_000_000L)),
        )

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("QUEUE_DATABASE_FAILURE", result.error.code.value)
    }
}
