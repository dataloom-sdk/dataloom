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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CircuitBreakerProbeLeaseRecoveryTest {
    private val scope = CircuitBreakerScope.provider(ProviderId("lease-provider"))
    private val clock = MutableClock(100L)
    private val store = InMemoryCircuitStore()
    private val configuration = CircuitBreakerConfiguration(
        failureThreshold = 1,
        failureWindow = SchedulingDelay(1_000L),
        openDuration = SchedulingDelay(1_000L),
        halfOpenProbeLeaseDuration = SchedulingDelay(500L),
    )

    @Test
    fun `active probe rejects contenders until its exclusive lease expires`() {
        val (_, probe) = openAndAcquireProbe()
        assertEquals(1L, probe.permit.generation)
        assertEquals(DataLoomInstant(1_600L), probe.record.state.probeLeaseUntil)

        clock.nowMillis = 1_599L
        val rejected = assertIs<CircuitBreakerPermission.Rejected>(
            runSuspend { coordinator().acquire(scope) },
        )
        assertEquals(CircuitBreakerRejectionReason.PROBE_IN_FLIGHT, rejected.reason)
        assertEquals(DataLoomInstant(1_600L), rejected.retryAt)
    }

    @Test
    fun `expired probe lease is recovered atomically after coordinator recreation`() {
        openAndAcquireProbe()
        clock.nowMillis = 1_600L

        val recovered = assertIs<CircuitBreakerPermission.ProbeAllowed>(
            runSuspend { coordinator().acquire(scope) },
        )
        assertEquals(2L, recovered.permit.generation)
        assertEquals(CircuitBreakerPhase.HALF_OPEN, recovered.record.state.phase)
        assertEquals(DataLoomInstant(2_100L), recovered.record.state.probeLeaseUntil)
    }

    @Test
    fun `late result from abandoned generation cannot mutate recovered probe`() {
        val (_, abandoned) = openAndAcquireProbe()
        clock.nowMillis = 1_600L
        val recovered = assertIs<CircuitBreakerPermission.ProbeAllowed>(
            runSuspend { coordinator().acquire(scope) },
        )

        clock.nowMillis = 1_601L
        assertIs<CircuitBreakerRecordResult.StaleProbe>(
            runSuspend { coordinator().recordSuccess(scope, abandoned.permit) },
        )
        val persisted = checkNotNull(store.record(scope)).state
        assertEquals(recovered.permit.generation, persisted.probeGeneration)
        assertEquals(CircuitBreakerPhase.HALF_OPEN, persisted.phase)
    }

    @Test
    fun `matching result at lease deadline is reported expired before recovery`() {
        val (_, probe) = openAndAcquireProbe()
        clock.nowMillis = 1_600L

        val expired = assertIs<CircuitBreakerRecordResult.ProbeLeaseExpired>(
            runSuspend { coordinator().recordSuccess(scope, probe.permit) },
        )
        assertEquals(DataLoomInstant(1_600L), expired.leaseUntil)
        assertEquals(1L, checkNotNull(store.record(scope)).state.probeGeneration)

        val recovered = assertIs<CircuitBreakerPermission.ProbeAllowed>(
            runSuspend { coordinator().acquire(scope) },
        )
        assertEquals(2L, recovered.permit.generation)
    }

    @Test
    fun `time range exhaustion rejects a probe instead of creating an expired lease`() {
        val nearMaximumClock = MutableClock(Long.MAX_VALUE - 1L)
        val nearMaximumStore = InMemoryCircuitStore()
        val coordinator = CircuitBreakerCoordinator(
            configuration = CircuitBreakerConfiguration(
                failureThreshold = 1,
                failureWindow = SchedulingDelay(1L),
                openDuration = SchedulingDelay(1L),
                halfOpenProbeLeaseDuration = SchedulingDelay(1L),
            ),
            clock = nearMaximumClock,
            stateStore = nearMaximumStore,
        )
        runSuspend { coordinator.recordFailure(scope) }
        nearMaximumClock.nowMillis = Long.MAX_VALUE

        val rejected = assertIs<CircuitBreakerPermission.Rejected>(
            runSuspend { coordinator.acquire(scope) },
        )
        assertEquals(
            CircuitBreakerRejectionReason.PROBE_LEASE_DEADLINE_EXHAUSTED,
            rejected.reason,
        )
    }

    @Test
    fun `probe lease duration must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerConfiguration(
                failureThreshold = 1,
                failureWindow = SchedulingDelay(1L),
                openDuration = SchedulingDelay(1L),
                halfOpenProbeLeaseDuration = SchedulingDelay.ZERO,
            )
        }
    }

    private fun openAndAcquireProbe(): Pair<CircuitBreakerCoordinator, CircuitBreakerPermission.ProbeAllowed> {
        val coordinator = coordinator()
        runSuspend { coordinator.recordFailure(scope) }
        clock.nowMillis = 1_100L
        val probe = assertIs<CircuitBreakerPermission.ProbeAllowed>(
            runSuspend { coordinator.acquire(scope) },
        )
        return coordinator to probe
    }

    private fun coordinator(): CircuitBreakerCoordinator = CircuitBreakerCoordinator(
        configuration = configuration,
        clock = clock,
        stateStore = store,
    )

    private class MutableClock(var nowMillis: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private class InMemoryCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

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
