package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Platform-independent evaluator that inspects a terminal
 * [SynchronizationResult], applies central retry protection, evaluates
 * [RetryPolicy] only for a fully retry-eligible error set, selects the maximum
 * delay, and computes an overflow-safe availability instant using [clock].
 *
 * A succeeded, skipped, or cancelled result returns
 * [SynchronizationRetryEvaluation.NotRequired]. A failed or partially
 * succeeded result containing any protected error returns
 * [SynchronizationRetryEvaluation.StopRetry] without invoking the configured
 * policy. Otherwise the policy is evaluated once per error in original order.
 *
 * This evaluator does not invoke a scheduler or durable queue and does not
 * increment [RetryAttempt]. Unexpected policy exceptions propagate.
 */
public class SynchronizationRetryEvaluator(
    private val retryPolicy: RetryPolicy,
    private val clock: DataLoomClock,
) {

    /**
     * Evaluates retry policy for [result] using the exact [retryAttempt] and
     * [retryOperation] supplied by the caller.
     */
    public fun evaluate(
        result: SynchronizationResult,
        retryAttempt: RetryAttempt,
        retryOperation: RetryOperation,
    ): SynchronizationRetryEvaluation {
        val errors = extractRetryErrors(result)
            ?: return SynchronizationRetryEvaluation.NotRequired

        val evaluated = evaluateRetryDecisions(
            retryPolicy = retryPolicy,
            synchronizationRequest = result.request,
            retryOperation = retryOperation,
            retryAttempt = retryAttempt,
            errors = errors,
        )

        evaluated.blockingError?.let { blockingError ->
            return SynchronizationRetryEvaluation.StopRetry(
                error = blockingError,
                decisions = evaluated.decisions,
            )
        }

        val maxDelay = selectMaxRetryDelay(evaluated.decisions)
            ?: return SynchronizationRetryEvaluation.StopRetry(
                error = errors.first(),
                decisions = evaluated.decisions,
            )

        val nowMillis = clock.now().epochMilliseconds
        val availableAtMillis = addMillisOverflowSafe(nowMillis, maxDelay.milliseconds)

        return SynchronizationRetryEvaluation.ShouldRetry(
            retryAttempt = retryAttempt,
            availableAt = DataLoomInstant(epochMilliseconds = availableAtMillis),
            error = errors.first(),
            decisions = evaluated.decisions,
            selectedDelay = maxDelay,
        )
    }

    private fun addMillisOverflowSafe(epochMillis: Long, delayMillis: Long): Long {
        val sum = epochMillis + delayMillis
        return if (sum < 0L) Long.MAX_VALUE else sum
    }
}
