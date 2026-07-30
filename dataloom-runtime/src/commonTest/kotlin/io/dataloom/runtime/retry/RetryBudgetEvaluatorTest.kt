package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RetryBudgetEvaluatorTest {

    @Test
    fun `first accepted retry starts durable window and records delay`() {
        val evaluator = RetryBudgetEvaluator(
            RetryBudgetConfiguration(
                maximumElapsedTime = SchedulingDelay(10_000L),
                maximumCumulativeDelay = SchedulingDelay(5_000L),
            ),
        )

        val accepted = assertIs<RetryBudgetEvaluation.Accepted>(
            evaluator.evaluate(
                state = null,
                evaluatedAt = DataLoomInstant(1_000L),
                proposedDelay = SchedulingDelay(750L),
            ),
        )

        assertEquals(DataLoomInstant(1_000L), accepted.nextState.windowStartedAt)
        assertEquals(DataLoomInstant(1_000L), accepted.nextState.lastEvaluatedAt)
        assertEquals(SchedulingDelay(750L), accepted.nextState.cumulativeDelay)
    }

    @Test
    fun `subsequent retry preserves window and accumulates delay`() {
        val evaluator = RetryBudgetEvaluator(
            RetryBudgetConfiguration(maximumCumulativeDelay = SchedulingDelay(5_000L)),
        )
        val current = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(1_500L),
            cumulativeDelay = SchedulingDelay(1_000L),
        )

        val accepted = assertIs<RetryBudgetEvaluation.Accepted>(
            evaluator.evaluate(
                state = current,
                evaluatedAt = DataLoomInstant(2_000L),
                proposedDelay = SchedulingDelay(1_500L),
            ),
        )

        assertEquals(DataLoomInstant(1_000L), accepted.nextState.windowStartedAt)
        assertEquals(DataLoomInstant(2_000L), accepted.nextState.lastEvaluatedAt)
        assertEquals(SchedulingDelay(2_500L), accepted.nextState.cumulativeDelay)
    }

    @Test
    fun `exact elapsed and cumulative boundaries are allowed`() {
        val evaluator = RetryBudgetEvaluator(
            RetryBudgetConfiguration(
                maximumElapsedTime = SchedulingDelay(2_000L),
                maximumCumulativeDelay = SchedulingDelay(1_000L),
            ),
        )
        val current = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(1_500L),
            cumulativeDelay = SchedulingDelay(500L),
        )

        val accepted = assertIs<RetryBudgetEvaluation.Accepted>(
            evaluator.evaluate(
                state = current,
                evaluatedAt = DataLoomInstant(2_500L),
                proposedDelay = SchedulingDelay(500L),
            ),
        )

        assertEquals(SchedulingDelay(1_000L), accepted.nextState.cumulativeDelay)
    }

    @Test
    fun `proposed availability beyond elapsed window stops`() {
        val evaluator = RetryBudgetEvaluator(
            RetryBudgetConfiguration(maximumElapsedTime = SchedulingDelay(2_000L)),
        )
        val current = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(2_000L),
            cumulativeDelay = SchedulingDelay.ZERO,
        )

        val stopped = assertIs<RetryBudgetEvaluation.Stopped>(
            evaluator.evaluate(
                state = current,
                evaluatedAt = DataLoomInstant(2_500L),
                proposedDelay = SchedulingDelay(501L),
            ),
        )

        assertEquals(RetryStopReason.ELAPSED_TIME_LIMIT_REACHED, stopped.reason)
    }

    @Test
    fun `proposed delay beyond cumulative budget stops`() {
        val evaluator = RetryBudgetEvaluator(
            RetryBudgetConfiguration(maximumCumulativeDelay = SchedulingDelay(1_000L)),
        )
        val current = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(1_000L),
            cumulativeDelay = SchedulingDelay(900L),
        )

        val stopped = assertIs<RetryBudgetEvaluation.Stopped>(
            evaluator.evaluate(
                state = current,
                evaluatedAt = DataLoomInstant(1_100L),
                proposedDelay = SchedulingDelay(101L),
            ),
        )

        assertEquals(RetryStopReason.CUMULATIVE_DELAY_LIMIT_REACHED, stopped.reason)
    }

    @Test
    fun `clock regression stops fail closed`() {
        val evaluator = RetryBudgetEvaluator(
            RetryBudgetConfiguration(maximumElapsedTime = SchedulingDelay(Long.MAX_VALUE)),
        )
        val current = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(2_000L),
            cumulativeDelay = SchedulingDelay.ZERO,
        )

        val stopped = assertIs<RetryBudgetEvaluation.Stopped>(
            evaluator.evaluate(
                state = current,
                evaluatedAt = DataLoomInstant(1_999L),
                proposedDelay = SchedulingDelay.ZERO,
            ),
        )

        assertEquals(RetryStopReason.CLOCK_REGRESSION_DETECTED, stopped.reason)
    }

    @Test
    fun `overflow saturates and finite budget stops`() {
        val evaluator = RetryBudgetEvaluator(
            RetryBudgetConfiguration(maximumCumulativeDelay = SchedulingDelay(Long.MAX_VALUE - 1L)),
        )
        val current = RetryBudgetState(
            windowStartedAt = DataLoomInstant(0L),
            lastEvaluatedAt = DataLoomInstant(0L),
            cumulativeDelay = SchedulingDelay(Long.MAX_VALUE - 5L),
        )

        val stopped = assertIs<RetryBudgetEvaluation.Stopped>(
            evaluator.evaluate(
                state = current,
                evaluatedAt = DataLoomInstant(0L),
                proposedDelay = SchedulingDelay(10L),
            ),
        )

        assertEquals(RetryStopReason.CUMULATIVE_DELAY_LIMIT_REACHED, stopped.reason)
    }
}
