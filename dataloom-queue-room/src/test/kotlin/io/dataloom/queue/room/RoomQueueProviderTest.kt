package io.dataloom.queue.room

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
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.QueueEntryDao
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Fast provider contract tests. Real Room/SQLite behaviour is covered by androidTest. */
class RoomQueueProviderTest {

    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: QueueEntryDao
    private lateinit var provider: RoomQueueProvider

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.queueEntryDao()).thenReturn(dao)
        provider = RoomQueueProvider(database)
    }

    private fun executionContext(): ExecutionContext = ExecutionContext(
        executionId = ExecutionId("exec-1"),
        correlationId = CorrelationId("corr-1"),
    )

    private fun entry(id: String = "entry-1"): QueueEntry = QueueEntry(
        id = QueueEntryId(id),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = executionContext(),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
    )

    @Test
    fun `descriptor has queue type`() {
        assertEquals(ProviderType.QUEUE, provider.descriptor.type)
    }

    @Test
    fun `enqueue returns success when DAO insert succeeds`() {
        runBlocking {
            whenever(dao.insert(any())).thenReturn(Unit)
            val result = provider.enqueue(QueueEnqueueRequest(entry()))
            assertIs<ProviderOperationResult.Success<Unit>>(result)
        }
    }

    @Test
    fun `enqueue maps duplicate primary key to duplicate entry`() {
        runBlocking {
            whenever(dao.insert(any())).thenThrow(mock<android.database.sqlite.SQLiteConstraintException>())
            val result = provider.enqueue(QueueEnqueueRequest(entry()))
            val failure = assertIs<ProviderOperationResult.Failure>(result)
            assertEquals("QUEUE_DUPLICATE_ENTRY", failure.error.code.value)
        }
    }

    @Test
    fun `database cancellation propagates instead of becoming a provider failure`() {
        val expected = CancellationException("cancelled")
        runBlocking {
            whenever(dao.insert(any())).thenThrow(expected)
        }

        val actual = assertFailsWith<CancellationException> {
            runBlocking {
                provider.enqueue(QueueEnqueueRequest(entry()))
            }
        }
        assertEquals("cancelled", actual.message)
    }

    @Test
    fun `acquire returns no entries when DAO selection is empty`() {
        runBlocking {
            whenever(dao.acquireEntries(any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyList())
            val result = provider.acquire(
                QueueAcquireRequest(
                    consumerId = QueueConsumerId("consumer-1"),
                    leaseId = QueueLeaseId("lease-1"),
                    acquiredAt = DataLoomInstant(1_000L),
                    leaseExpiresAt = DataLoomInstant(2_000L),
                    maxEntries = 10,
                ),
            )
            val success = assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(result)
            assertIs<QueueAcquireResult.NoEntries>(success.value)
        }
    }

    @Test
    fun `complete returns success for one guarded transition`() {
        runBlocking {
            whenever(dao.completeEntry(eq("entry-1"), eq("lease-1"))).thenReturn(1)
            val result = provider.complete(
                QueueCompletionRequest(
                    entryId = QueueEntryId("entry-1"),
                    leaseId = QueueLeaseId("lease-1"),
                    completedAt = DataLoomInstant(2_000L),
                ),
            )
            assertIs<ProviderOperationResult.Success<Unit>>(result)
        }
    }

    @Test
    fun `complete rejects a stale lease`() {
        runBlocking {
            whenever(dao.completeEntry(any(), any())).thenReturn(0)
            val result = provider.complete(
                QueueCompletionRequest(
                    entryId = QueueEntryId("entry-1"),
                    leaseId = QueueLeaseId("stale-lease"),
                    completedAt = DataLoomInstant(2_000L),
                ),
            )
            val failure = assertIs<ProviderOperationResult.Failure>(result)
            assertEquals("QUEUE_STALE_LEASE", failure.error.code.value)
        }
    }

    @Test
    fun `reschedule persists every canonical error field`() {
        runBlocking {
            whenever(
                dao.rescheduleEntry(
                    eq("entry-1"),
                    eq("lease-1"),
                    eq(3_000L),
                    eq(2),
                    eq("NETWORK_TEMPORARY"),
                    eq("NETWORK"),
                    eq("WARNING"),
                    eq("RECOVERABLE"),
                    eq("A temporary network failure occurred."),
                ),
            ).thenReturn(1)

            val result = provider.reschedule(
                QueueRescheduleRequest(
                    entryId = QueueEntryId("entry-1"),
                    leaseId = QueueLeaseId("lease-1"),
                    retryAttempt = RetryAttempt(2),
                    availableAt = DataLoomInstant(3_000L),
                    error = testError(),
                ),
            )

            assertIs<ProviderOperationResult.Success<Unit>>(result)
        }
    }

    @Test
    fun `defer forwards the guarded lease and availability without retry data`() {
        runBlocking {
            whenever(
                dao.deferEntry(
                    eq("entry-1"),
                    eq("lease-1"),
                    eq(3_000L),
                ),
            ).thenReturn(1)

            val result = provider.defer(
                QueueDeferralRequest(
                    entryId = QueueEntryId("entry-1"),
                    leaseId = QueueLeaseId("lease-1"),
                    availableAt = DataLoomInstant(3_000L),
                    reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
            )

            assertIs<ProviderOperationResult.Success<Unit>>(result)
        }
    }

    @Test
    fun `defer rejects a stale lease`() {
        runBlocking {
            whenever(dao.deferEntry(any(), any(), any())).thenReturn(0)
            val result = provider.defer(
                QueueDeferralRequest(
                    entryId = QueueEntryId("entry-1"),
                    leaseId = QueueLeaseId("stale-lease"),
                    availableAt = DataLoomInstant(3_000L),
                    reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
            )

            val failure = assertIs<ProviderOperationResult.Failure>(result)
            assertEquals("QUEUE_STALE_LEASE", failure.error.code.value)
        }
    }

    @Test
    fun `fail persists every canonical error field and disposition`() {
        runBlocking {
            whenever(
                dao.failEntry(
                    eq("entry-1"),
                    eq("lease-1"),
                    eq("DEAD_LETTER"),
                    eq("NETWORK_TEMPORARY"),
                    eq("NETWORK"),
                    eq("WARNING"),
                    eq("RECOVERABLE"),
                    eq("A temporary network failure occurred."),
                ),
            ).thenReturn(1)

            val result = provider.fail(
                QueueFailureRequest(
                    entryId = QueueEntryId("entry-1"),
                    leaseId = QueueLeaseId("lease-1"),
                    error = testError(),
                    disposition = QueueFailureDisposition.DEAD_LETTER,
                ),
            )

            assertIs<ProviderOperationResult.Success<Unit>>(result)
        }
    }

    @Test
    fun `cancel returns success for a pending entry`() {
        runBlocking {
            whenever(dao.cancelEntry("entry-1")).thenReturn(1)
            val result = provider.cancel(
                QueueCancellationRequest(
                    entryId = QueueEntryId("entry-1"),
                    context = executionContext(),
                ),
            )
            assertIs<ProviderOperationResult.Success<Unit>>(result)
        }
    }

    @Test
    fun `expired lease recovery returns exact affected count`() {
        runBlocking {
            whenever(dao.recoverExpiredLeases(5_000L)).thenReturn(3)
            val result = provider.recoverExpiredLeases(
                ExpiredLeaseRecoveryRequest(currentTime = DataLoomInstant(5_000L)),
            )
            val success = assertIs<ProviderOperationResult.Success<ExpiredLeaseRecoveryResult>>(result)
            assertEquals(3, success.value.recoveredEntries)
        }
    }

    private fun testError(): DataLoomError = TestDataLoomError(
        code = ErrorCode("NETWORK_TEMPORARY"),
        category = ErrorCategory.NETWORK,
        severity = ErrorSeverity.WARNING,
        recoverability = Recoverability.RECOVERABLE,
        message = "A temporary network failure occurred.",
    )

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
