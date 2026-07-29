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
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetryProtectionIntegrationTest {

    private data class FakeError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability,
        override val message: String = "Sanitized retry protection failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class CountingRetryPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("counting-retry-policy")
        var calls: Int = 0

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            calls++
            return RetryDecision.Retry(delay = SchedulingDelay(500L))
        }
    }

    private class FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(10_000L)
    }

    private val request = SynchronizationRequest(
        workflowId = WorkflowId("retry-protection-workflow"),
        sessionId = SynchronizationSessionId("retry-protection-session"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("retry-protection-execution"),
            correlationId = CorrelationId("retry-protection-correlation"),
        ),
    )

    @Test
    fun `protected sibling blocks entire partial retry without invoking custom policy`() {
        val transientError = FakeError(
            code = ErrorCode("DL-NETWORK-TRANSIENT"),
            category = ErrorCategory.NETWORK,
            recoverability = Recoverability.RECOVERABLE,
        )
        val protectedError = FakeError(
            code = ErrorCode("DL-AUTH-REJECTED"),
            category = ErrorCategory.AUTHORIZATION,
            recoverability = Recoverability.RECOVERABLE,
        )
        val policy = CountingRetryPolicy()
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = policy,
            clock = FixedClock(),
        )

        val result = evaluator.evaluate(
            result = SynchronizationResult.PartiallySucceeded(
                request = request,
                completedAt = DataLoomInstant(9_000L),
                summary = SynchronizationSummary(),
                errors = listOf(transientError, protectedError),
            ),
            retryAttempt = RetryAttempt(1),
            retryOperation = RetryOperation("sync.execution"),
        )

        val stopped = assertIs<SynchronizationRetryEvaluation.StopRetry>(result)
        assertEquals(protectedError, stopped.error)
        assertTrue(stopped.decisions.all { it is RetryDecision.Stop })
        assertEquals(0, policy.calls)
    }

    @Test
    fun `fully recoverable failure set still invokes configured policy`() {
        val firstError = FakeError(
            code = ErrorCode("DL-NETWORK-ONE"),
            category = ErrorCategory.NETWORK,
            recoverability = Recoverability.RECOVERABLE,
        )
        val secondError = FakeError(
            code = ErrorCode("DL-STORAGE-TWO"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val policy = CountingRetryPolicy()
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = policy,
            clock = FixedClock(),
        )

        val result = evaluator.evaluate(
            result = SynchronizationResult.PartiallySucceeded(
                request = request,
                completedAt = DataLoomInstant(9_000L),
                summary = SynchronizationSummary(),
                errors = listOf(firstError, secondError),
            ),
            retryAttempt = RetryAttempt(1),
            retryOperation = RetryOperation("sync.execution"),
        )

        assertIs<SynchronizationRetryEvaluation.ShouldRetry>(result)
        assertEquals(2, policy.calls)
    }

    @Test
    fun `non recoverable and unknown errors both bypass custom policy`() {
        val policy = CountingRetryPolicy()
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = policy,
            clock = FixedClock(),
        )
        val nonRecoverable = FakeError(
            code = ErrorCode("DL-PROVIDER-FATAL"),
            category = ErrorCategory.PROVIDER,
            recoverability = Recoverability.NON_RECOVERABLE,
        )
        val unknown = FakeError(
            code = ErrorCode("DL-PROVIDER-UNKNOWN"),
            category = ErrorCategory.PROVIDER,
            recoverability = Recoverability.UNKNOWN,
        )

        val first = evaluator.evaluate(
            result = SynchronizationResult.Failed(
                request = request,
                completedAt = DataLoomInstant(9_000L),
                summary = SynchronizationSummary(),
                error = nonRecoverable,
            ),
            retryAttempt = RetryAttempt(1),
            retryOperation = RetryOperation("sync.execution"),
        )
        val second = evaluator.evaluate(
            result = SynchronizationResult.Failed(
                request = request,
                completedAt = DataLoomInstant(9_000L),
                summary = SynchronizationSummary(),
                error = unknown,
            ),
            retryAttempt = RetryAttempt(1),
            retryOperation = RetryOperation("sync.execution"),
        )

        assertIs<SynchronizationRetryEvaluation.StopRetry>(first)
        assertIs<SynchronizationRetryEvaluation.StopRetry>(second)
        assertEquals(0, policy.calls)
    }
}
