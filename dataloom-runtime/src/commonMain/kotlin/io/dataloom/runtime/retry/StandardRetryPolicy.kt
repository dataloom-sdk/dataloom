package io.dataloom.runtime.retry

import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * DataLoom's deterministic built-in retry policy.
 *
 * The policy provides immediate, fixed, linear, and exponential delay
 * strategies, an enforced retry-attempt budget, overflow-safe arithmetic, and
 * the same fail-closed protection used by runtime paths around custom policies.
 *
 * [maximumAttempts] counts retry attempts after the original failed operation.
 * A value of zero disables retry. Attempt `N` is allowed only when
 * `N <= maximumAttempts`.
 *
 * Jitter, elapsed-time and aggregate-delay budgets, provider retry hints,
 * timeout separation, and circuit-breaker state are deliberately separate
 * policy/state slices rather than implicit behavior in this class.
 */
public class StandardRetryPolicy(
    override public val id: RetryPolicyId,
    public val strategy: RetryBackoffStrategy,
    public val maximumAttempts: Int,
) : RetryPolicy {

    init {
        require(maximumAttempts >= 0) {
            "maximumAttempts must be zero or greater, but was $maximumAttempts."
        }
    }

    /**
     * Returns a deterministic retry or stop decision for [request].
     *
     * Non-recoverable, unknown, and protected failure classes are rejected
     * before attempt or delay evaluation. No external state is accessed.
     */
    override public fun evaluate(request: RetryEvaluationRequest): RetryDecision {
        protectedRetryStopReason(request.error)?.let { reason ->
            return RetryDecision.Stop(reason = reason)
        }

        if (request.attempt.number > maximumAttempts) {
            return RetryDecision.Stop(reason = RetryStopReason.ATTEMPT_LIMIT_REACHED)
        }

        return RetryDecision.Retry(
            delay = calculateDelay(
                strategy = strategy,
                attemptNumber = request.attempt.number,
            ),
        )
    }
}

private fun calculateDelay(
    strategy: RetryBackoffStrategy,
    attemptNumber: Int,
): SchedulingDelay = when (strategy) {
    RetryBackoffStrategy.Immediate -> SchedulingDelay.ZERO
    is RetryBackoffStrategy.Fixed -> strategy.delay
    is RetryBackoffStrategy.Linear -> SchedulingDelay(
        linearDelayMilliseconds(strategy = strategy, attemptNumber = attemptNumber),
    )
    is RetryBackoffStrategy.Exponential -> SchedulingDelay(
        exponentialDelayMilliseconds(strategy = strategy, attemptNumber = attemptNumber),
    )
}

private fun linearDelayMilliseconds(
    strategy: RetryBackoffStrategy.Linear,
    attemptNumber: Int,
): Long {
    val initial = strategy.initialDelay.milliseconds
    val increment = strategy.increment.milliseconds
    val maximum = strategy.maximumDelay.milliseconds
    val steps = (attemptNumber - 1).toLong()

    if (steps <= 0L || increment == 0L || initial >= maximum) {
        return initial
    }

    val remaining = maximum - initial
    if (steps > remaining / increment) {
        return maximum
    }

    return initial + (steps * increment)
}

private fun exponentialDelayMilliseconds(
    strategy: RetryBackoffStrategy.Exponential,
    attemptNumber: Int,
): Long {
    val maximum = strategy.maximumDelay.milliseconds
    val multiplier = strategy.multiplier.toLong()
    var delay = strategy.initialDelay.milliseconds
    var remainingMultiplications = attemptNumber - 1

    if (remainingMultiplications <= 0 || delay == 0L || delay >= maximum) {
        return delay
    }

    while (remainingMultiplications > 0 && delay < maximum) {
        if (delay > maximum / multiplier) {
            return maximum
        }
        delay *= multiplier
        remainingMultiplications--
    }

    return delay
}
