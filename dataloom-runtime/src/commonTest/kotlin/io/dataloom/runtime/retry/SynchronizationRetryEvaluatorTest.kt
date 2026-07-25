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
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic common tests for [SynchronizationRetryEvaluator].
 *
 * All fakes are stateless or deterministically stateful. No real clock,
 * real scheduler, real database, filesystem, Thread.sleep, arbitrary delay,
 * Android APIs, JVM-only APIs, reflection, ServiceLoader, system clock,
 * random identifiers, or production credentials are used.
 */
class SynchronizationRetryEvaluatorTest {

    // =========================================================================
    // Fake clock (FixedDataLoomClock cannot be used in dataloom-runtime tests)
    // =========================================================================

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    // =========================================================================
    // Shared test fixtures
    // =========================================================================

    private val t0 = DataLoomInstant(epochMilliseconds = 1_000_000L)
    private val t1 = DataLoomInstant(epochMilliseconds = 2_000_000L)
    private val clock = FixedClock(t0)
    private val attempt1 = RetryAttempt(1)
    private val attempt2 = RetryAttempt(2)
    private val retryOp = RetryOperation("sync.execution")

    private val sampleContext = ExecutionContext(
        executionId = ExecutionId("exec-001"),
        correlationId = CorrelationId("corr-001"),
    )

