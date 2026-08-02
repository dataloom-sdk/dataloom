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
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationCommandState
import io.dataloom.api.retry.RetryAdministrationCommandStatus
import io.dataloom.api.retry.RetryAdministrationCompareAndSetRequest
import io.dataloom.api.retry.RetryAdministrationCompareAndSetResult
import io.dataloom.api.retry.RetryAdministrationExecutionResult
import io.dataloom.api.retry.RetryAdministrationLoadResult
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class RoomRetryAdministrationExecutorInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var queueProvider: RoomQueueProvider
    private lateinit var stateStore: RoomRetryAdministrationStateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            DataLoomRoomDatabase::class.java,
        ).build()
        queueProvider = RoomQueueProvider(database)
        stateStore = RoomRetryAdministrationStateStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun authorizedRetryCommitsQueueMutationAndSucceededReceiptExactlyOnce() = runBlocking<Unit> {
        val terminalError = TestError(
            code = ErrorCode("NETWORK_FINAL"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "The network operation failed.",
        )
        val entry = queueEntry()
        assertIs<ProviderOperationResult.Success<Unit>>(
            queueProvider.enqueue(QueueEnqueueRequest(entry)),
        )
        acquire(1_000L, "lease-1")
        assertIs<ProviderOperationResult.Success<Unit>>(
            queueProvider.reschedule(
                QueueRescheduleRequest(
                    entryId = entry.id,
                    leaseId = QueueLeaseId("lease-1"),
                    retryAttempt = RetryAttempt(2),
                    availableAt = DataLoomInstant(1_500L),
                    error = TestError(
                        code = ErrorCode("NETWORK_TEMPORARY"),
                        category = ErrorCategory.NETWORK,
                        severity = ErrorSeverity.WARNING,
                        recoverability = Recoverability.RECOVERABLE,
                        message = "Retry later.",
                    ),
                    retryBudgetState = RetryBudgetState(
                        windowStartedAt = DataLoomInstant(1_100L),
                        lastEvaluatedAt = DataLoomInstant(1_200L),
                        cumulativeDelay = SchedulingDelay(500L),
                    ),
                ),
            ),
        )
        acquire(1_500L, "lease-2")
        assertIs<ProviderOperationResult.Success<Unit>>(
            queueProvider.fail(
                QueueFailureRequest(
                    entryId = entry.id,
                    leaseId = QueueLeaseId("lease-2"),
                    error = terminalError,
                    disposition = QueueFailureDisposition.FAILED,
                ),
            ),
        )

        val request = administrationRequest(terminalError)
        val authorizationId = RetryAdministrationAuthorizationId("authorization-1")
        val created = stateStore.compareAndSet(
            RetryAdministrationCompareAndSetRequest(
                commandId = request.commandId,
                expectedVersion = null,
                nextState = RetryAdministrationCommandState(
                    request = request,
                    status = RetryAdministrationCommandStatus.AUTHORIZED,
                    authorizationId = authorizationId,
                    effectiveRecoverability = Recoverability.RECOVERABLE,
                    updatedAt = DataLoomInstant(2_500L),
                ),
            ),
        )
        assertIs<RetryAdministrationCompareAndSetResult.Updated>(
            assertIs<ProviderOperationResult.Success<RetryAdministrationCompareAndSetResult>>(
                created,
            ).value,
        )

        val command = AuthorizedRetryAdministrationCommand(
            request = request,
            authorizationId = authorizationId,
            effectiveRecoverability = Recoverability.RECOVERABLE,
        )
        val executor = RoomRetryAdministrationExecutor(database, FixedClock(3_000L))
        assertIs<RetryAdministrationExecutionResult.Applied>(executor.execute(command))
        assertIs<RetryAdministrationExecutionResult.Applied>(executor.execute(command))

        val persisted = assertIs<RetryAdministrationLoadResult.Found>(
            assertIs<ProviderOperationResult.Success<RetryAdministrationLoadResult>>(
                stateStore.load(request.commandId),
            ).value,
        ).record
        assertEquals(RetryAdministrationCommandStatus.SUCCEEDED, persisted.state.status)
        assertEquals(1L, persisted.version)
        assertEquals(DataLoomInstant(3_000L), persisted.state.updatedAt)

        val acquired = assertIs<QueueAcquireResult.Entries>(
            acquire(3_000L, "lease-after-admin"),
        ).entries.single()
        assertEquals(RetryAttempt(2), acquired.retryAttempt)
        assertEquals(
            RetryBudgetState(
                windowStartedAt = DataLoomInstant(1_100L),
                lastEvaluatedAt = DataLoomInstant(1_200L),
                cumulativeDelay = SchedulingDelay(500L),
            ),
            acquired.retryBudgetState,
        )
        assertEquals(entry.workflowTimeoutState, acquired.workflowTimeoutState)
        assertNull(acquired.lastError)
        assertIs<QueueAcquireResult.NoEntries>(acquire(3_000L, "second-lease"))
    }

    @Test
    fun mismatchedDurableFailureRejectsWithoutChangingQueueOrCommand() = runBlocking<Unit> {
        val terminalError = TestError(
            code = ErrorCode("NETWORK_FINAL"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "The network operation failed.",
        )
        val entry = queueEntry(id = "mismatch")
        assertIs<ProviderOperationResult.Success<Unit>>(
            queueProvider.enqueue(QueueEnqueueRequest(entry)),
        )
        acquire(1_000L, "mismatch-lease", entry.id.value)
        assertIs<ProviderOperationResult.Success<Unit>>(
            queueProvider.fail(
                QueueFailureRequest(
                    entryId = entry.id,
                    leaseId = QueueLeaseId("mismatch-lease"),
                    error = terminalError,
                    disposition = QueueFailureDisposition.DEAD_LETTER,
                ),
            ),
        )

        val request = administrationRequest(
            terminalError.copy(code = ErrorCode("DIFFERENT_FAILURE")),
            entryId = entry.id,
            commandId = "command-mismatch",
        )
        val authorizationId = RetryAdministrationAuthorizationId("authorization-mismatch")
        stateStore.compareAndSet(
            RetryAdministrationCompareAndSetRequest(
                commandId = request.commandId,
                expectedVersion = null,
                nextState = RetryAdministrationCommandState(
                    request = request,
                    status = RetryAdministrationCommandStatus.AUTHORIZED,
                    authorizationId = authorizationId,
                    effectiveRecoverability = Recoverability.RECOVERABLE,
                    updatedAt = DataLoomInstant(2_500L),
                ),
            ),
        )

        val result = assertIs<RetryAdministrationExecutionResult.Rejected>(
            RoomRetryAdministrationExecutor(database, FixedClock(3_000L)).execute(
                AuthorizedRetryAdministrationCommand(
                    request = request,
                    authorizationId = authorizationId,
                    effectiveRecoverability = Recoverability.RECOVERABLE,
                ),
            ),
        )
        assertEquals("RETRY_ADMIN_TARGET_FAILURE_MISMATCH", result.reasonCode)

        val persisted = assertIs<RetryAdministrationLoadResult.Found>(
            assertIs<ProviderOperationResult.Success<RetryAdministrationLoadResult>>(
                stateStore.load(request.commandId),
            ).value,
        ).record
        assertEquals(RetryAdministrationCommandStatus.AUTHORIZED, persisted.state.status)
        assertEquals(0L, persisted.version)
        assertIs<QueueAcquireResult.NoEntries>(acquire(3_000L, "rejected-lease"))
    }

    private suspend fun acquire(
        now: Long,
        leaseId: String,
        expectedEntryId: String? = null,
    ): QueueAcquireResult {
        val result = assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(
            queueProvider.acquire(
                QueueAcquireRequest(
                    consumerId = QueueConsumerId("consumer-1"),
                    leaseId = QueueLeaseId(leaseId),
                    acquiredAt = DataLoomInstant(now),
                    leaseExpiresAt = DataLoomInstant(now + 1_000L),
                    maxEntries = 1,
                ),
            ),
        ).value
        if (expectedEntryId != null) {
            assertEquals(
                expectedEntryId,
                assertIs<QueueAcquireResult.Entries>(result).entries.single().id.value,
            )
        }
        return result
    }

    private fun queueEntry(id: String = "entry-1"): QueueEntry = QueueEntry(
        id = QueueEntryId(id),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-$id"),
            sessionId = SynchronizationSessionId("session-$id"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-$id"),
                correlationId = CorrelationId("correlation-$id"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        workflowTimeoutState = WorkflowTimeoutState(
            startedAt = DataLoomInstant(1_000L),
            deadline = DataLoomInstant(10_000L),
        ),
    )

    private fun administrationRequest(
        error: DataLoomError,
        entryId: QueueEntryId = QueueEntryId("entry-1"),
        commandId: String = "command-1",
    ): RetryAdministrationRequest = RetryAdministrationRequest(
        commandId = RetryAdministrationCommandId(commandId),
        queueEntryId = entryId,
        principalId = RetryAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(2_000L),
        action = RetryAdministrationAction.REQUEUE,
        reason = RetryAdministrationReason("Operator requested retry"),
        originalFailure = RetryFailureSnapshot(
            code = error.code,
            category = error.category,
            severity = error.severity,
            recoverability = error.recoverability,
        ),
    )

    private class FixedClock(private val value: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(value)
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
