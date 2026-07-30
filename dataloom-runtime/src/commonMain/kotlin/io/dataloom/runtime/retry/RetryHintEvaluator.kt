package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.RetryDelayHint
import io.dataloom.api.error.RetryDelayHintCarrier
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.scheduling.SchedulingDelay

/** Central, side-effect-free normalization and enforcement for retry hints. */
internal class RetryHintEvaluator(
    private val configuration: RetryHintConfiguration,
) {
    /**
     * Extracts and clamps an optional hint from [error].
     *
     * The result is safe to expose through a runtime-created policy request. Raw
     * protocol text and provider internals never cross this boundary.
     */
    fun boundedHint(error: DataLoomError): RetryDelayHint? {
        val hint = (error as? RetryDelayHintCarrier)?.retryDelayHint ?: return null
        val boundedMilliseconds = minOf(
            hint.delayMilliseconds,
            configuration.maximumHintDelay.milliseconds,
        )
        return if (boundedMilliseconds == hint.delayMilliseconds) {
            hint
        } else {
            hint.copy(delayMilliseconds = boundedMilliseconds)
        }
    }

    /**
     * Enforces [boundedHint] as a minimum on a policy [decision].
     *
     * Stop decisions are never changed. Retry metadata is preserved exactly.
     */
    fun apply(
        decision: RetryDecision,
        boundedHint: RetryDelayHint?,
    ): RetryDecision {
        if (decision !is RetryDecision.Retry || boundedHint == null) return decision

        val finalMilliseconds = maxOf(
            decision.delay.milliseconds,
            boundedHint.delayMilliseconds,
        )
        if (finalMilliseconds == decision.delay.milliseconds) return decision

        return decision.copy(delay = SchedulingDelay(finalMilliseconds))
    }
}
