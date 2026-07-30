package io.dataloom.runtime.retry

import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * DataLoom's standard retry policy for eligible failures.
 *
 * The policy provides immediate, fixed, linear, and exponential backoff,
 * optional deterministic full/equal jitter, an enforced retry-attempt budget,
 * overflow-safe arithmetic, and the same fail-closed protection used by the
 * surrounding runtime.
 *
 * [maximumAttempts] counts retry attempts after the original failed operation.
 * A value of zero disables retry. Attempt `N` is allowed only when
 * `N <= maximumAttempts`.
 *
 * The three-argument constructor preserves exact deterministic base delays and
 * requires no random source. The five-argument constructor applies
 * [jitterStrategy] using the explicitly injected [RetryRandomSource]. The source
 * is called only for an eligible, budget-approved attempt with a non-zero jitter
 * window.
 *
 * Elapsed-time and aggregate-delay budgets, provider retry hints, timeout
 * separation, and circuit-breaker state are separate V1 policy/state slices.
 */
public class StandardRetryPolicy private constructor(
    override public val id: RetryPolicyId,
    public val strategy: RetryBackoffStrategy,
    public val maximumAttempts: Int,
    private val jitterConfiguration: StandardRetryJitterConfiguration,
) : RetryPolicy {

    /** Jitter mode applied after the deterministic base delay is calculated. */
    public val jitterStrategy: RetryJitterStrategy
        get() = jitterConfiguration.strategy

    /**
     * Creates a standard policy with deterministic base backoff and no jitter.
     *
     * @param id stable retry policy identifier.
     * @param strategy deterministic base backoff strategy.
     * @param maximumAttempts maximum retry attempts after the original failure.
     */
    public constructor(
        id: RetryPolicyId,
        strategy: RetryBackoffStrategy,
        maximumAttempts: Int,
    ) : this(
        id = id,
        strategy = strategy,
        maximumAttempts = maximumAttempts,
        jitterConfiguration = StandardRetryJitterConfiguration(
            strategy = RetryJitterStrategy.None,
            randomSource = null,
        ),
    )

    /**
     * Creates a standard policy with explicitly configured deterministic jitter.
     *
     * [randomSource] must satisfy the deterministic and bounded
     * [RetryRandomSource] contract. It may be shared safely when its own
     * implementation is thread-safe.
     *
     * @param id stable retry policy identifier.
     * @param strategy deterministic base backoff strategy.
     * @param maximumAttempts maximum retry attempts after the original failure.
     * @param jitterStrategy jitter mode applied after base delay calculation.
     * @param randomSource injected deterministic bounded sample source.
     */
    public constructor(
        id: RetryPolicyId,
        strategy: RetryBackoffStrategy,
        maximumAttempts: Int,
        jitterStrategy: RetryJitterStrategy,
        randomSource: RetryRandomSource,
    ) : this(
        id = id,
        strategy = strategy,
        maximumAttempts = maximumAttempts,
        jitterConfiguration = StandardRetryJitterConfiguration(
            strategy = jitterStrategy,
            randomSource = randomSource,
        ),
    )

    init {
        require(maximumAttempts >= 0) {
            "maximumAttempts must be zero or greater, but was $maximumAttempts."
        }
    }

    /**
     * Returns a deterministic retry or stop decision for [request].
     *
     * Non-recoverable, unknown, and protected failure classes are rejected
     * before attempt, backoff, or jitter evaluation. A faulty random source that
     * returns an out-of-range sample causes an [IllegalStateException].
     */
    override public fun evaluate(request: RetryEvaluationRequest): RetryDecision {
        protectedRetryStopReason(request.error)?.let { reason ->
            return RetryDecision.Stop(reason = reason)
        }

        if (request.attempt.number > maximumAttempts) {
            return RetryDecision.Stop(reason = RetryStopReason.ATTEMPT_LIMIT_REACHED)
        }

        val baseDelay = calculateDelay(
            strategy = strategy,
            attemptNumber = request.attempt.number,
        )

        return RetryDecision.Retry(
            delay = applyJitter(
                baseDelay = baseDelay,
                request = request,
            ),
        )
    }

    private fun applyJitter(
        baseDelay: SchedulingDelay,
        request: RetryEvaluationRequest,
    ): SchedulingDelay {
        val baseMilliseconds = baseDelay.milliseconds
        if (jitterStrategy == RetryJitterStrategy.None || baseMilliseconds == 0L) {
            return baseDelay
        }

        val lowerBound: Long
        val randomMaximum: Long
        when (jitterStrategy) {
            RetryJitterStrategy.None -> return baseDelay
            RetryJitterStrategy.Full -> {
                lowerBound = 0L
                randomMaximum = baseMilliseconds
            }
            RetryJitterStrategy.Equal -> {
                lowerBound = (baseMilliseconds / 2L) + (baseMilliseconds % 2L)
                randomMaximum = baseMilliseconds - lowerBound
            }
        }

        if (randomMaximum == 0L) {
            return SchedulingDelay(lowerBound)
        }

        val source = checkNotNull(jitterConfiguration.randomSource) {
            "A RetryRandomSource is required when jitter is enabled."
        }
        val randomValue = source.sample(
            RetryRandomRequest(
                policyId = id,
                workflowId = request.synchronizationRequest.workflowId,
                sessionId = request.synchronizationRequest.sessionId,
                operation = request.operation,
                errorCode = request.error.code,
                attempt = request.attempt,
                maximumInclusive = randomMaximum,
            ),
        )
        check(randomValue in 0L..randomMaximum) {
            "RetryRandomSource returned $randomValue outside 0..$randomMaximum."
        }

        return SchedulingDelay(lowerBound + randomValue)
    }
}

private data class StandardRetryJitterConfiguration(
    val strategy: RetryJitterStrategy,
    val randomSource: RetryRandomSource?,
)

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
