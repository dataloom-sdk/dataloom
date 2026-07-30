package io.dataloom.runtime.retry

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class SynchronizationRetryOrchestratorSchedulerTimeoutTest {

    @Test
    fun `zero timeout prevents scheduler invocation and returns bounded failure`() = runTest {
        val scheduler = RecordingSchedulerProvider(delayMilliseconds = 1_000L)
        val result = timeoutOrchestrator(
            scheduler = scheduler,
            timeout = SchedulingDelay.ZERO,
        ).evaluateAndSchedule(retryRequest)

        assertEquals(RetryOrchestrationStatus.SCHEDULER_FAILED, result.status)
        assertEquals(0, scheduler.scheduleCallCount)
        assertEquals("SCHEDULER_PROVIDER_TIMEOUT", result.schedulerError?.code?.value)
        assertEquals(retryDelay, result.selectedDelay)
        assertNull(result.scheduleReceipt)
        assertNull(result.retryBudgetState)
    }

    @Test
    fun `scheduler success inside timeout is preserved exactly`() = runTest {
        val receipt = ScheduleReceipt(scheduleId)
        val scheduler = RecordingSchedulerProvider(
            delayMilliseconds = 100L,
            scheduleResult = ProviderOperationResult.Success(receipt),
        )

        val result = timeoutOrchestrator(
            scheduler = scheduler,
            timeout = SchedulingDelay(500L),
        ).evaluateAndSchedule(retryRequest)

        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(1, scheduler.scheduleCallCount)
        assertSame(receipt, result.scheduleReceipt)
        assertNull(result.schedulerError)
        assertEquals(retryDelay, scheduler.lastScheduleRequest?.delay)
        assertSame(synchronizationRequest, scheduler.lastScheduleRequest?.synchronizationRequest)
    }

    @Test
    fun `timeout cancels cooperative scheduler and does not expose advanced budget state`() = runTest {
        val scheduler = RecordingSchedulerProvider(delayMilliseconds = 1_000L)
        val orchestrator = SynchronizationRetryOrchestrator.withSchedulerProviderTimeout(
            retryPolicy = retryPolicy,
            schedulerProvider = scheduler,
            configuration = schedulingConfiguration,
            clock = fixedClock,
            schedulerProviderTimeout = SchedulingDelay(100L),
            budgetConfiguration = RetryBudgetConfiguration(
                maximumCumulativeDelay = SchedulingDelay(10_000L),
            ),
        )

        val result = orchestrator.evaluateAndSchedule(retryRequest)

        assertEquals(RetryOrchestrationStatus.SCHEDULER_FAILED, result.status)
        assertEquals("SCHEDULER_PROVIDER_TIMEOUT", result.schedulerError?.code?.value)
        assertTrue(scheduler.scheduleFinallyExecuted)
        assertNull(result.retryBudgetState)
    }

    @Test
    fun `canonical scheduler failure is preserved exactly`() = runTest {
        val expected = FakeError(code = ErrorCode("SCHEDULER_EXPECTED_FAILURE"))
        val scheduler = RecordingSchedulerProvider(
            scheduleResult = ProviderOperationResult.Failure(expected),
        )

        val result = timeoutOrchestrator(
            scheduler = scheduler,
            timeout = SchedulingDelay(500L),
        ).evaluateAndSchedule(retryRequest)

        assertEquals(RetryOrchestrationStatus.SCHEDULER_FAILED, result.status)
        assertSame(expected, result.schedulerError)
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `caller cancellation propagates without a structured retry status`() = runTest {
        val scheduler = RecordingSchedulerProvider(delayMilliseconds = 10_000L)
        val execution = backgroundScope.async {
            timeoutOrchestrator(
                scheduler = scheduler,
                timeout = SchedulingDelay(20_000L),
            ).evaluateAndSchedule(retryRequest)
        }
        scheduler.scheduleStarted.await()

        execution.cancel(CancellationException("caller cancelled"))
        val failure = captureFailure { execution.await() }

        assertIs<CancellationException>(failure)
        assertEquals("caller cancelled", failure.message)
        assertEquals(1, scheduler.scheduleCallCount)
        assertTrue(scheduler.scheduleFinallyExecuted)
    }

    @Test
    fun `missing scheduler remains scheduler not configured without clock access`() = runTest {
        val orchestrator = SynchronizationRetryOrchestrator.withSchedulerProviderTimeout(
            retryPolicy = retryPolicy,
            schedulerProvider = null,
            configuration = schedulingConfiguration,
            clock = ThrowingClock,
            schedulerProviderTimeout = SchedulingDelay.ZERO,
        )

        val result = orchestrator.evaluateAndSchedule(retryRequest)

        assertEquals(RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED, result.status)
        assertNull(result.schedulerError)
        assertNull(result.retryBudgetState)
    }

    @Test
    fun `existing constructor preserves historical direct scheduler path`() = runTest {
        val scheduler = RecordingSchedulerProvider(delayMilliseconds = 1_000L)
        val orchestrator = SynchronizationRetryOrchestrator(
            retryPolicy = retryPolicy,
            schedulerProvider = scheduler,
            configuration = schedulingConfiguration,
        )

        val result = orchestrator.evaluateAndSchedule(retryRequest)

        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(1, scheduler.scheduleCallCount)
    }

    private fun timeoutOrchestrator(
        scheduler: SchedulerProvider?,
        timeout: SchedulingDelay,
    ): SynchronizationRetryOrchestrator =
        SynchronizationRetryOrchestrator.withSchedulerProviderTimeout(
            retryPolicy = retryPolicy,
            schedulerProvider = scheduler,
            configuration = schedulingConfiguration,
            clock = fixedClock,
            schedulerProviderTimeout = timeout,
        )

    private class RecordingSchedulerProvider(
        private val delayMilliseconds: Long = 0L,
        private val scheduleResult: ProviderOperationResult<ScheduleReceipt>? = null,
    ) : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("retry-timeout-scheduler"),
            name = ProviderName("Retry Timeout Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )

        var scheduleCallCount: Int = 0
            private set
        var lastScheduleRequest: ScheduleRequest? = null
            private set
        var scheduleFinallyExecuted: Boolean = false
            private set
        val scheduleStarted: CompletableDeferred<Unit> = CompletableDeferred()

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
            scheduleCallCount++
            lastScheduleRequest = request
            scheduleStarted.complete(Unit)
            return try {
                if (delayMilliseconds > 0L) delay(delayMilliseconds)
                scheduleResult ?: ProviderOperationResult.Success(ScheduleReceipt(request.id))
            } finally {
                scheduleFinallyExecuted = true
            }
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("RETRY_TIMEOUT_TEST_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.SCHEDULER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Retry timeout test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private object ThrowingClock : DataLoomClock {
        override fun now(): DataLoomInstant = error("Clock must not be read without a scheduler.")
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected block to fail.")
    } catch (failure: Throwable) {
        failure
    }

    private companion object {
        val completedAt = DataLoomInstant(1_000L)
        val fixedClock: DataLoomClock = FixedClock(completedAt)
        val scheduleId = ScheduleId("retry-schedule-1")
        val retryDelay = SchedulingDelay(250L)
        val synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        )
        val failure = FakeError()
        val synchronizationResult = SynchronizationResult.Failed(
            request = synchronizationRequest,
            completedAt = completedAt,
            summary = SynchronizationSummary(),
            error = failure,
        )
        val retryRequest = SynchronizationRetryRequest(
            synchronizationRequest = synchronizationRequest,
            synchronizationResult = synchronizationResult,
            retryOperation = RetryOperation("transport.push"),
            retryAttempt = RetryAttempt(1),
            scheduleId = scheduleId,
        )
        val schedulingConfiguration = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        )
        val retryPolicy: RetryPolicy = object : RetryPolicy {
            override val id: RetryPolicyId = RetryPolicyId("retry-timeout-policy")

            override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                RetryDecision.Retry(retryDelay)
        }
    }
}
