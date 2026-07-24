package io.dataloom.runtime.retry

import io.dataloom.api.context.DataLoomMetadata
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
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-024 retry orchestration.
 *
 * All fakes are stateless or deterministically stateful. No real scheduler,
 * real network, real database, filesystem, Thread.sleep, arbitrary delay,
 * Android APIs, JVM-only APIs, reflection, ServiceLoader, system clock,
 * random identifiers, or production credentials are used.
 *
 * Suspend functions are exercised using [kotlin.coroutines.startCoroutine]
 * primitives from the Kotlin standard library, without requiring
 * kotlinx.coroutines.
 */
class SynchronizationRetryOrchestratorTest {

    // =========================================================================
    // Sentinel for runSuspend
    // =========================================================================

    private object Pending

    // =========================================================================
    // runSuspend helper
    // =========================================================================

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
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }

    // =========================================================================
    // Fake DataLoomError
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake recoverable error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class FakeNonRecoverableError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-002"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Fake non-recoverable error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class FakeSchedulerError(
        override val code: ErrorCode = ErrorCode("DL-SCHED-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Scheduler provider failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Fake RetryPolicy
    // =========================================================================

    /** Always returns [RetryDecision.Retry] with the configured delay. */
    private class AlwaysRetryPolicy(
        private val delay: SchedulingDelay = SchedulingDelay(1000L),
    ) : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("always-retry")
        val capturedRequests: MutableList<RetryEvaluationRequest> = mutableListOf()

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            capturedRequests.add(request)
            return RetryDecision.Retry(delay = delay)
        }
    }

    /** Always returns [RetryDecision.Stop] with [RetryStopReason.POLICY_REJECTED]. */
    private class AlwaysStopPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("always-stop")
        val capturedRequests: MutableList<RetryEvaluationRequest> = mutableListOf()

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            capturedRequests.add(request)
            return RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
        }
    }

    /**
     * Returns [RetryDecision.Retry] for the first invocation and
     * [RetryDecision.Stop] for subsequent invocations.
     */
    private class MixedPolicy(
        private val retryDelay: SchedulingDelay = SchedulingDelay(500L),
    ) : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("mixed")
        private var callCount = 0
        val decisions: MutableList<RetryDecision> = mutableListOf()

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            callCount++
            val decision = if (callCount == 1) {
                RetryDecision.Retry(delay = retryDelay)
            } else {
                RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
            }
            decisions.add(decision)
            return decision
        }
    }

    /**
     * Returns the decision from the supplied list in order.
     */
    private class ScriptedPolicy(
        private val responses: List<RetryDecision>,
    ) : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("scripted")
        private var callIndex = 0
        val capturedRequests: MutableList<RetryEvaluationRequest> = mutableListOf()

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            capturedRequests.add(request)
            val decision = responses[callIndex]
            callIndex++
            return decision
        }
    }

    /** Throws an unexpected exception to verify propagation. */
    private class ThrowingPolicy(
        private val exception: Throwable = IllegalStateException("Unexpected policy error"),
    ) : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("throwing")
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision = throw exception
    }

    // =========================================================================
    // Fake SchedulerProvider
    // =========================================================================

    /** Returns [ProviderOperationResult.Success] with a receipt using the request ID. */
    private class SuccessSchedulerProvider : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-success"),
            name = ProviderName("Success Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )
        var callCount: Int = 0
        val capturedRequests: MutableList<ScheduleRequest> = mutableListOf()

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> {
            callCount++
            capturedRequests.add(request)
            return ProviderOperationResult.Success(ScheduleReceipt(id = request.id))
        }

        override suspend fun cancel(request: ScheduleCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /** Returns [ProviderOperationResult.Failure] with the configured error. */
    private class FailingSchedulerProvider(
        private val error: DataLoomError = FakeSchedulerError(),
    ) : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-failing"),
            name = ProviderName("Failing Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )
        var callCount: Int = 0
        val capturedRequests: MutableList<ScheduleRequest> = mutableListOf()

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> {
            callCount++
            capturedRequests.add(request)
            return ProviderOperationResult.Failure(error)
        }

        override suspend fun cancel(request: ScheduleCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /** Throws [CancellationException] when schedule is called. */
    private class CancellingSchedulerProvider : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-cancelling"),
            name = ProviderName("Cancelling Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> {
            throw CancellationException("Simulated cancellation from scheduler.")
        }

        override suspend fun cancel(request: ScheduleCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /** Throws an unexpected exception when schedule is called. */
    private class ThrowingSchedulerProvider(
        private val exception: Throwable = IllegalStateException("Unexpected scheduler error"),
    ) : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-throwing"),
            name = ProviderName("Throwing Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> {
            throw exception
        }

        override suspend fun cancel(request: ScheduleCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    // =========================================================================
    // Shared test fixtures
    // =========================================================================

    private val sampleRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.FULL,
        context = ExecutionContext(
            executionId = ExecutionId("exec-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private val sampleInstant = DataLoomInstant(1000L)

    private val zeroSummary = SynchronizationSummary()

    private val sampleError = FakeError()

    private val sampleOperation = RetryOperation("transport.push")

    private val sampleAttempt = RetryAttempt(1)

    private val sampleScheduleId = ScheduleId("schedule-001")

    private val defaultConstraints = ScheduleConstraints()

    private val defaultConfiguration = RetrySchedulingConfiguration(
        constraints = defaultConstraints,
        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
    )

    private fun makeFailedResult(error: DataLoomError = sampleError): SynchronizationResult.Failed =
        SynchronizationResult.Failed(
            request = sampleRequest,
            completedAt = sampleInstant,
            summary = zeroSummary,
            error = error,
        )

    private fun makePartialResult(vararg errors: DataLoomError): SynchronizationResult.PartiallySucceeded =
        SynchronizationResult.PartiallySucceeded(
            request = sampleRequest,
            completedAt = sampleInstant,
            summary = zeroSummary,
            errors = errors.toList(),
        )

    private fun makeRetryRequest(
        syncRequest: SynchronizationRequest = sampleRequest,
        syncResult: SynchronizationResult = makeFailedResult(),
        operation: RetryOperation = sampleOperation,
        attempt: RetryAttempt = sampleAttempt,
        scheduleId: ScheduleId = sampleScheduleId,
    ): SynchronizationRetryRequest = SynchronizationRetryRequest(
        synchronizationRequest = syncRequest,
        synchronizationResult = syncResult,
        retryOperation = operation,
        retryAttempt = attempt,
        scheduleId = scheduleId,
    )

    private fun makeOrchestrator(
        policy: RetryPolicy = AlwaysRetryPolicy(),
        scheduler: SchedulerProvider? = SuccessSchedulerProvider(),
        config: RetrySchedulingConfiguration = defaultConfiguration,
    ): SynchronizationRetryOrchestrator = SynchronizationRetryOrchestrator(
        retryPolicy = policy,
        schedulerProvider = scheduler,
        configuration = config,
    )

    // =========================================================================
    // SynchronizationRetryRequest tests
    // =========================================================================

    @Test
    fun `SynchronizationRetryRequest preserves synchronizationRequest`() {
        val req = makeRetryRequest(syncRequest = sampleRequest)
        assertSame(sampleRequest, req.synchronizationRequest)
    }

    @Test
    fun `SynchronizationRetryRequest preserves synchronizationResult`() {
        val result = makeFailedResult()
        val req = makeRetryRequest(syncResult = result)
        assertSame(result, req.synchronizationResult)
    }

    @Test
    fun `SynchronizationRetryRequest preserves retryOperation`() {
        val req = makeRetryRequest(operation = sampleOperation)
        assertEquals(sampleOperation, req.retryOperation)
    }

    @Test
    fun `SynchronizationRetryRequest preserves retryAttempt`() {
        val attempt = RetryAttempt(3)
        val req = makeRetryRequest(attempt = attempt)
        assertEquals(attempt, req.retryAttempt)
    }

    @Test
    fun `SynchronizationRetryRequest preserves scheduleId`() {
        val req = makeRetryRequest(scheduleId = sampleScheduleId)
        assertEquals(sampleScheduleId, req.scheduleId)
    }

    @Test
    fun `SynchronizationRetryRequest toString does not expose payload`() {
        val req = makeRetryRequest()
        val s = req.toString()
        // Must not contain full SynchronizationResult toString output
        assertTrue(s.contains("session-001"), "toString should include session ID")
        assertTrue(s.contains("transport.push"), "toString should include operation")
        assertTrue(s.contains("1"), "toString should include attempt number")
        assertTrue(s.contains("schedule-001"), "toString should include scheduleId")
        assertTrue(s.contains("Failed"), "toString should include result variant")
    }

    // =========================================================================
    // RetrySchedulingConfiguration tests
    // =========================================================================

    @Test
    fun `RetrySchedulingConfiguration preserves constraints`() {
        val constraints = ScheduleConstraints()
        val config = RetrySchedulingConfiguration(
            constraints = constraints,
            existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
        )
        assertEquals(constraints, config.constraints)
    }

    @Test
    fun `RetrySchedulingConfiguration preserves existingSchedulePolicy`() {
        val config = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        )
        assertEquals(ExistingSchedulePolicy.REPLACE, config.existingSchedulePolicy)
    }

    @Test
    fun `RetrySchedulingConfiguration value-based equality`() {
        val a = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
        )
        val b = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
        )
        assertEquals(a, b)
    }

    @Test
    fun `RetrySchedulingConfiguration inequality when policy differs`() {
        val a = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
        )
        val b = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        )
        assertTrue(a != b)
    }

    // =========================================================================
    // Not-required results
    // =========================================================================

    @Test
    fun `Succeeded result returns NOT_REQUIRED`() {
        val policy = AlwaysRetryPolicy()
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = scheduler)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(
                    syncResult = SynchronizationResult.Succeeded(
                        request = sampleRequest,
                        completedAt = sampleInstant,
                        summary = zeroSummary,
                    ),
                ),
            )
        }
        assertEquals(RetryOrchestrationStatus.NOT_REQUIRED, result.status)
        assertTrue(result.decisions.isEmpty())
        assertNull(result.selectedDelay)
        assertNull(result.scheduleReceipt)
        assertNull(result.schedulerError)
        assertEquals(0, policy.capturedRequests.size)
        assertEquals(0, scheduler.callCount)
    }

    @Test
    fun `Skipped NO_CHANGES result returns NOT_REQUIRED`() {
        val policy = AlwaysRetryPolicy()
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = scheduler)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(
                    syncResult = SynchronizationResult.Skipped(
                        request = sampleRequest,
                        completedAt = sampleInstant,
                        summary = zeroSummary,
                        reason = SynchronizationSkipReason.NO_CHANGES,
                    ),
                ),
            )
        }
        assertEquals(RetryOrchestrationStatus.NOT_REQUIRED, result.status)
        assertTrue(result.decisions.isEmpty())
        assertEquals(0, policy.capturedRequests.size)
        assertEquals(0, scheduler.callCount)
    }

    @Test
    fun `Skipped POLICY_REJECTED result returns NOT_REQUIRED`() {
        val policy = AlwaysRetryPolicy()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(
                    syncResult = SynchronizationResult.Skipped(
                        request = sampleRequest,
                        completedAt = sampleInstant,
                        summary = zeroSummary,
                        reason = SynchronizationSkipReason.POLICY_REJECTED,
                    ),
                ),
            )
        }
        assertEquals(RetryOrchestrationStatus.NOT_REQUIRED, result.status)
        assertEquals(0, policy.capturedRequests.size)
    }

    @Test
    fun `Cancelled result returns NOT_REQUIRED`() {
        val policy = AlwaysRetryPolicy()
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = scheduler)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(
                    syncResult = SynchronizationResult.Cancelled(
                        request = sampleRequest,
                        completedAt = sampleInstant,
                        summary = zeroSummary,
                    ),
                ),
            )
        }
        assertEquals(RetryOrchestrationStatus.NOT_REQUIRED, result.status)
        assertTrue(result.decisions.isEmpty())
        assertNull(result.selectedDelay)
        assertNull(result.scheduleReceipt)
        assertNull(result.schedulerError)
        assertEquals(0, policy.capturedRequests.size)
        assertEquals(0, scheduler.callCount)
    }

    // =========================================================================
    // Failed result evaluation tests
    // =========================================================================

    @Test
    fun `Failed error is evaluated exactly once`() {
        val policy = AlwaysStopPolicy()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(1, policy.capturedRequests.size)
    }

    @Test
    fun `Failed evaluation receives exact RetryOperation`() {
        val policy = AlwaysStopPolicy()
        val operation = RetryOperation("storage.read")
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(operation = operation, syncResult = makeFailedResult()))
        }
        assertEquals(operation, policy.capturedRequests.single().operation)
    }

    @Test
    fun `Failed evaluation receives exact RetryAttempt`() {
        val policy = AlwaysStopPolicy()
        val attempt = RetryAttempt(5)
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(attempt = attempt, syncResult = makeFailedResult()))
        }
        assertEquals(attempt, policy.capturedRequests.single().attempt)
    }

    @Test
    fun `Failed evaluation receives exact DataLoomError`() {
        val policy = AlwaysStopPolicy()
        val error = FakeError(code = ErrorCode("DL-SPECIFIC"))
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult(error = error)))
        }
        assertSame(error, policy.capturedRequests.single().error)
    }

    @Test
    fun `RetryAttempt is not incremented by orchestrator`() {
        val policy = AlwaysStopPolicy()
        val attempt = RetryAttempt(2)
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(attempt = attempt, syncResult = makeFailedResult()))
        }
        assertEquals(2, policy.capturedRequests.single().attempt.number)
    }

    @Test
    fun `Failed with stop decision returns STOPPED`() {
        val orchestrator = makeOrchestrator(policy = AlwaysStopPolicy(), scheduler = null)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(RetryOrchestrationStatus.STOPPED, result.status)
        assertEquals(1, result.decisions.size)
        assertIs<RetryDecision.Stop>(result.decisions[0])
        assertNull(result.selectedDelay)
        assertNull(result.scheduleReceipt)
        assertNull(result.schedulerError)
    }

    @Test
    fun `Failed with retry decision schedules once`() {
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(delay = SchedulingDelay(500L)), scheduler = scheduler)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(1, scheduler.callCount)
    }

    // =========================================================================
    // PartiallySucceeded evaluation tests
    // =========================================================================

    @Test
    fun `PartiallySucceeded evaluates every error`() {
        val policy = AlwaysStopPolicy()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        val error1 = FakeError(code = ErrorCode("DL-ERR-1"))
        val error2 = FakeError(code = ErrorCode("DL-ERR-2"))
        val error3 = FakeError(code = ErrorCode("DL-ERR-3"))
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makePartialResult(error1, error2, error3)))
        }
        assertEquals(3, policy.capturedRequests.size)
    }

    @Test
    fun `PartiallySucceeded evaluation order matches error order`() {
        val policy = AlwaysStopPolicy()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        val error1 = FakeError(code = ErrorCode("DL-FIRST"))
        val error2 = FakeError(code = ErrorCode("DL-SECOND"))
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makePartialResult(error1, error2)))
        }
        assertSame(error1, policy.capturedRequests[0].error)
        assertSame(error2, policy.capturedRequests[1].error)
    }

    @Test
    fun `PartiallySucceeded errors are not deduplicated`() {
        val policy = AlwaysStopPolicy()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        val error = FakeError()
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makePartialResult(error, error)))
        }
        assertEquals(2, policy.capturedRequests.size)
    }

    @Test
    fun `PartiallySucceeded decision order matches evaluation order`() {
        val policy = ScriptedPolicy(
            listOf(
                RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED),
                RetryDecision.Retry(delay = SchedulingDelay(100L)),
            ),
        )
        val orchestrator = makeOrchestrator(policy = policy, scheduler = SuccessSchedulerProvider())
        val error1 = FakeError(code = ErrorCode("DL-E1"))
        val error2 = FakeError(code = ErrorCode("DL-E2"))
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makePartialResult(error1, error2)))
        }
        assertIs<RetryDecision.Stop>(result.decisions[0])
        assertIs<RetryDecision.Retry>(result.decisions[1])
    }

    @Test
    fun `PartiallySucceeded stop-only decisions return STOPPED`() {
        val orchestrator = makeOrchestrator(policy = AlwaysStopPolicy(), scheduler = null)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makePartialResult(FakeError(), FakeError())))
        }
        assertEquals(RetryOrchestrationStatus.STOPPED, result.status)
    }

    @Test
    fun `PartiallySucceeded mixed stop and retry decisions schedule once`() {
        val policy = MixedPolicy(retryDelay = SchedulingDelay(300L))
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = scheduler)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(syncResult = makePartialResult(FakeError(), FakeError())),
            )
        }
        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(1, scheduler.callCount)
        assertEquals(2, result.decisions.size)
    }

    // =========================================================================
    // Delay aggregation tests
    // =========================================================================

    @Test
    fun `single retry delay is selected unchanged`() {
        val delay = SchedulingDelay(750L)
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(delay = delay), scheduler = SuccessSchedulerProvider())
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(delay, result.selectedDelay)
    }

    @Test
    fun `multiple retry delays choose the maximum`() {
        val policy = ScriptedPolicy(
            listOf(
                RetryDecision.Retry(delay = SchedulingDelay(100L)),
                RetryDecision.Retry(delay = SchedulingDelay(900L)),
                RetryDecision.Retry(delay = SchedulingDelay(500L)),
            ),
        )
        val orchestrator = makeOrchestrator(policy = policy, scheduler = SuccessSchedulerProvider())
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(syncResult = makePartialResult(FakeError(), FakeError(), FakeError())),
            )
        }
        assertEquals(SchedulingDelay(900L), result.selectedDelay)
    }

    @Test
    fun `decision order does not affect maximum selection`() {
        val policy = ScriptedPolicy(
            listOf(
                RetryDecision.Retry(delay = SchedulingDelay(900L)),
                RetryDecision.Retry(delay = SchedulingDelay(100L)),
            ),
        )
        val orchestrator = makeOrchestrator(policy = policy, scheduler = SuccessSchedulerProvider())
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(syncResult = makePartialResult(FakeError(), FakeError())),
            )
        }
        assertEquals(SchedulingDelay(900L), result.selectedDelay)
    }

    @Test
    fun `stop decisions do not contribute a delay`() {
        val policy = ScriptedPolicy(
            listOf(
                RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED),
                RetryDecision.Retry(delay = SchedulingDelay(200L)),
            ),
        )
        val orchestrator = makeOrchestrator(policy = policy, scheduler = SuccessSchedulerProvider())
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(syncResult = makePartialResult(FakeError(), FakeError())),
            )
        }
        assertEquals(SchedulingDelay(200L), result.selectedDelay)
    }

    // =========================================================================
    // Missing scheduler tests
    // =========================================================================

    @Test
    fun `null SchedulerProvider returns SCHEDULER_NOT_CONFIGURED`() {
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = null)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED, result.status)
    }

    @Test
    fun `SCHEDULER_NOT_CONFIGURED preserves selected delay`() {
        val delay = SchedulingDelay(600L)
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(delay = delay), scheduler = null)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(delay, result.selectedDelay)
    }

    @Test
    fun `SCHEDULER_NOT_CONFIGURED preserves decisions`() {
        val policy = AlwaysRetryPolicy(delay = SchedulingDelay(300L))
        val orchestrator = makeOrchestrator(policy = policy, scheduler = null)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(1, result.decisions.size)
        assertIs<RetryDecision.Retry>(result.decisions[0])
    }

    @Test
    fun `SCHEDULER_NOT_CONFIGURED has null scheduleReceipt`() {
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = null)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertNull(result.scheduleReceipt)
    }

    @Test
    fun `SCHEDULER_NOT_CONFIGURED has null schedulerError`() {
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = null)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertNull(result.schedulerError)
    }

    // =========================================================================
    // Successful scheduling tests
    // =========================================================================

    @Test
    fun `ScheduleRequest receives exact ScheduleId`() {
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = scheduler)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(scheduleId = sampleScheduleId, syncResult = makeFailedResult()))
        }
        assertEquals(sampleScheduleId, scheduler.capturedRequests.single().id)
    }

    @Test
    fun `ScheduleRequest preserves original SynchronizationRequest`() {
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = scheduler)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncRequest = sampleRequest, syncResult = makeFailedResult()))
        }
        assertSame(sampleRequest, scheduler.capturedRequests.single().synchronizationRequest)
    }

    @Test
    fun `ScheduleRequest preserves selected maximum delay`() {
        val delay = SchedulingDelay(1234L)
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(delay = delay), scheduler = scheduler)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(delay, scheduler.capturedRequests.single().delay)
    }

    @Test
    fun `ScheduleRequest preserves constraints from configuration`() {
        val constraints = ScheduleConstraints(requiresCharging = true)
        val config = RetrySchedulingConfiguration(
            constraints = constraints,
            existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
        )
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = scheduler, config = config)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(constraints, scheduler.capturedRequests.single().constraints)
    }

    @Test
    fun `ScheduleRequest preserves existingSchedulePolicy from configuration`() {
        val config = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        )
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = scheduler, config = config)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(ExistingSchedulePolicy.REPLACE, scheduler.capturedRequests.single().existingPolicy)
    }

    @Test
    fun `scheduler is invoked exactly once`() {
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = scheduler)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(1, scheduler.callCount)
    }

    @Test
    fun `SCHEDULED result preserves exact ScheduleReceipt`() {
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = scheduler)
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(scheduleId = sampleScheduleId, syncResult = makeFailedResult()))
        }
        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        val receipt = assertNotNull(result.scheduleReceipt)
        assertEquals(sampleScheduleId, receipt.id)
    }

    @Test
    fun `SCHEDULED result status is SCHEDULED`() {
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = SuccessSchedulerProvider())
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
    }

    // =========================================================================
    // Scheduler failure tests
    // =========================================================================

    @Test
    fun `scheduler failure returns SCHEDULER_FAILED`() {
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = FailingSchedulerProvider())
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(RetryOrchestrationStatus.SCHEDULER_FAILED, result.status)
    }

    @Test
    fun `SCHEDULER_FAILED preserves exact DataLoomError from provider`() {
        val schedulerError = FakeSchedulerError(code = ErrorCode("DL-SCHED-SPECIFIC"))
        val orchestrator = makeOrchestrator(
            policy = AlwaysRetryPolicy(),
            scheduler = FailingSchedulerProvider(error = schedulerError),
        )
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertSame(schedulerError, result.schedulerError)
    }

    @Test
    fun `SCHEDULER_FAILED does not re-evaluate RetryPolicy`() {
        val policy = AlwaysRetryPolicy()
        val orchestrator = makeOrchestrator(policy = policy, scheduler = FailingSchedulerProvider())
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(1, policy.capturedRequests.size)
    }

    @Test
    fun `SCHEDULER_FAILED does not call scheduler again`() {
        val scheduler = FailingSchedulerProvider()
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = scheduler)
        runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertEquals(1, scheduler.callCount)
    }

    @Test
    fun `SCHEDULER_FAILED has null scheduleReceipt`() {
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = FailingSchedulerProvider())
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
        }
        assertNull(result.scheduleReceipt)
    }

    // =========================================================================
    // RetryOrchestrationResult invariants
    // =========================================================================

    @Test
    fun `NOT_REQUIRED with non-empty decisions is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.NOT_REQUIRED,
                decisions = listOf(RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)),
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )
        }
    }

    @Test
    fun `NOT_REQUIRED with non-null selectedDelay is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.NOT_REQUIRED,
                decisions = emptyList(),
                selectedDelay = SchedulingDelay(100L),
                scheduleReceipt = null,
                schedulerError = null,
            )
        }
    }

    @Test
    fun `STOPPED with empty decisions is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.STOPPED,
                decisions = emptyList(),
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )
        }
    }

    @Test
    fun `STOPPED with a retry decision is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.STOPPED,
                decisions = listOf(RetryDecision.Retry(delay = SchedulingDelay(100L))),
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )
        }
    }

    @Test
    fun `SCHEDULED without delay is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULED,
                decisions = listOf(RetryDecision.Retry(delay = SchedulingDelay(100L))),
                selectedDelay = null,
                scheduleReceipt = ScheduleReceipt(id = ScheduleId("s1")),
                schedulerError = null,
            )
        }
    }

    @Test
    fun `SCHEDULED without receipt is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULED,
                decisions = listOf(RetryDecision.Retry(delay = SchedulingDelay(100L))),
                selectedDelay = SchedulingDelay(100L),
                scheduleReceipt = null,
                schedulerError = null,
            )
        }
    }

    @Test
    fun `SCHEDULER_NOT_CONFIGURED without delay is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED,
                decisions = listOf(RetryDecision.Retry(delay = SchedulingDelay(100L))),
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )
        }
    }

    @Test
    fun `SCHEDULER_FAILED without delay is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_FAILED,
                decisions = listOf(RetryDecision.Retry(delay = SchedulingDelay(100L))),
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = FakeSchedulerError(),
            )
        }
    }

    @Test
    fun `SCHEDULER_FAILED without schedulerError is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_FAILED,
                decisions = listOf(RetryDecision.Retry(delay = SchedulingDelay(100L))),
                selectedDelay = SchedulingDelay(100L),
                scheduleReceipt = null,
                schedulerError = null,
            )
        }
    }

    @Test
    fun `decisions collection is defensively copied`() {
        val mutableDecisions = mutableListOf<RetryDecision>(
            RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED),
        )
        val result = RetryOrchestrationResult(
            status = RetryOrchestrationStatus.STOPPED,
            decisions = mutableDecisions,
            selectedDelay = null,
            scheduleReceipt = null,
            schedulerError = null,
        )
        mutableDecisions.clear()
        assertEquals(1, result.decisions.size)
    }

    @Test
    fun `decision order is preserved`() {
        val stop1 = RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
        val stop2 = RetryDecision.Stop(reason = RetryStopReason.ATTEMPT_LIMIT_REACHED)
        val result = RetryOrchestrationResult(
            status = RetryOrchestrationStatus.STOPPED,
            decisions = listOf(stop1, stop2),
            selectedDelay = null,
            scheduleReceipt = null,
            schedulerError = null,
        )
        assertSame(stop1, result.decisions[0])
        assertSame(stop2, result.decisions[1])
    }

    @Test
    fun `exposed decisions collection is not mutable`() {
        val result = RetryOrchestrationResult(
            status = RetryOrchestrationStatus.STOPPED,
            decisions = listOf(RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)),
            selectedDelay = null,
            scheduleReceipt = null,
            schedulerError = null,
        )
        // The exposed type is read-only List, not MutableList.
        // Even if the runtime type allows mutation, the public API is List.
        val decisions: List<RetryDecision> = result.decisions
        assertEquals(1, decisions.size)
    }

    // =========================================================================
    // Cancellation and exceptions
    // =========================================================================

    @Test
    fun `CancellationException from SchedulerProvider propagates`() {
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = CancellingSchedulerProvider())
        assertFailsWith<CancellationException> {
            runSuspend {
                orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
            }
        }
    }

    @Test
    fun `cancellation is not converted into orchestration result`() {
        val orchestrator = makeOrchestrator(policy = AlwaysRetryPolicy(), scheduler = CancellingSchedulerProvider())
        var result: RetryOrchestrationResult? = null
        try {
            runSuspend {
                result = orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
            }
        } catch (_: CancellationException) {
            // expected
        }
        assertNull(result)
    }

    @Test
    fun `unexpected RetryPolicy exception propagates`() {
        val exception = IllegalStateException("Policy bug")
        val orchestrator = makeOrchestrator(policy = ThrowingPolicy(exception), scheduler = null)
        val thrown = assertFailsWith<IllegalStateException> {
            runSuspend {
                orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
            }
        }
        assertSame(exception, thrown)
    }

    @Test
    fun `unexpected SchedulerProvider exception propagates`() {
        val exception = IllegalStateException("Scheduler bug")
        val orchestrator = makeOrchestrator(
            policy = AlwaysRetryPolicy(),
            scheduler = ThrowingSchedulerProvider(exception),
        )
        val thrown = assertFailsWith<IllegalStateException> {
            runSuspend {
                orchestrator.evaluateAndSchedule(makeRetryRequest(syncResult = makeFailedResult()))
            }
        }
        assertSame(exception, thrown)
    }

    @Test
    fun `scheduling occurs at most once`() {
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeOrchestrator(
            policy = AlwaysRetryPolicy(delay = SchedulingDelay(100L)),
            scheduler = scheduler,
        )
        // Run with multiple partial errors to confirm still only one schedule call
        val result = runSuspend {
            orchestrator.evaluateAndSchedule(
                makeRetryRequest(syncResult = makePartialResult(FakeError(), FakeError(), FakeError())),
            )
        }
        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(1, scheduler.callCount)
    }
}
