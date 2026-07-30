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
import kotlin.test.assertNull

class RetryHintRuntimeIntegrationTest {

    private data class HintError(
        override val retryDelayHint: RetryDelayHint,
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val code: ErrorCode = ErrorCode("DL-HINT-RUNTIME"),
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Sanitized retry hint runtime failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError, RetryDelayHintCarrier

    private class RecordingPolicy(
        private val delay: SchedulingDelay,
    ) : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("hint-recording-policy")
        val requests: MutableList<RetryEvaluationRequest> = mutableListOf()

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            requests += request
            return RetryDecision.Retry(delay = delay)
        }
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private val request = SynchronizationRequest(
        workflowId = WorkflowId("hint-runtime-workflow"),
        sessionId = SynchronizationSessionId("hint-runtime-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("hint-runtime-execution"),
            correlationId = CorrelationId("hint-runtime-correlation"),
        ),
    )

    @Test
    fun `runtime exposes bounded hint and budgets final enforced delay`() {
        val policy = RecordingPolicy(delay = SchedulingDelay(1_000L))
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = policy,
            clock = FixedClock(DataLoomInstant(2_000L)),
            budgetConfiguration = RetryBudgetConfiguration(
                maximumCumulativeDelay = SchedulingDelay(5_000L),
            ),
            hintConfiguration = RetryHintConfiguration(
                maximumHintDelay = SchedulingDelay(4_000L),
            ),
        )

        val retry = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            evaluator.evaluate(
                result = failure(
                    HintError(
                        RetryDelayHint(
                            delayMilliseconds = 10_000L,
                            source = RetryDelayHintSource.SERVER,
                        ),
                    ),
                ),
                retryAttempt = RetryAttempt(1),
                retryOperation = RetryOperation("transport.push"),
            ),
        )

        assertEquals(
            RetryDelayHint(
                delayMilliseconds = 4_000L,
                source = RetryDelayHintSource.SERVER,
            ),
            policy.requests.single().retryDelayHint,
        )
        assertEquals(SchedulingDelay(4_000L), retry.selectedDelay)
        assertEquals(DataLoomInstant(6_000L), retry.availableAt)
        assertEquals(SchedulingDelay(4_000L), retry.retryBudgetState?.cumulativeDelay)
    }

    @Test
    fun `omitted hint configuration preserves existing policy behavior`() {
        val policy = RecordingPolicy(delay = SchedulingDelay(1_000L))
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = policy,
            clock = FixedClock(DataLoomInstant(2_000L)),
        )

        val retry = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            evaluator.evaluate(
                result = failure(
                    HintError(
                        RetryDelayHint(
                            delayMilliseconds = 10_000L,
                            source = RetryDelayHintSource.PROVIDER,
                        ),
                    ),
                ),
                retryAttempt = RetryAttempt(1),
                retryOperation = RetryOperation("transport.push"),
            ),
        )

        assertNull(policy.requests.single().retryDelayHint)
        assertEquals(SchedulingDelay(1_000L), retry.selectedDelay)
        assertEquals(DataLoomInstant(3_000L), retry.availableAt)
    }

    @Test
    fun `protected hinted error stops before policy invocation`() {
        val policy = RecordingPolicy(delay = SchedulingDelay(1_000L))
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = policy,
            clock = FixedClock(DataLoomInstant(2_000L)),
            hintConfiguration = RetryHintConfiguration(
                maximumHintDelay = SchedulingDelay(4_000L),
            ),
        )

        assertIs<SynchronizationRetryEvaluation.StopRetry>(
            evaluator.evaluate(
                result = failure(
                    HintError(
                        retryDelayHint = RetryDelayHint(
                            delayMilliseconds = 3_000L,
                            source = RetryDelayHintSource.SERVER,
                        ),
                        category = ErrorCategory.AUTHENTICATION,
                    ),
                ),
                retryAttempt = RetryAttempt(1),
                retryOperation = RetryOperation("transport.push"),
            ),
        )

        assertEquals(0, policy.requests.size)
    }

    private fun failure(error: DataLoomError): SynchronizationResult.Failed =
        SynchronizationResult.Failed(
            request = request,
            completedAt = DataLoomInstant(1_000L),
            summary = SynchronizationSummary(),
            error = error,
        )
}
