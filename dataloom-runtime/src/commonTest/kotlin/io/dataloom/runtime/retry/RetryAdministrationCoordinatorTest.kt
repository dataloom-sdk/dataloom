package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationDecision
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationAuthorizer
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationCommandStatus
import io.dataloom.api.retry.RetryAdministrationCompareAndSetRequest
import io.dataloom.api.retry.RetryAdministrationCompareAndSetResult
import io.dataloom.api.retry.RetryAdministrationExecutionResult
import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.retry.RetryAdministrationLoadResult
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAdministrationStateRecord
import io.dataloom.api.retry.RetryAdministrationStateStore
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RetryAdministrationCoordinatorTest {
    private val clock = MutableClock(100L)
    private val authorizer = RecordingAuthorizer()
    private val store = InMemoryRetryAdministrationStore()
    private val executor = IdempotentRecordingExecutor()
    private val coordinator = RetryAdministrationCoordinator(
        clock = clock,
        authorizer = authorizer,
        stateStore = store,
        executor = executor,
    )

    @Test
    fun `recoverable network failure is requeued once and replay returns durable result`() {
        val request = request(
            action = RetryAdministrationAction.REQUEUE,
            failure = failure(
                category = ErrorCategory.NETWORK,
                recoverability = Recoverability.RECOVERABLE,
            ),
        )

        val first = assertIs<RetryAdministrationResult.Succeeded>(
            runSuspend { coordinator.execute(request) },
        )
        val replay = assertIs<RetryAdministrationResult.Succeeded>(
            runSuspend { coordinator.execute(request) },
        )

        assertEquals(first.record, replay.record)
        assertEquals(RetryAdministrationCommandStatus.SUCCEEDED, first.record.state.status)
        assertEquals(1, authorizer.invocations)
        assertEquals(1, executor.invocations)
        assertEquals(1, executor.appliedMutations)
    }

    @Test
    fun `protected failure requires explicit reclassification after authorization`() {
        val request = request(
            action = RetryAdministrationAction.REQUEUE,
            failure = failure(
                category = ErrorCategory.AUTHENTICATION,
                recoverability = Recoverability.RECOVERABLE,
            ),
        )

        val result = assertIs<RetryAdministrationResult.PolicyRejected>(
            runSuspend { coordinator.execute(request) },
        )

        assertEquals(
            "RETRY_RECLASSIFICATION_REQUIRED",
            result.record.state.rejectionReasonCode,
        )
        assertEquals(1, authorizer.invocations)
        assertEquals(0, executor.invocations)
    }

    @Test
    fun `authorized reclassification preserves original failure and applies recoverable command`() {
        val original = failure(
            category = ErrorCategory.SECURITY,
            recoverability = Recoverability.NON_RECOVERABLE,
        )
        val request = request(
            action = RetryAdministrationAction.RECLASSIFY_AND_REQUEUE,
            failure = original,
        )

        val result = assertIs<RetryAdministrationResult.Succeeded>(
            runSuspend { coordinator.execute(request) },
        )

        assertEquals(original, result.record.state.request.originalFailure)
        assertEquals(Recoverability.RECOVERABLE, result.record.state.effectiveRecoverability)
        assertEquals(Recoverability.RECOVERABLE, executor.lastCommand?.effectiveRecoverability)
        assertEquals(1, executor.appliedMutations)
    }

    @Test
    fun `authorization denial is durable and does not invoke executor on replay`() {
        authorizer.decision = RetryAdministrationAuthorizationDecision.Denied("ADMIN_RETRY_DENIED")
        val request = request()

        val first = assertIs<RetryAdministrationResult.AuthorizationDenied>(
            runSuspend { coordinator.execute(request) },
        )
        val replay = assertIs<RetryAdministrationResult.AuthorizationDenied>(
            runSuspend { coordinator.execute(request) },
        )

        assertEquals(first.record, replay.record)
        assertEquals(1, authorizer.invocations)
        assertEquals(0, executor.invocations)
        assertNull(first.record.state.authorizationId)
    }

    @Test
    fun `same command id with different immutable request is rejected`() {
        val firstRequest = request()
        assertIs<RetryAdministrationResult.Succeeded>(
            runSuspend { coordinator.execute(firstRequest) },
        )
        val conflicting = firstRequest.copy(
            reason = RetryAdministrationReason("different reason"),
        )

        val result = assertIs<RetryAdministrationResult.CommandConflict>(
            runSuspend { coordinator.execute(conflicting) },
        )

        assertEquals(firstRequest, result.existing.state.request)
        assertEquals(1, executor.invocations)
    }

    @Test
    fun `unconfirmed final audit write retries same idempotent command without duplicate mutation`() {
        store.failNextTerminalWrite = true
        val request = request()

        assertIs<RetryAdministrationResult.ExecutionRecordingUnconfirmed>(
            runSuspend { coordinator.execute(request) },
        )
        assertEquals(1, executor.invocations)
        assertEquals(1, executor.appliedMutations)

        val recovered = assertIs<RetryAdministrationResult.Succeeded>(
            runSuspend { coordinator.execute(request) },
        )

        assertEquals(RetryAdministrationCommandStatus.SUCCEEDED, recovered.record.state.status)
        assertEquals(2, executor.invocations)
        assertEquals(1, executor.appliedMutations)
    }

    @Test
    fun `clock regression after authorized execution evidence fails closed before redelivery`() {
        store.failNextTerminalWrite = true
        val request = request()
        assertIs<RetryAdministrationResult.ExecutionRecordingUnconfirmed>(
            runSuspend { coordinator.execute(request) },
        )
        clock.nowMillis = 99L

        val result = assertIs<RetryAdministrationResult.ClockRegression>(
            runSuspend { coordinator.execute(request) },
        )

        assertEquals(DataLoomInstant(99L), result.observedAt)
        assertEquals(DataLoomInstant(100L), result.persistedAt)
        assertEquals(1, executor.invocations)
    }

    private fun request(
        action: RetryAdministrationAction = RetryAdministrationAction.REQUEUE,
        failure: RetryFailureSnapshot = failure(),
    ): RetryAdministrationRequest = RetryAdministrationRequest(
        commandId = RetryAdministrationCommandId("command-1"),
        queueEntryId = QueueEntryId("entry-1"),
        principalId = RetryAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(90L),
        action = action,
        reason = RetryAdministrationReason("operator requested retry after correction"),
        originalFailure = failure,
    )

    private fun failure(
        category: ErrorCategory = ErrorCategory.NETWORK,
        recoverability: Recoverability = Recoverability.RECOVERABLE,
    ): RetryFailureSnapshot = RetryFailureSnapshot(
        code = ErrorCode("TEST_FAILURE"),
        category = category,
        severity = ErrorSeverity.ERROR,
        recoverability = recoverability,
    )

    private class MutableClock(
        var nowMillis: Long,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private class RecordingAuthorizer : RetryAdministrationAuthorizer {
        var invocations: Int = 0
        var decision: RetryAdministrationAuthorizationDecision =
            RetryAdministrationAuthorizationDecision.Authorized(
                RetryAdministrationAuthorizationId("authorization-1"),
            )

        override suspend fun authorize(
            request: RetryAdministrationRequest,
        ): RetryAdministrationAuthorizationDecision {
            invocations += 1
            return decision
        }
    }

    private class IdempotentRecordingExecutor : RetryAdministrationExecutor {
        private val appliedCommands = mutableSetOf<RetryAdministrationCommandId>()
        var invocations: Int = 0
        var appliedMutations: Int = 0
        var lastCommand: AuthorizedRetryAdministrationCommand? = null

        override suspend fun execute(
            command: AuthorizedRetryAdministrationCommand,
        ): RetryAdministrationExecutionResult {
            invocations += 1
            lastCommand = command
            if (appliedCommands.add(command.request.commandId)) {
                appliedMutations += 1
            }
            return RetryAdministrationExecutionResult.Applied
        }
    }

    private class InMemoryRetryAdministrationStore : RetryAdministrationStateStore {
        private val records = mutableMapOf<RetryAdministrationCommandId, RetryAdministrationStateRecord>()
        var failNextTerminalWrite: Boolean = false

        override suspend fun load(
            commandId: RetryAdministrationCommandId,
        ): ProviderOperationResult<RetryAdministrationLoadResult> = ProviderOperationResult.Success(
            records[commandId]?.let(RetryAdministrationLoadResult::Found)
                ?: RetryAdministrationLoadResult.Missing,
        )

        override suspend fun compareAndSet(
            request: RetryAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<RetryAdministrationCompareAndSetResult> {
            val current = records[request.commandId]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    RetryAdministrationCompareAndSetResult.Conflict(current),
                )
            }
            if (failNextTerminalWrite &&
                request.nextState.status != RetryAdministrationCommandStatus.AUTHORIZED
            ) {
                failNextTerminalWrite = false
                return ProviderOperationResult.Failure(TEST_ERROR)
            }
            val updated = RetryAdministrationStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.commandId] = updated
            return ProviderOperationResult.Success(
                RetryAdministrationCompareAndSetResult.Updated(updated),
            )
        }
    }

    private companion object {
        val TEST_ERROR: DataLoomError = object : DataLoomError {
            override val code: ErrorCode = ErrorCode("TEST_PERSISTENCE_FAILURE")
            override val category: ErrorCategory = ErrorCategory.STORAGE
            override val severity: ErrorSeverity = ErrorSeverity.ERROR
            override val recoverability: Recoverability = Recoverability.RECOVERABLE
            override val message: String = "Test persistence failure."
            override val cause: Throwable? = null
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext

            override fun resumeWith(resumeResult: Result<T>) {
                result = resumeResult
            }
        },
    )
    return checkNotNull(result).getOrThrow()
}
