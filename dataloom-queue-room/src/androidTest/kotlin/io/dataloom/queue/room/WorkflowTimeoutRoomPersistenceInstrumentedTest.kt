package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class WorkflowTimeoutRoomPersistenceInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun workflowTimeoutSurvivesReopenRetryDeferralAndLeaseRecovery() = runBlocking {
        val timeoutState = WorkflowTimeoutState(
            startedAt = DataLoomInstant(1_000L),
            deadline = DataLoomInstant(60_000L),
        )

        openDatabase().use { database ->
            val provider = RoomQueueProvider(database)
            assertIs<ProviderOperationResult.Success<Unit>>(
                provider.enqueue(
                    QueueEnqueueRequest(entry(timeoutState)),
                ),
            )
        }

        openDatabase().use { database ->
            val provider = RoomQueueProvider(database)
            val firstLease = acquired(provider, now = 2_000L, leaseId = "lease-1")
            assertEquals(timeoutState, firstLease.workflowTimeoutState)

            assertIs<ProviderOperationResult.Success<Unit>>(
                provider.reschedule(
                    QueueRescheduleRequest(
                        entryId = firstLease.id,
                        leaseId = requireNotNull(firstLease.lease).id,
                        retryAttempt = RetryAttempt(1),
                        availableAt = DataLoomInstant(3_000L),
                        error = TestError(),
                    ),
                ),
            )

            val retryLease = acquired(provider, now = 3_000L, leaseId = "lease-2")
            assertEquals(timeoutState, retryLease.workflowTimeoutState)

            assertIs<ProviderOperationResult.Success<Unit>>(
                provider.defer(
                    QueueDeferralRequest(
                        entryId = retryLease.id,
                        leaseId = requireNotNull(retryLease.lease).id,
                        availableAt = DataLoomInstant(4_000L),
                        reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                    ),
                ),
            )

            val deferredLease = acquired(
                provider,
                now = 4_000L,
                leaseId = "lease-3",
                expiresAt = 5_000L,
            )
            assertEquals(timeoutState, deferredLease.workflowTimeoutState)

            assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(
                provider.recoverExpiredLeases(
                    ExpiredLeaseRecoveryRequest(currentTime = DataLoomInstant(5_001L)),
                ),
            )

            val recoveredLease = acquired(provider, now = 6_000L, leaseId = "lease-4")
            assertEquals(timeoutState, recoveredLease.workflowTimeoutState)
        }
    }

    private fun openDatabase(): DataLoomRoomDatabase = Room.databaseBuilder(
        context,
        DataLoomRoomDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(*DataLoomRoomMigrations.ALL)
        .build()

    private suspend fun acquired(
        provider: RoomQueueProvider,
        now: Long,
        leaseId: String,
        expiresAt: Long = now + 1_000L,
    ): QueueEntry {
        val result = provider.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId("consumer-1"),
                leaseId = QueueLeaseId(leaseId),
                acquiredAt = DataLoomInstant(now),
                leaseExpiresAt = DataLoomInstant(expiresAt),
                maxEntries = 1,
            ),
        )
        return assertIs<QueueAcquireResult.Entries>(
            assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(result).value,
        ).entries.single()
    }

    private fun entry(timeoutState: WorkflowTimeoutState): QueueEntry = QueueEntry(
        id = QueueEntryId("deadline-entry"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        workflowTimeoutState = timeoutState,
    )

    private data class TestError(
        override val code: ErrorCode = ErrorCode("NETWORK_RETRY"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Retry later.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        const val DATABASE_NAME = "dataloom-workflow-timeout-persistence-test"
    }
}