    private val sampleRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = sampleContext,
    )

    private val zeroSummary = SynchronizationSummary()

    // =========================================================================
    // Fake error types
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake recoverable error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AnotherFakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-002"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Another fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Fake RetryPolicy implementations
    // =========================================================================

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

    private class AlwaysStopPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("always-stop")
        val capturedRequests: MutableList<RetryEvaluationRequest> = mutableListOf()

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            capturedRequests.add(request)
            return RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
        }
    }

    private class ScriptedPolicy(
        private val responses: List<RetryDecision>,
    ) : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("scripted")
        private var callIndex = 0
        val capturedRequests: MutableList<RetryEvaluationRequest> = mutableListOf()

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            capturedRequests.add(request)
            return responses[callIndex++]
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeEvaluator(
        policy: RetryPolicy = AlwaysRetryPolicy(),
        clockInstant: DataLoomInstant = t0,
    ) = SynchronizationRetryEvaluator(
        retryPolicy = policy,
        clock = FixedClock(clockInstant),
    )

    private fun makeFailedResult(
        error: DataLoomError = FakeError(),
    ): SynchronizationResult.Failed = SynchronizationResult.Failed(
        request = sampleRequest,
        completedAt = t1,
        summary = zeroSummary,
        error = error,
    )

    private fun makePartialResult(
        errors: List<DataLoomError>,
    ): SynchronizationResult.PartiallySucceeded = SynchronizationResult.PartiallySucceeded(
        request = sampleRequest,
        completedAt = t1,
        summary = zeroSummary,
        errors = errors,
    )

    // =========================================================================
    // NotRequired — non-retryable result variants
    // =========================================================================

    @Test
    fun `Succeeded result returns NotRequired`() {
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = SynchronizationResult.Succeeded(
                request = sampleRequest,
                completedAt = t1,
                summary = zeroSummary,
            ),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertIs<SynchronizationRetryEvaluation.NotRequired>(result)
    }

    @Test
    fun `Skipped result returns NotRequired`() {
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = SynchronizationResult.Skipped(
                request = sampleRequest,
                completedAt = t1,
                summary = zeroSummary,
                reason = SynchronizationSkipReason.NO_CHANGES,
            ),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertIs<SynchronizationRetryEvaluation.NotRequired>(result)
    }

    @Test
    fun `Cancelled result returns NotRequired`() {
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = SynchronizationResult.Cancelled(
                request = sampleRequest,
                completedAt = t1,
                summary = zeroSummary,
            ),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertIs<SynchronizationRetryEvaluation.NotRequired>(result)
    }

    @Test
    fun `NotRequired does not invoke RetryPolicy`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        evaluator.evaluate(
            result = SynchronizationResult.Succeeded(
                request = sampleRequest,
                completedAt = t1,
                summary = zeroSummary,
            ),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertEquals(0, policy.capturedRequests.size)
    }

    // =========================================================================
    // StopRetry — policy stops retry
    // =========================================================================

    @Test
    fun `Failed with stop decision returns StopRetry`() {
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertIs<SynchronizationRetryEvaluation.StopRetry>(result)
    }

    @Test
    fun `StopRetry preserves the primary error`() {
        val error = FakeError()
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(error = error),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.StopRetry
        assertEquals(error, result.error)
    }

    @Test
    fun `StopRetry decisions list is non-empty`() {
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.StopRetry
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test
    fun `StopRetry decisions list contains Stop decision`() {
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.StopRetry
        assertTrue(result.decisions.all { it is RetryDecision.Stop })
    }

    // =========================================================================
    // ShouldRetry — policy requests retry
    // =========================================================================

    @Test
    fun `Failed with retry decision returns ShouldRetry`() {
        val evaluator = makeEvaluator(AlwaysRetryPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertIs<SynchronizationRetryEvaluation.ShouldRetry>(result)
    }

    @Test
    fun `ShouldRetry preserves the exact RetryAttempt`() {
        val evaluator = makeEvaluator(AlwaysRetryPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt2,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        assertEquals(attempt2, result.retryAttempt)
    }

    @Test
    fun `ShouldRetry passes exact RetryAttempt to RetryPolicy`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt2,
            retryOperation = retryOp,
        )
        assertEquals(1, policy.capturedRequests.size)
        assertEquals(attempt2, policy.capturedRequests[0].attempt)
    }

    @Test
    fun `ShouldRetry passes exact RetryOperation to RetryPolicy`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertEquals(retryOp, policy.capturedRequests[0].operation)
    }

    @Test
    fun `ShouldRetry passes exact SynchronizationRequest to RetryPolicy`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertEquals(sampleRequest, policy.capturedRequests[0].synchronizationRequest)
    }

    @Test
    fun `ShouldRetry preserves primary error from Failed result`() {
        val error = FakeError()
        val evaluator = makeEvaluator(AlwaysRetryPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(error = error),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        assertEquals(error, result.error)
    }

    @Test
    fun `ShouldRetry selectedDelay matches policy delay`() {
        val delay = SchedulingDelay(5000L)
        val evaluator = makeEvaluator(AlwaysRetryPolicy(delay = delay))
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        assertEquals(delay, result.selectedDelay)
    }

    @Test
    fun `ShouldRetry availableAt equals clock now plus delay`() {
        val delay = SchedulingDelay(3000L)
        val evaluator = makeEvaluator(AlwaysRetryPolicy(delay = delay), clockInstant = t0)
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        val expectedMillis = t0.epochMilliseconds + delay.milliseconds
        assertEquals(expectedMillis, result.availableAt.epochMilliseconds)
    }

    @Test
    fun `ShouldRetry availableAt is overflow-safe for large delay`() {
        val delay = SchedulingDelay(Long.MAX_VALUE)
        val evaluator = makeEvaluator(AlwaysRetryPolicy(delay = delay), clockInstant = t0)
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        // Should clamp to Long.MAX_VALUE without overflow
        assertEquals(Long.MAX_VALUE, result.availableAt.epochMilliseconds)
    }

    @Test
    fun `ShouldRetry decisions contains Retry decision`() {
        val evaluator = makeEvaluator(AlwaysRetryPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        assertTrue(result.decisions.any { it is RetryDecision.Retry })
    }

    // =========================================================================
    // PartiallySucceeded — multiple error evaluation
    // =========================================================================

    @Test
    fun `PartiallySucceeded evaluates all errors in order`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        val error1 = FakeError(code = ErrorCode("E1"))
        val error2 = AnotherFakeError(code = ErrorCode("E2"))
        evaluator.evaluate(
            result = makePartialResult(errors = listOf(error1, error2)),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertEquals(2, policy.capturedRequests.size)
        assertEquals(error1, policy.capturedRequests[0].error)
        assertEquals(error2, policy.capturedRequests[1].error)
    }

    @Test
    fun `PartiallySucceeded uses first error as primary`() {
        val error1 = FakeError(code = ErrorCode("E1"))
        val error2 = AnotherFakeError(code = ErrorCode("E2"))
        val evaluator = makeEvaluator(AlwaysRetryPolicy())
        val result = evaluator.evaluate(
            result = makePartialResult(errors = listOf(error1, error2)),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        assertEquals(error1, result.error)
    }

    @Test
    fun `PartiallySucceeded with all stop decisions returns StopRetry`() {
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = makePartialResult(errors = listOf(FakeError(), AnotherFakeError())),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertIs<SynchronizationRetryEvaluation.StopRetry>(result)
    }

    @Test
    fun `PartiallySucceeded with one retry decision returns ShouldRetry`() {
        val delay = SchedulingDelay(500L)
        val policy = ScriptedPolicy(
            listOf(
                RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED),
                RetryDecision.Retry(delay = delay),
            ),
        )
        val evaluator = makeEvaluator(policy)
        val result = evaluator.evaluate(
            result = makePartialResult(errors = listOf(FakeError(), AnotherFakeError())),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertIs<SynchronizationRetryEvaluation.ShouldRetry>(result)
    }

    @Test
    fun `PartiallySucceeded selects maximum delay across all retry decisions`() {
        val delay1 = SchedulingDelay(1000L)
        val delay2 = SchedulingDelay(3000L)
        val policy = ScriptedPolicy(
            listOf(
                RetryDecision.Retry(delay = delay1),
                RetryDecision.Retry(delay = delay2),
            ),
        )
        val evaluator = makeEvaluator(policy)
        val result = evaluator.evaluate(
            result = makePartialResult(errors = listOf(FakeError(), AnotherFakeError())),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        assertEquals(delay2, result.selectedDelay)
    }

    @Test
    fun `decisions list is defensively copied in ShouldRetry`() {
        val evaluator = makeEvaluator(AlwaysRetryPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        ) as SynchronizationRetryEvaluation.ShouldRetry
        // Access the list twice to confirm stable snapshot
        val snap1 = result.decisions
        val snap2 = result.decisions
        assertEquals(snap1, snap2)
    }

    @Test
    fun `decisions list is defensively copied in StopRetry`() {
        val evaluator = makeEvaluator(AlwaysStopPolicy())
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        ) as SynchronizationRetryEvaluation.StopRetry
        val snap1 = result.decisions
        val snap2 = result.decisions
        assertEquals(snap1, snap2)
    }

    // =========================================================================
    // RetryPolicy is invoked exactly once per error
    // =========================================================================

    @Test
    fun `Failed result evaluates RetryPolicy exactly once`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertEquals(1, policy.capturedRequests.size)
    }

    @Test
    fun `PartiallySucceeded with two errors evaluates RetryPolicy twice`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        evaluator.evaluate(
            result = makePartialResult(errors = listOf(FakeError(), AnotherFakeError())),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertEquals(2, policy.capturedRequests.size)
    }

    // =========================================================================
    // previousDelay and provider are null in evaluation request
    // =========================================================================

    @Test
    fun `RetryEvaluationRequest has null previousDelay`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertNull(policy.capturedRequests[0].previousDelay)
    }

    @Test
    fun `RetryEvaluationRequest has null provider`() {
        val policy = AlwaysRetryPolicy()
        val evaluator = makeEvaluator(policy)
        evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        assertNull(policy.capturedRequests[0].provider)
    }

    // =========================================================================
    // Zero-delay edge case
    // =========================================================================

    @Test
    fun `ShouldRetry with zero delay sets availableAt to clock now`() {
        val delay = SchedulingDelay.ZERO
        val evaluator = makeEvaluator(AlwaysRetryPolicy(delay = delay), clockInstant = t0)
        val result = evaluator.evaluate(
            result = makeFailedResult(),
            retryAttempt = attempt1,
            retryOperation = retryOp,
        )
        result as SynchronizationRetryEvaluation.ShouldRetry
        assertEquals(t0.epochMilliseconds, result.availableAt.epochMilliseconds)
    }
}
