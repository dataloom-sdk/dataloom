package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CircuitBreakerCoordinatorTest {
    private val scope = CircuitBreakerScope.provider(ProviderId("transport-provider"))
    private val clock = MutableClock(100L)
    private val store = InMemoryCircuitStore()
    private val configuration = CircuitBreakerConfiguration(
        failureThreshold = 3,
        failureWindow = SchedulingDelay(1_000L),
        openDuration = SchedulingDelay(5_000L),
    )

    @Test
    fun `threshold opens circuit and rejects before deadline`() {
        val coordinator = coordinator()
        assertIs<CircuitBreakerRecordResult.Recorded>(
            runSuspend { coordinator.recordFailure(scope) },
        )
        clock.nowMillis = 200L
        runSuspend { coordinator.recordFailure(scope) }
        clock.nowMillis = 300L
        val opened = assertIs<CircuitBreakerRecordResult.Recorded>(
            runSuspend { coordinator.recordFailure(scope) },
        )
        assertEquals(CircuitBreakerPhase.OPEN, opened.record.state.phase)
        assertEquals(DataLoomInstant(5_300L), opened.record.state.openUntil)

        clock.nowMillis = 1_000L
        val rejected = assertIs<CircuitBreakerPermission.Rejected>(
            runSuspend { coordinator.acquire(scope) },
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)
        assertEquals(DataLoomInstant(5_300L), rejected.retryAt)
    }

    @Test
    fun `exact deadline grants one controlled probe`() {
        val coordinator = openCircuit()
        clock.nowMillis = 5_300L
        val probe = assertIs<CircuitBreakerPermission.ProbeAllowed>(
            runSuspend { coordinator.acquire(scope) },
        )
        assertEquals(1L, probe.permit.generation)

        val second = assertIs<CircuitBreakerPermission.Rejected>(
            runSuspend { coordinator.acquire(scope) },
        )
        assertEquals(CircuitBreakerRejectionReason.PROBE_IN_FLIGHT, second.reason)
    }

    @Test
    fun `successful probe closes and normal traffic resumes`() {
        val coordinator = openCircuit()
        clock.nowMillis = 5_300L
        val probe = assertIs<CircuitBreakerPermission.ProbeAllowed>(
            runSuspend { coordinator.acquire(scope) },
        )
        clock.nowMillis = 5_301L
        val closed = assertIs<CircuitBreakerRecordResult.Recorded>(
            runSuspend { coordinator.recordSuccess(scope, probe.permit) },
        )
        assertEquals(CircuitBreakerPhase.CLOSED, closed.record.state.phase)
        assertIs<CircuitBreakerPermission.Allowed>(
            runSuspend { coordinator.acquire(scope) },
        )
    }

    @Test
    fun `failed probe reopens for a new duration`() {
        val coordinator = openCircuit()
        clock.nowMillis = 5_300L
        val probe = assertIs<CircuitBreakerPermission.ProbeAllowed>(
            runSuspend { coordinator.acquire(scope) },
        )
        clock.nowMillis = 5_400L
        val reopened = assertIs<CircuitBreakerRecordResult.Recorded>(
            runSuspend { coordinator.recordFailure(scope, probe.permit) },
        )
        assertEquals(CircuitBreakerPhase.OPEN, reopened.record.state.phase)
        assertEquals(DataLoomInstant(10_400L), reopened.record.state.openUntil)
    }

    @Test
    fun `open state survives coordinator recreation`() {
        openCircuit()
        val recreated = coordinator()
        clock.nowMillis = 1_000L
        val rejected = assertIs<CircuitBreakerPermission.Rejected>(
            runSuspend { recreated.acquire(scope) },
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)
    }

    @Test
    fun `failure outside window starts a new count`() {
        val coordinator = coordinator()
        runSuspend { coordinator.recordFailure(scope) }
        clock.nowMillis = 1_101L
        val recorded = assertIs<CircuitBreakerRecordResult.Recorded>(
            runSuspend { coordinator.recordFailure(scope) },
        )
        assertEquals(CircuitBreakerPhase.CLOSED, recorded.record.state.phase)
        assertEquals(1, recorded.record.state.consecutiveFailures)
        assertEquals(DataLoomInstant(1_101L), recorded.record.state.failureWindowStartedAt)
    }

    @Test
    fun `persisted time regression rejects fail closed`() {
        val coordinator = openCircuit()
        clock.nowMillis = 299L
        val rejected = assertIs<CircuitBreakerPermission.Rejected>(
            runSuspend { coordinator.acquire(scope) },
        )
        assertEquals(CircuitBreakerRejectionReason.CLOCK_REGRESSION, rejected.reason)
    }

    @Test
    fun `stale probe outcome cannot mutate a recovered circuit`() {
        val coordinator = openCircuit()
        clock.nowMillis = 5_300L
        val probe = assertIs<CircuitBreakerPermission.ProbeAllowed>(
            runSuspend { coordinator.acquire(scope) },
        )
        clock.nowMillis = 5_301L
        runSuspend { coordinator.recordSuccess(scope, probe.permit) }
        val versionAfterClose = store.record(scope)?.version

        clock.nowMillis = 5_302L
        assertIs<CircuitBreakerRecordResult.StaleProbe>(
            runSuspend { coordinator.recordFailure(scope, probe.permit) },
        )
        assertEquals(versionAfterClose, store.record(scope)?.version)
    }

    @Test
    fun `compare and set conflict is retried`() {
        val coordinator = coordinator()
        store.conflictsRemaining = 1
        val result = assertIs<CircuitBreakerRecordResult.Recorded>(
            runSuspend { coordinator.recordFailure(scope) },
        )
        assertEquals(1, result.record.state.consecutiveFailures)
        assertEquals(0, store.conflictsRemaining)
    }

    private fun openCircuit(): CircuitBreakerCoordinator {
        val coordinator = coordinator()
        runSuspend { coordinator.recordFailure(scope) }
        clock.nowMillis = 200L
        runSuspend { coordinator.recordFailure(scope) }
        clock.nowMillis = 300L
        runSuspend { coordinator.recordFailure(scope) }
        return coordinator
    }

    private fun coordinator(): CircuitBreakerCoordinator = CircuitBreakerCoordinator(
        configuration = configuration,
        clock = clock,
        stateStore = store,
    )

    private class MutableClock(
        var nowMillis: Long,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private class InMemoryCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()
        var conflictsRemaining: Int = 0

        fun record(scope: CircuitBreakerScope): CircuitBreakerStateRecord? = records[scope]

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> = ProviderOperationResult.Success(
            records[scope]?.let(CircuitBreakerLoadResult::Found)
                ?: CircuitBreakerLoadResult.Missing,
        )

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
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
