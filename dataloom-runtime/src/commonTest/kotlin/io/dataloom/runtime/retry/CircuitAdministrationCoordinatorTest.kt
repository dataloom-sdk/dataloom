package io.dataloom.runtime.retry

import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationDecision
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationAuthorizer
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationExecutionResult
import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationFailureSnapshot
import io.dataloom.api.circuit.CircuitAdministrationLoadResult
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitAdministrationStateRecord
import io.dataloom.api.circuit.CircuitAdministrationStateStore
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class CircuitAdministrationCoordinatorTest {
    private val clock = MutableClock(100L)
    private val authorizer = RecordingAuthorizer()
    private val store = InMemoryCircuitAdministrationStore()
    private val executor = IdempotentRecordingExecutor(clock)
    private val coordinator = CircuitAdministrationCoordinator(
        clock = clock,
        authorizer = authorizer,
        stateStore = store,
        executor = executor,
    )

    @Test
    fun `authorized open is applied once and replay returns durable audit`() {
        val request = request(
            action = CircuitAdministrationAction.OPEN,
            openUntil = DataLoomInstant(200L),
        )

        val first = assertIs<CircuitAdministrationResult.Succeeded>(
            runCircuitSuspend { coordinator.execute(request) },
        )
        val replay = assertIs<CircuitAdministrationResult.Succeeded>(
            runCircuitSuspend { coordinator.execute(request) },
        )

        assertEquals(first.record, replay.record)
        assertEquals(CircuitAdministrationCommandStatus.SUCCEEDED, first.record.state.status)
        assertEquals(CircuitBreakerPhase.OPEN, first.record.state.resultingRecord?.state?.phase)
        assertEquals(
            DataLoomInstant(200L),
            first.record.state.resultingRecord?.state?.openUntil,
        )
        assertEquals(1L, first.record.state.resultingRecord?.version)
        assertEquals(1, authorizer.invocations)
        assertEquals(1, executor.invocations)
        assertEquals(1, executor.appliedMutations)
    }

    @Test
    fun `open deadline at execution time is durably rejected before executor`() {
        val request = request(
            action = CircuitAdministrationAction.OPEN,
            openUntil = DataLoomInstant(100L),
        )

        val result = assertIs<CircuitAdministrationResult.PolicyRejected>(
            runCircuitSuspend { coordinator.execute(request) },
        )

        assertEquals(
            "CIRCUIT_ADMINISTRATION_OPEN_DEADLINE_EXPIRED",
            result.record.state.rejectionReasonCode,
        )
        assertEquals(0, executor.invocations)
    }

    @Test
    fun `authorization denial is durable and does not invoke executor on replay`() {
        authorizer.decision = CircuitAdministrationAuthorizationDecision.Denied(
            "CIRCUIT_ADMINISTRATION_DENIED",
        )
        val request = request(action = CircuitAdministrationAction.CLOSE)

        val first = assertIs<CircuitAdministrationResult.AuthorizationDenied>(
            runCircuitSuspend { coordinator.execute(request) },
        )
        val replay = assertIs<CircuitAdministrationResult.AuthorizationDenied>(
            runCircuitSuspend { coordinator.execute(request) },
        )

        assertEquals(first.record, replay.record)
        assertEquals(1, authorizer.invocations)
        assertEquals(0, executor.invocations)
        assertNull(first.record.state.authorizationId)
    }

    @Test
    fun `same command id with changed immutable request is rejected`() {
        val firstRequest = request(action = CircuitAdministrationAction.CLOSE)
        assertIs<CircuitAdministrationResult.Succeeded>(
            runCircuitSuspend { coordinator.execute(firstRequest) },
        )
        val conflicting = firstRequest.copy(
            reason = CircuitAdministrationReason("different reason"),
        )

        val result = assertIs<CircuitAdministrationResult.CommandConflict>(
            runCircuitSuspend { coordinator.execute(conflicting) },
        )

        assertEquals(firstRequest, result.existing.state.request)
        assertEquals(1, executor.invocations)
    }

    @Test
    fun `unconfirmed final audit retries idempotent command without duplicate mutation`() {
        store.failNextTerminalWrite = true
        val request = request(action = CircuitAdministrationAction.RESET)

        assertIs<CircuitAdministrationResult.ExecutionRecordingUnconfirmed>(
            runCircuitSuspend { coordinator.execute(request) },
        )
        assertEquals(1, executor.invocations)
        assertEquals(1, executor.appliedMutations)

        val recovered = assertIs<CircuitAdministrationResult.Succeeded>(
            runCircuitSuspend { coordinator.execute(request) },
        )

        assertEquals(CircuitAdministrationCommandStatus.SUCCEEDED, recovered.record.state.status)
        assertEquals(2, executor.invocations)
        assertEquals(1, executor.appliedMutations)
    }

    @Test
    fun `clock regression after authorized evidence fails closed before redelivery`() {
        store.failNextTerminalWrite = true
        val request = request(action = CircuitAdministrationAction.CLOSE)
        assertIs<CircuitAdministrationResult.ExecutionRecordingUnconfirmed>(
            runCircuitSuspend { coordinator.execute(request) },
        )
        clock.nowMillis = 99L

        val result = assertIs<CircuitAdministrationResult.ClockRegression>(
            runCircuitSuspend { coordinator.execute(request) },
        )

        assertEquals(DataLoomInstant(99L), result.observedAt)
        assertEquals(DataLoomInstant(100L), result.persistedAt)
        assertEquals(1, executor.invocations)
    }

    @Test
    fun `exact request and authorization are supplied to executor`() {
        val request = request(action = CircuitAdministrationAction.CLOSE)

        assertIs<CircuitAdministrationResult.Succeeded>(
            runCircuitSuspend { coordinator.execute(request) },
        )

        assertSame(request, authorizer.lastRequest)
        assertSame(request, executor.lastCommand?.request)
        assertEquals("authorization-1", executor.lastCommand?.authorizationId?.value)
    }

    @Test
    fun `open requires a future deadline and close reset forbid one`() {
        assertFailsWith<IllegalArgumentException> {
            request(action = CircuitAdministrationAction.OPEN, openUntil = null)
        }
        assertFailsWith<IllegalArgumentException> {
            request(
                action = CircuitAdministrationAction.OPEN,
                openUntil = DataLoomInstant(90L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request(
                action = CircuitAdministrationAction.CLOSE,
                openUntil = DataLoomInstant(200L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request(
                action = CircuitAdministrationAction.RESET,
                openUntil = DataLoomInstant(200L),
            )
        }
    }

    @Test
    fun `non-positive contention attempt bound is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationCoordinator(
                clock = clock,
                authorizer = authorizer,
                stateStore = store,
                executor = executor,
                maximumStateUpdateAttempts = 0,
            )
        }
    }

    @Test
    fun `executor rejection and failure become durable terminal evidence`() {
        executor.resultOverride = CircuitAdministrationExecutionResult.Rejected(
            "CIRCUIT_STATE_CHANGED",
        )
        val rejected = assertIs<CircuitAdministrationResult.ExecutionRejected>(
            runCircuitSuspend { coordinator.execute(request(CircuitAdministrationAction.CLOSE)) },
        )
        assertEquals("CIRCUIT_STATE_CHANGED", rejected.record.state.rejectionReasonCode)

        val secondRequest = request(CircuitAdministrationAction.RESET).copy(
            commandId = CircuitAdministrationCommandId("command-2"),
        )
        executor.resultOverride = CircuitAdministrationExecutionResult.Failed(
            CircuitAdministrationFailureSnapshot(
                code = ErrorCode("CIRCUIT_EXECUTION_FAILED"),
                category = ErrorCategory.STORAGE,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
            ),
        )
        val failed = assertIs<CircuitAdministrationResult.ExecutionFailed>(
            runCircuitSuspend { coordinator.execute(secondRequest) },
        )

        assertEquals(
            ErrorCode("CIRCUIT_EXECUTION_FAILED"),
            failed.record.state.executionFailure?.code,
        )
    }

    @Test
    fun `persistent compare and set contention stops at configured bound`() {
        store.alwaysConflict = true
        val bounded = CircuitAdministrationCoordinator(
            clock = clock,
            authorizer = authorizer,
            stateStore = store,
            executor = executor,
            maximumStateUpdateAttempts = 3,
        )

        assertIs<CircuitAdministrationResult.ContentionLimitReached>(
            runCircuitSuspend { bounded.execute(request(CircuitAdministrationAction.CLOSE)) },
        )
        assertEquals(3, authorizer.invocations)
        assertEquals(0, executor.invocations)
    }

    @Test
    fun `persistence failure before durable admission fails closed`() {
        store.failNextLoad = true

        val result = assertIs<CircuitAdministrationResult.PersistenceFailure>(
            runCircuitSuspend { coordinator.execute(request(CircuitAdministrationAction.CLOSE)) },
        )

        assertSame(TEST_ERROR, result.error)
        assertEquals(0, authorizer.invocations)
        assertEquals(0, executor.invocations)
    }

    private fun request(
        action: CircuitAdministrationAction,
        openUntil: DataLoomInstant? = null,
    ): CircuitAdministrationRequest = CircuitAdministrationRequest(
        commandId = CircuitAdministrationCommandId("command-1"),
        scope = CircuitBreakerScope.global(),
        principalId = CircuitAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(90L),
        action = action,
        reason = CircuitAdministrationReason("operator requested circuit operation"),
        openUntil = openUntil,
    )

    private class MutableClock(
        var nowMillis: Long,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private class RecordingAuthorizer : CircuitAdministrationAuthorizer {
        var invocations: Int = 0
        var lastRequest: CircuitAdministrationRequest? = null
        var decision: CircuitAdministrationAuthorizationDecision =
            CircuitAdministrationAuthorizationDecision.Authorized(
                CircuitAdministrationAuthorizationId("authorization-1"),
            )

        override suspend fun authorize(
            request: CircuitAdministrationRequest,
        ): CircuitAdministrationAuthorizationDecision {
            invocations += 1
            lastRequest = request
            return decision
        }
    }

    private class IdempotentRecordingExecutor(
        private val clock: DataLoomClock,
    ) : CircuitAdministrationExecutor {
        private val applied = mutableMapOf<
            CircuitAdministrationCommandId,
            CircuitAdministrationExecutionResult.Applied,
        >()
        var invocations: Int = 0
        var appliedMutations: Int = 0
        var lastCommand: AuthorizedCircuitAdministrationCommand? = null
        var resultOverride: CircuitAdministrationExecutionResult? = null

        override suspend fun execute(
            command: AuthorizedCircuitAdministrationCommand,
        ): CircuitAdministrationExecutionResult {
            invocations += 1
            lastCommand = command
            resultOverride?.let { result -> return result }
            return applied.getOrPut(command.request.commandId) {
                appliedMutations += 1
                CircuitAdministrationExecutionResult.Applied(
                    record = CircuitBreakerStateRecord(
                        state = stateFor(command.request),
                        version = 1L,
                    ),
                )
            }
        }

        private fun stateFor(request: CircuitAdministrationRequest): CircuitBreakerState {
            val observedAt = clock.now()
            return when (request.action) {
                CircuitAdministrationAction.OPEN -> CircuitBreakerState(
                    scope = request.scope,
                    phase = CircuitBreakerPhase.OPEN,
                    consecutiveFailures = 0,
                    failureWindowStartedAt = null,
                    openUntil = checkNotNull(request.openUntil),
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = observedAt,
                )
                CircuitAdministrationAction.CLOSE,
                CircuitAdministrationAction.RESET,
                -> CircuitBreakerState(
                    scope = request.scope,
                    phase = CircuitBreakerPhase.CLOSED,
                    consecutiveFailures = 0,
                    failureWindowStartedAt = null,
                    openUntil = null,
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = observedAt,
                )
            }
        }
    }

    private class InMemoryCircuitAdministrationStore : CircuitAdministrationStateStore {
        private val records = mutableMapOf<
            CircuitAdministrationCommandId,
            CircuitAdministrationStateRecord,
        >()
        var failNextTerminalWrite: Boolean = false
        var failNextLoad: Boolean = false
        var alwaysConflict: Boolean = false

        override suspend fun load(
            commandId: CircuitAdministrationCommandId,
        ): ProviderOperationResult<CircuitAdministrationLoadResult> {
            if (failNextLoad) {
                failNextLoad = false
                return ProviderOperationResult.Failure(TEST_ERROR)
            }
            return ProviderOperationResult.Success(
                records[commandId]?.let(CircuitAdministrationLoadResult::Found)
                    ?: CircuitAdministrationLoadResult.Missing,
            )
        }

        override suspend fun compareAndSet(
            request: CircuitAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<CircuitAdministrationCompareAndSetResult> {
            val current = records[request.commandId]
            if (alwaysConflict || current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitAdministrationCompareAndSetResult.Conflict(current),
                )
            }
            if (failNextTerminalWrite &&
                request.nextState.status != CircuitAdministrationCommandStatus.AUTHORIZED
            ) {
                failNextTerminalWrite = false
                return ProviderOperationResult.Failure(TEST_ERROR)
            }
            val updated = CircuitAdministrationStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.commandId] = updated
            return ProviderOperationResult.Success(
                CircuitAdministrationCompareAndSetResult.Updated(updated),
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

private fun <T> runCircuitSuspend(block: suspend () -> T): T {
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
