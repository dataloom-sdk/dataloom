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
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StandardRetryPolicyRuntimeIntegrationTest {

    private data class RecoverableNetworkError(
        override val code: ErrorCode = ErrorCode("DL-STANDARD-RETRY-INTEGRATION"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized standard retry integration failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class FixedRandomSource(
        private val value: Long,
    ) : RetryRandomSource {
        var capturedRequest: RetryRandomRequest? = null

        override fun sample(request: RetryRandomRequest): Long {
            capturedRequest = request
            return value
        }
    }

    private val request = SynchronizationRequest(
        workflowId = WorkflowId("standard-retry-runtime-workflow"),
        sessionId = SynchronizationSessionId("standard-retry-runtime-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("standard-retry-runtime-execution"),
            correlationId = CorrelationId("standard-retry-runtime-correlation"),
        ),
    )

    private val failure = SynchronizationResult.Failed(
        request = request,
        completedAt = DataLoomInstant(900L),
        summary = SynchronizationSummary(),
        error = RecoverableNetworkError(),
    )

    @Test
    fun `standard policy delay is converted into exact queue availability`() {
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("standard-runtime-fixed"),
                strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(250L)),
                maximumAttempts = 1,
            ),
            clock = FixedClock(DataLoomInstant(1_000L)),
        )

        val evaluation = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            evaluator.evaluate(
                result = failure,
                retryAttempt = RetryAttempt(1),
                retryOperation = RetryOperation("transport.push"),
            ),
        )

        assertEquals(SchedulingDelay(250L), evaluation.selectedDelay)
        assertEquals(DataLoomInstant(1_250L), evaluation.availableAt)
        assertEquals(RetryAttempt(1), evaluation.retryAttempt)
    }

    @Test
    fun `jittered delay is converted into exact queue availability`() {
        val randomSource = FixedRandomSource(value = 75L)
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("standard-runtime-jitter"),
                strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(250L)),
                maximumAttempts = 1,
                jitterStrategy = RetryJitterStrategy.Full,
                randomSource = randomSource,
            ),
            clock = FixedClock(DataLoomInstant(1_000L)),
        )

        val evaluation = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            evaluator.evaluate(
                result = failure,
                retryAttempt = RetryAttempt(1),
                retryOperation = RetryOperation("transport.push"),
            ),
        )

        assertEquals(SchedulingDelay(75L), evaluation.selectedDelay)
        assertEquals(DataLoomInstant(1_075L), evaluation.availableAt)
        assertEquals(250L, randomSource.capturedRequest?.maximumInclusive)
    }

    @Test
    fun `standard policy attempt exhaustion stops before queue reschedule`() {
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("standard-runtime-budget"),
                strategy = RetryBackoffStrategy.Immediate,
                maximumAttempts = 1,
            ),
            clock = FixedClock(DataLoomInstant(1_000L)),
        )

        val evaluation = assertIs<SynchronizationRetryEvaluation.StopRetry>(
            evaluator.evaluate(
                result = failure,
                retryAttempt = RetryAttempt(2),
                retryOperation = RetryOperation("transport.push"),
            ),
        )

        val decision = assertIs<RetryDecision.Stop>(evaluation.decisions.single())
        assertEquals(RetryStopReason.ATTEMPT_LIMIT_REACHED, decision.reason)
    }
}
