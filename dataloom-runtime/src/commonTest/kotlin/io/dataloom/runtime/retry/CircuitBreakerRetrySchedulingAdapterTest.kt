package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
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

class CircuitBreakerRetrySchedulingAdapterTest {
    private val providerId = ProviderId("retry-scheduler")
    private val scope = CircuitBreakerScope.provider(providerId)
    private val request = ScheduleRequest(ScheduleId("retry-schedule"))

    @Test
    fun `accepted schedule is executed once and receipt is preserved`() {
        val scheduler = RecordingScheduler(providerId)
        val adapter = adapter(scheduler, failureThreshold = 2)

        val result = assertIs<CircuitBreakerExecutionResult.Executed<ScheduleReceipt>>(
            runSuspend { adapter.schedule(request) },
        )

        assertEquals(1, scheduler.scheduleCalls)
        assertEquals(
            request.id,
            assertIs<CircuitProtectedOperationResult.Success<ScheduleReceipt>>(
                result.operationResult,
            ).value.id,
        )
        assertIs<CircuitBreakerRecordResult.Ignored>(result.recordResult)
    }

    @Test
    fun `recoverable scheduler failure opens circuit and blocks a second schedule`() {
        val schedulerError = TestError(
            category = ErrorCategory.SCHEDULER,
            recoverability = Recoverability.RECOVERABLE,
        )
        val scheduler = RecordingScheduler(providerId, failure = schedulerError)
        val adapter = adapter(scheduler, failureThreshold = 1)

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend { adapter.schedule(request) },
        )
        assertEquals(
            schedulerError,
            assertIs<CircuitProtectedOperationResult.Failure>(first.operationResult).error,
        )
        assertIs<CircuitBreakerRecordResult.Recorded>(first.recordResult)

        assertIs<CircuitBreakerExecutionResult.Rejected>(
            runSuspend { adapter.schedule(request) },
        )
        assertEquals(1, scheduler.scheduleCalls)
    }

    @Test
    fun `non recoverable scheduler failure remains visible without opening circuit`() {
        val schedulerError = TestError(
            category = ErrorCategory.CONFIGURATION,
            recoverability = Recoverability.NON_RECOVERABLE,
        )
        val scheduler = RecordingScheduler(providerId, failure = schedulerError)
        val adapter = adapter(scheduler, failureThreshold = 1)

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend { adapter.schedule(request) },
        )
        assertEquals(
            schedulerError,
            assertIs<CircuitProtectedOperationResult.NonCircuitFailure>(
                first.operationResult,
            ).error,
        )
        assertIs<CircuitBreakerRecordResult.Ignored>(first.recordResult)

        runSuspend { adapter.schedule(request) }
        assertEquals(2, scheduler.scheduleCalls)
    }

    @Test
    fun `provider scoped adapter rejects a mismatched scheduler identity`() {
        val scheduler = RecordingScheduler(providerId)
        val context = circuitContext(failureThreshold = 1)

        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerRetrySchedulingAdapter(
                schedulerProvider = scheduler,
                providerOperationAdapter = context.adapter,
                scope = CircuitBreakerScope.provider(ProviderId("different-provider")),
            )
        }
    }

    private fun adapter(
        scheduler: SchedulerProvider,
        failureThreshold: Int,
    ): CircuitBreakerRetrySchedulingAdapter {
        val context = circuitContext(failureThreshold)
        return CircuitBreakerRetrySchedulingAdapter(
            schedulerProvider = scheduler,
            providerOperationAdapter = context.adapter,
            scope = scope,
        )
    }

    private fun circuitContext(failureThreshold: Int): CircuitContext {
        val coordinator = CircuitBreakerCoordinator(
            configuration = CircuitBreakerConfiguration(
                failureThreshold = failureThreshold,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(1_000L),
            ),
            clock = FixedClock,
            stateStore = InMemoryCircuitStore(),
        )
        return CircuitContext(
            CircuitBreakerProviderOperationAdapter(
                CircuitBreakerExecutionGate(coordinator),
            ),
        )
    }

    private data class CircuitContext(
        val adapter: CircuitBreakerProviderOperationAdapter,
    )

    private data class TestError(
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val code: ErrorCode = ErrorCode("RETRY-SCHEDULER-FAILURE"),
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Sanitized retry scheduling failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class RecordingScheduler(
        providerId: ProviderId,
        private val failure: DataLoomError? = null,
    ) : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Retry Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )
        var scheduleCalls: Int = 0

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(
            request: ScheduleRequest,
        ): ProviderOperationResult<ScheduleReceipt> {
            scheduleCalls += 1
            return failure?.let(ProviderOperationResult::Failure)
                ?: ProviderOperationResult.Success(ScheduleReceipt(request.id))
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(100L)
    }

    private class InMemoryCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

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
