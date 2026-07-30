package io.dataloom.runtime.retry

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
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

class RetryBudgetSchedulerIntegrationTest {

    private data class TestError(
        override val code: ErrorCode = ErrorCode("DL-BUDGET-SCHEDULER"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized retry budget scheduler failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class MutableClock(var instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class RecordingScheduler(
        private val fail: Boolean = false,
    ) : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("budget-scheduler"),
            name = ProviderName("Budget Scheduler"),
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
            return if (fail) {
                ProviderOperationResult.Failure(TestError(category = ErrorCategory.SCHEDULER))
            } else {
                ProviderOperationResult.Success(ScheduleReceipt(request.id))
            }
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private val synchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("budget-scheduler-workflow"),
        sessionId = SynchronizationSessionId("budget-scheduler-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("budget-scheduler-execution"),
            correlationId = CorrelationId("budget-scheduler-correlation"),
        ),
    )
    private val failure = SynchronizationResult.Failed(
        request = synchronizationRequest,
        completedAt = DataLoomInstant(1_000L),
        summary = SynchronizationSummary(),
        error = TestError(),
    )
    private val schedulingConfiguration = RetrySchedulingConfiguration(
        constraints = ScheduleConstraints(),
        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
    )

    @Test
    fun `scheduler acceptance returns next budget state`() {
        val scheduler = RecordingScheduler()
        val clock = MutableClock(DataLoomInstant(2_000L))
        val orchestrator = orchestrator(scheduler, clock, delay = 500L, cumulativeLimit = 1_000L)

        val result = runSuspend {
            orchestrator.evaluateAndSchedule(retryRequest(attempt = 1))
        }

        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(1, scheduler.scheduleCalls)
        assertEquals(SchedulingDelay(500L), result.retryBudgetState?.cumulativeDelay)
        assertEquals(DataLoomInstant(2_000L), result.retryBudgetState?.windowStartedAt)
    }

    @Test
    fun `scheduler failure does not return advanced budget state`() {
        val scheduler = RecordingScheduler(fail = true)
        val orchestrator = orchestrator(
            scheduler = scheduler,
            clock = MutableClock(DataLoomInstant(2_000L)),
            delay = 500L,
            cumulativeLimit = 1_000L,
        )

        val result = runSuspend {
            orchestrator.evaluateAndSchedule(retryRequest(attempt = 1))
        }

        assertEquals(RetryOrchestrationStatus.SCHEDULER_FAILED, result.status)
        assertEquals(1, scheduler.scheduleCalls)
        assertNull(result.retryBudgetState)
    }

    @Test
    fun `missing scheduler does not return advanced budget state`() {
        val orchestrator = orchestrator(
            scheduler = null,
            clock = MutableClock(DataLoomInstant(2_000L)),
            delay = 500L,
            cumulativeLimit = 1_000L,
        )

        val result = runSuspend {
            orchestrator.evaluateAndSchedule(retryRequest(attempt = 1))
        }

        assertEquals(RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED, result.status)
        assertNull(result.retryBudgetState)
    }

    @Test
    fun `cumulative budget stops before scheduler invocation`() {
        val scheduler = RecordingScheduler()
        val orchestrator = orchestrator(
            scheduler = scheduler,
            clock = MutableClock(DataLoomInstant(2_000L)),
            delay = 200L,
            cumulativeLimit = 1_000L,
        )
        val current = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(1_500L),
            cumulativeDelay = SchedulingDelay(900L),
        )

        val result = runSuspend {
            orchestrator.evaluateAndSchedule(retryRequest(attempt = 2, budgetState = current))
        }

        assertEquals(RetryOrchestrationStatus.STOPPED, result.status)
        assertEquals(0, scheduler.scheduleCalls)
        val stop = assertIs<RetryDecision.Stop>(result.decisions.single())
        assertEquals(RetryStopReason.CUMULATIVE_DELAY_LIMIT_REACHED, stop.reason)
        assertNull(result.retryBudgetState)
    }

    private fun orchestrator(
        scheduler: SchedulerProvider?,
        clock: DataLoomClock,
        delay: Long,
        cumulativeLimit: Long,
    ): SynchronizationRetryOrchestrator = SynchronizationRetryOrchestrator(
        retryPolicy = StandardRetryPolicy(
            id = RetryPolicyId("budget-scheduler-policy"),
            strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(delay)),
            maximumAttempts = 5,
        ),
        schedulerProvider = scheduler,
        configuration = schedulingConfiguration,
        clock = clock,
        budgetConfiguration = RetryBudgetConfiguration(
            maximumCumulativeDelay = SchedulingDelay(cumulativeLimit),
        ),
    )

    private fun retryRequest(
        attempt: Int,
        budgetState: RetryBudgetState? = null,
    ): SynchronizationRetryRequest = SynchronizationRetryRequest(
        synchronizationRequest = synchronizationRequest,
        synchronizationResult = failure,
        retryOperation = RetryOperation("transport.push"),
        retryAttempt = RetryAttempt(attempt),
        scheduleId = ScheduleId("budget-scheduler-schedule"),
        retryBudgetState = budgetState,
    )

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
