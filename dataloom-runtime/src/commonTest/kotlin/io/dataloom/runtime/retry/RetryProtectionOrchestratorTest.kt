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
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

class RetryProtectionOrchestratorTest {

    private object Pending

    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) {
                        rawResult = result.getOrNull()
                    } else {
                        thrown = result.exceptionOrNull()
                    }
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending)
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }

    private data class FakeError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability,
        override val message: String = "Sanitized retry orchestration failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class CountingRetryPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("orchestrator-counting-policy")
        var calls: Int = 0

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            calls++
            return RetryDecision.Retry(SchedulingDelay(250L))
        }
    }

    private class RecordingScheduler : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("retry-protection-scheduler"),
            name = ProviderName("Retry protection scheduler"),
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
            scheduleCalls++
            return ProviderOperationResult.Success(ScheduleReceipt(id = request.id))
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private val synchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("retry-orchestrator-workflow"),
        sessionId = SynchronizationSessionId("retry-orchestrator-session"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("retry-orchestrator-execution"),
            correlationId = CorrelationId("retry-orchestrator-correlation"),
        ),
    )

    private fun orchestrator(
        policy: RetryPolicy,
        scheduler: SchedulerProvider,
    ): SynchronizationRetryOrchestrator = SynchronizationRetryOrchestrator(
        retryPolicy = policy,
        schedulerProvider = scheduler,
        configuration = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        ),
    )

    @Test
    fun `protected partial batch does not invoke policy or scheduler`() {
        val transient = FakeError(
            code = ErrorCode("DL-TRANSIENT"),
            category = ErrorCategory.NETWORK,
            recoverability = Recoverability.RECOVERABLE,
        )
        val protected = FakeError(
            code = ErrorCode("DL-VALIDATION"),
            category = ErrorCategory.VALIDATION,
            recoverability = Recoverability.RECOVERABLE,
        )
        val policy = CountingRetryPolicy()
        val scheduler = RecordingScheduler()

        val result = runSuspend {
            orchestrator(policy, scheduler).evaluateAndSchedule(
                SynchronizationRetryRequest(
                    synchronizationRequest = synchronizationRequest,
                    synchronizationResult = SynchronizationResult.PartiallySucceeded(
                        request = synchronizationRequest,
                        completedAt = DataLoomInstant(100L),
                        summary = SynchronizationSummary(),
                        errors = listOf(transient, protected),
                    ),
                    retryOperation = RetryOperation("sync.execution"),
                    retryAttempt = RetryAttempt(1),
                    scheduleId = ScheduleId("retry-protected"),
                ),
            )
        }

        assertEquals(RetryOrchestrationStatus.STOPPED, result.status)
        assertEquals(0, policy.calls)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `recoverable batch still invokes policy and scheduler once`() {
        val recoverable = FakeError(
            code = ErrorCode("DL-NETWORK"),
            category = ErrorCategory.NETWORK,
            recoverability = Recoverability.RECOVERABLE,
        )
        val policy = CountingRetryPolicy()
        val scheduler = RecordingScheduler()

        val result = runSuspend {
            orchestrator(policy, scheduler).evaluateAndSchedule(
                SynchronizationRetryRequest(
                    synchronizationRequest = synchronizationRequest,
                    synchronizationResult = SynchronizationResult.Failed(
                        request = synchronizationRequest,
                        completedAt = DataLoomInstant(100L),
                        summary = SynchronizationSummary(),
                        error = recoverable,
                    ),
                    retryOperation = RetryOperation("sync.execution"),
                    retryAttempt = RetryAttempt(1),
                    scheduleId = ScheduleId("retry-recoverable"),
                ),
            )
        }

        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(1, policy.calls)
        assertEquals(1, scheduler.scheduleCalls)
    }
}
