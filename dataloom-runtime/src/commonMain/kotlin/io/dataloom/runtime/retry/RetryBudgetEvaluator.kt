package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/** Central, side-effect-free elapsed-time and cumulative-delay budget evaluator. */
internal class RetryBudgetEvaluator(
    private val configuration: RetryBudgetConfiguration,
) {
    /**
     * Evaluates [proposedDelay] against the current durable [state].
     *
     * A missing state starts a new window at [evaluatedAt]. Accepted evaluations
     * return the exact state that must be persisted with the retry transition.
     * Rejected evaluations never advance durable state.
     */
    fun evaluate(
        state: RetryBudgetState?,
        evaluatedAt: DataLoomInstant,
        proposedDelay: SchedulingDelay,
    ): RetryBudgetEvaluation {
        val current = state ?: RetryBudgetState(
            windowStartedAt = evaluatedAt,
            lastEvaluatedAt = evaluatedAt,
            cumulativeDelay = SchedulingDelay.ZERO,
        )

        if (evaluatedAt.epochMilliseconds < current.windowStartedAt.epochMilliseconds ||
            evaluatedAt.epochMilliseconds < current.lastEvaluatedAt.epochMilliseconds
        ) {
            return RetryBudgetEvaluation.Stopped(RetryStopReason.CLOCK_REGRESSION_DETECTED)
        }

        val elapsedBefore = evaluatedAt.epochMilliseconds - current.windowStartedAt.epochMilliseconds
        val elapsedThroughNextRetry = addSaturated(elapsedBefore, proposedDelay.milliseconds)
        val maximumElapsed = configuration.maximumElapsedTime?.milliseconds
        if (maximumElapsed != null && elapsedThroughNextRetry > maximumElapsed) {
            return RetryBudgetEvaluation.Stopped(RetryStopReason.ELAPSED_TIME_LIMIT_REACHED)
        }

        val cumulativeAfter = addSaturated(
            current.cumulativeDelay.milliseconds,
            proposedDelay.milliseconds,
        )
        val maximumCumulative = configuration.maximumCumulativeDelay?.milliseconds
        if (maximumCumulative != null && cumulativeAfter > maximumCumulative) {
            return RetryBudgetEvaluation.Stopped(RetryStopReason.CUMULATIVE_DELAY_LIMIT_REACHED)
        }

        return RetryBudgetEvaluation.Accepted(
            RetryBudgetState(
                windowStartedAt = current.windowStartedAt,
                lastEvaluatedAt = evaluatedAt,
                cumulativeDelay = SchedulingDelay(cumulativeAfter),
            ),
        )
    }
}

/** Result of one central retry-budget evaluation. */
internal sealed interface RetryBudgetEvaluation {
    data class Accepted(val nextState: RetryBudgetState) : RetryBudgetEvaluation
    data class Stopped(val reason: RetryStopReason) : RetryBudgetEvaluation
}

private fun addSaturated(left: Long, right: Long): Long {
    if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    return left + right
}
