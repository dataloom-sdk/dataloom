package io.dataloom.runtime.retry

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.RetryDelayHint
import io.dataloom.api.error.RetryDelayHintCarrier
import io.dataloom.api.error.RetryDelayHintSource
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
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
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
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

class RetryHintSchedulerIntegrationTest {

    private data class HintError(
        override val retryDelayHint: RetryDelayHint,
        override val code: ErrorCode = ErrorCode("DL-HINT-SCHEDULER"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized retry hint scheduler failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError, RetryDelayHintCarrier

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class RecordingScheduler : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("hint-scheduler"),
            name = ProviderName("Hint Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )
        val requests: MutableList<ScheduleRequest> = mutableListOf()

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
            requests += request
            return ProviderOperationResult.Success(ScheduleReceipt(request.id))
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private val synchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("hint-scheduler-workflow"),
        sessionId = SynchronizationSessionId("hint-scheduler-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("hint-scheduler-execution"),
            correlationId = CorrelationId("hint-scheduler-correlation"),
        ),
    )
    private val failure = SynchronizationResult.Failed(
        request = synchronizationRequest,
        completedAt = DataLoomInstant(1_000L),
        summary = SynchronizationSummary(),
        error = HintError(
            RetryDelayHint(
                delayMilliseconds = 10_000L,
                source = RetryDelayHintSource.SERVER,
            ),
        ),
    )
    private val schedulingConfiguration = RetrySchedulingConfiguration(
        constraints = ScheduleConstraints(),
        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
    )

    @Test
    fun `hint-only orchestrator schedules bounded minimum`() {
        val scheduler = RecordingScheduler()
        val orchestrator = SynchronizationRetryOrchestrator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("hint-scheduler-policy"),
                strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(1_000L)),
                maximumAttempts = 3,
            ),
            schedulerProvider = scheduler,
            configuration = schedulingConfiguration,
            hintConfiguration = RetryHintConfiguration(
                maximumHintDelay = SchedulingDelay(4_000L),
            ),
        )

        val result = runSuspend {
            orchestrator.evaluateAndSchedule(retryRequest())
        }

        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(SchedulingDelay(4_000L), result.selectedDelay)
        assertEquals(SchedulingDelay(4_000L), scheduler.requests.single().delay)
    }

    @Test
    fun `combined orchestrator budgets final hint-adjusted delay`() {
        val scheduler = RecordingScheduler()
        val orchestrator = SynchronizationRetryOrchestrator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("hint-budget-scheduler-policy"),
                strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(1_000L)),
                maximumAttempts = 3,
            ),
            schedulerProvider = scheduler,
            configuration = schedulingConfiguration,
            clock = FixedClock(DataLoomInstant(2_000L)),
            budgetConfiguration = RetryBudgetConfiguration(
                maximumCumulativeDelay = SchedulingDelay(4_000L),
            ),
            hintConfiguration = RetryHintConfiguration(
                maximumHintDelay = SchedulingDelay(4_000L),
            ),
        )

        val result = runSuspend {
            orchestrator.evaluateAndSchedule(retryRequest())
        }

        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(SchedulingDelay(4_000L), result.retryBudgetState?.cumulativeDelay)
        assertEquals(SchedulingDelay(4_000L), scheduler.requests.single().delay)
    }

    private fun retryRequest(): SynchronizationRetryRequest = SynchronizationRetryRequest(
        synchronizationRequest = synchronizationRequest,
        synchronizationResult = failure,
        retryOperation = RetryOperation("transport.push"),
        retryAttempt = RetryAttempt(1),
        scheduleId = ScheduleId("hint-scheduler-schedule"),
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
