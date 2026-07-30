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
import io.dataloom.api.retry.RetryBudgetState
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

class RetryBudgetRuntimeIntegrationTest {

    private data class RecoverableError(
        override val code: ErrorCode = ErrorCode("DL-BUDGET-INTEGRATION"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized retry budget integration failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class MutableClock(var instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private val request = SynchronizationRequest(
        workflowId = WorkflowId("budget-runtime-workflow"),
        sessionId = SynchronizationSessionId("budget-runtime-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("budget-runtime-execution"),
            correlationId = CorrelationId("budget-runtime-correlation"),
        ),
    )

    private val failure = SynchronizationResult.Failed(
        request = request,
        completedAt = DataLoomInstant(1_000L),
        summary = SynchronizationSummary(),
        error = RecoverableError(),
    )

    @Test
    fun `accepted retry returns exact state for durable persistence`() {
        val clock = MutableClock(DataLoomInstant(2_000L))
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("budget-runtime-policy"),
                strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(500L)),
                maximumAttempts = 5,
            ),
            clock = clock,
            budgetConfiguration = RetryBudgetConfiguration(
                maximumElapsedTime = SchedulingDelay(5_000L),
                maximumCumulativeDelay = SchedulingDelay(2_000L),
            ),
        )

        val first = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            evaluator.evaluate(
                result = failure,
                retryAttempt = RetryAttempt(1),
                retryOperation = RetryOperation("transport.push"),
                retryBudgetState = null,
            ),
        )

        assertEquals(DataLoomInstant(2_500L), first.availableAt)
        assertEquals(
            RetryBudgetState(
                windowStartedAt = DataLoomInstant(2_000L),
                lastEvaluatedAt = DataLoomInstant(2_000L),
                cumulativeDelay = SchedulingDelay(500L),
            ),
            first.retryBudgetState,
        )

        clock.instant = DataLoomInstant(3_000L)
        val second = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            evaluator.evaluate(
                result = failure,
                retryAttempt = RetryAttempt(2),
                retryOperation = RetryOperation("transport.push"),
                retryBudgetState = first.retryBudgetState,
            ),
        )

        assertEquals(SchedulingDelay(1_000L), second.retryBudgetState?.cumulativeDelay)
        assertEquals(DataLoomInstant(2_000L), second.retryBudgetState?.windowStartedAt)
    }

    @Test
    fun `budget rejection replaces retry decisions with stable stop reason`() {
        val evaluator = SynchronizationRetryEvaluator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("budget-runtime-stop"),
                strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(600L)),
                maximumAttempts = 5,
            ),
            clock = MutableClock(DataLoomInstant(2_000L)),
            budgetConfiguration = RetryBudgetConfiguration(
                maximumCumulativeDelay = SchedulingDelay(500L),
            ),
        )

        val stopped = assertIs<SynchronizationRetryEvaluation.StopRetry>(
            evaluator.evaluate(
                result = failure,
                retryAttempt = RetryAttempt(1),
                retryOperation = RetryOperation("transport.push"),
                retryBudgetState = null,
            ),
        )

        val decision = assertIs<RetryDecision.Stop>(stopped.decisions.single())
        assertEquals(RetryStopReason.CUMULATIVE_DELAY_LIMIT_REACHED, decision.reason)
    }
}
