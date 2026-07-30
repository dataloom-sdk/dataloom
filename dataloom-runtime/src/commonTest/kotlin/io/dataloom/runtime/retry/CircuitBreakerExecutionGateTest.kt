package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CircuitBreakerExecutionGateTest {
    private val scope = CircuitBreakerScope.provider(ProviderId("gate-provider"))
    private val eligibleError = TestError(
        code = ErrorCode("GATE-ELIGIBLE"),
        category = ErrorCategory.NETWORK,
        recoverability = Recoverability.RECOVERABLE,
    )
    private val persistenceError = TestError(
        code = ErrorCode("GATE-PERSISTENCE"),
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.NON_RECOVERABLE,
    )

    @Test
    fun `success executes once and preserves ignored recording evidence`() {
        val context = context()
        var calls = 0

        val result = assertIs<CircuitBreakerExecutionResult.Executed<String>>(
            runSuspend {
                context.gate.execute(scope) {
                    calls += 1
                    CircuitProtectedOperationResult.Success("ok")
                }
            },
        )

        assertEquals(1, calls)
        assertEquals(
            "ok",
            assertIs<CircuitProtectedOperationResult.Success<String>>(result.operationResult).value,
        )
        assertIs<CircuitBreakerRecordResult.Ignored>(result.recordResult)
        assertNull(context.store.record(scope))
    }

    @Test
    fun `eligible failure opens circuit and later call is rejected without execution`() {
        val context = context(failureThreshold = 1)
        var calls = 0

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend {
                context.gate.execute(scope) {
                    calls += 1
                    CircuitProtectedOperationResult.Failure(eligibleError)
                }
            },
        )
        val recorded = assertIs<CircuitBreakerRecordResult.Recorded>(first.recordResult)
        assertEquals(CircuitBreakerPhase.OPEN, recorded.record.state.phase)

        val rejected = assertIs<CircuitBreakerExecutionResult.Rejected>(
            runSuspend {
                context.gate.execute(scope) {
                    calls += 1
                    CircuitProtectedOperationResult.Success("must-not-run")
                }
            },
        )

        assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)
        assertEquals(DataLoomInstant(1_100L), rejected.retryAt)
        assertEquals(1, calls)
    }

    @Test
    fun `successful half open probe closes circuit and preserves operation value`() {
        val context = context(failureThreshold = 1)
        runSuspend { context.coordinator.recordFailure(scope) }
        context.clock.nowMillis = 1_100L

        val probe = assertIs<CircuitBreakerExecutionResult.Executed<String>>(
            runSuspend {
                context.gate.execute(scope) {
                    CircuitProtectedOperationResult.Success("probe-ok")
                }
            },
        )

        assertEquals(
            "probe-ok",
            assertIs<CircuitProtectedOperationResult.Success<String>>(probe.operationResult).value,
        )
        val recorded = assertIs<CircuitBreakerRecordResult.Recorded>(probe.recordResult)
        assertEquals(CircuitBreakerPhase.CLOSED, recorded.record.state.phase)
        assertEquals(1L, recorded.record.state.probeGeneration)
    }

    @Test
    fun `non circuit failure preserves error and resets prior failure count`() {
        val context = context(failureThreshold = 2)
        runSuspend { context.coordinator.recordFailure(scope) }
        assertEquals(1, context.store.record(scope)?.state?.consecutiveFailures)
        context.clock.nowMillis = 101L
        val semanticError = TestError(
            code = ErrorCode("GATE-VALIDATION"),
            category = ErrorCategory.VALIDATION,
            recoverability = Recoverability.NON_RECOVERABLE,
        )

        val result = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend {
                context.gate.execute(scope) {
                    CircuitProtectedOperationResult.NonCircuitFailure(semanticError)
                }
            },
        )

        assertEquals(
            semanticError,
            assertIs<CircuitProtectedOperationResult.NonCircuitFailure>(result.operationResult).error,
        )
        val recorded = assertIs<CircuitBreakerRecordResult.Recorded>(result.recordResult)
        assertEquals(CircuitBreakerPhase.CLOSED, recorded.record.state.phase)
        assertEquals(0, recorded.record.state.consecutiveFailures)
    }

    @Test
    fun `permission persistence failure prevents operation invocation`() {
        val context = context()
        context.store.loadError = persistenceError
        var calls = 0

        val result = assertIs<CircuitBreakerExecutionResult.PermissionPersistenceFailure>(
            runSuspend {
                context.gate.execute(scope) {
                    calls += 1
                    CircuitProtectedOperationResult.Success("must-not-run")
                }
            },
        )

        assertEquals(persistenceError, result.error)
        assertEquals(0, calls)
    }

    @Test
    fun `post execution persistence failure retains the completed operation result`() {
        val context = context()
        context.store.compareAndSetError = persistenceError
        var calls = 0

        val result = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend {
                context.gate.execute(scope) {
                    calls += 1
                    CircuitProtectedOperationResult.Failure(eligibleError)
                }
            },
        )

        assertEquals(1, calls)
        assertEquals(
            eligibleError,
            assertIs<CircuitProtectedOperationResult.Failure>(result.operationResult).error,
        )
        assertEquals(
            persistenceError,
            assertIs<CircuitBreakerRecordResult.PersistenceFailure>(result.recordResult).error,
        )
    }

    @Test
    fun `post execution contention retains the completed operation result`() {
        val context = context(maximumStateUpdateAttempts = 1)
        context.store.conflictsRemaining = 1
        var calls = 0

        val result = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend {
                context.gate.execute(scope) {
                    calls += 1
                    CircuitProtectedOperationResult.Failure(eligibleError)
                }
            },
        )

        assertEquals(1, calls)
        assertIs<CircuitProtectedOperationResult.Failure>(result.operationResult)
        assertIs<CircuitBreakerRecordResult.ContentionLimitReached>(result.recordResult)
    }

    @Test
    fun `permission contention prevents half open probe invocation`() {
        val context = context(
            failureThreshold = 1,
            maximumStateUpdateAttempts = 1,
        )
        runSuspend { context.coordinator.recordFailure(scope) }
        context.clock.nowMillis = 1_100L
        context.store.conflictsRemaining = 1
        var calls = 0

        val result = runSuspend {
            context.gate.execute(scope) {
                calls += 1
                CircuitProtectedOperationResult.Success("must-not-run")
            }
        }

        assertIs<CircuitBreakerExecutionResult.PermissionContentionLimitReached>(result)
        assertEquals(0, calls)
    }

    @Test
    fun `clock regression after execution remains attached to operation evidence`() {
        val context = context(failureThreshold = 2)
        runSuspend { context.coordinator.recordFailure(scope) }
        context.clock.nowMillis = 101L

        val result = assertIs<CircuitBreakerExecutionResult.Executed<String>>(
            runSuspend {
                context.gate.execute(scope) {
                    context.clock.nowMillis = 99L
                    CircuitProtectedOperationResult.Success("completed")
                }
            },
        )

        assertEquals(
            "completed",
            assertIs<CircuitProtectedOperationResult.Success<String>>(result.operationResult).value,
        )
        val regression = assertIs<CircuitBreakerRecordResult.ClockRegression>(result.recordResult)
        assertEquals(DataLoomInstant(99L), regression.observedAt)
        assertEquals(DataLoomInstant(100L), regression.persistedAt)
    }

    @Test
    fun `stale probe result retains completed operation evidence`() {
        val context = context(failureThreshold = 1)
        runSuspend { context.coordinator.recordFailure(scope) }
        context.clock.nowMillis = 1_100L

        val result = assertIs<CircuitBreakerExecutionResult.Executed<String>>(
            runSuspend {
                context.gate.execute(scope) {
                    val current = checkNotNull(context.store.record(scope))
                    context.store.forceState(
                        CircuitBreakerState(
                            scope = scope,
                            phase = CircuitBreakerPhase.CLOSED,
                            consecutiveFailures = 0,
                            failureWindowStartedAt = null,
                            openUntil = null,
                            probeGeneration = current.state.probeGeneration,
                            probeInFlight = false,
                            updatedAt = context.clock.now(),
                        ),
                    )
                    CircuitProtectedOperationResult.Success("completed-probe")
                }
            },
        )

        assertEquals(
            "completed-probe",
            assertIs<CircuitProtectedOperationResult.Success<String>>(result.operationResult).value,
        )
        assertIs<CircuitBreakerRecordResult.StaleProbe>(result.recordResult)
    }

    @Test
    fun `unexpected exception propagates without recording an outcome`() {
        val context = context()

        assertFailsWith<IllegalStateException> {
            runSuspend {
                context.gate.execute<String>(scope) {
                    throw IllegalStateException("programming failure")
                }
            }
        }

        assertNull(context.store.record(scope))
    }

    @Test
    fun `caller cancellation propagates without recording an outcome`() {
        val context = context()

        assertFailsWith<CancellationException> {
            runSuspend {
                context.gate.execute<String>(scope) {
                    throw CancellationException("cancelled")
                }
            }
        }

        assertNull(context.store.record(scope))
    }

    private fun context(
        failureThreshold: Int = 2,
        maximumStateUpdateAttempts: Int = 3,
    ): TestContext = TestContext(
        failureThreshold = failureThreshold,
        maximumStateUpdateAttempts = maximumStateUpdateAttempts,
    )

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Sanitized circuit gate test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class TestContext(
        failureThreshold: Int,
        maximumStateUpdateAttempts: Int,
    ) {
        val clock = MutableClock(100L)
        val store = InMemoryCircuitStore()
        val coordinator = CircuitBreakerCoordinator(
            configuration = CircuitBreakerConfiguration(
                failureThreshold = failureThreshold,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(1_000L),
                maximumStateUpdateAttempts = maximumStateUpdateAttempts,
            ),
            clock = clock,
            stateStore = store,
        )
        val gate = CircuitBreakerExecutionGate(coordinator)
    }

    private class MutableClock(
        var nowMillis: Long,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private class InMemoryCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()
        var loadError: DataLoomError? = null
        var compareAndSetError: DataLoomError? = null
        var conflictsRemaining: Int = 0

        fun record(scope: CircuitBreakerScope): CircuitBreakerStateRecord? = records[scope]

        fun forceState(state: CircuitBreakerState) {
            val current = records[state.scope]
            records[state.scope] = CircuitBreakerStateRecord(
                state = state,
                version = (current?.version ?: -1L) + 1L,
            )
        }

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadError?.let { return ProviderOperationResult.Failure(it) }
            return ProviderOperationResult.Success(
                records[scope]?.let(CircuitBreakerLoadResult::Found)
                    ?: CircuitBreakerLoadResult.Missing,
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            compareAndSetError?.let { return ProviderOperationResult.Failure(it) }
            val current = records[request.scope]
            if (conflictsRemaining > 0) {
                conflictsRemaining -= 1
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(updated),
            )
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    completed = result
                }
            },
        )
        return checkNotNull(completed).getOrThrow()
    }
}
