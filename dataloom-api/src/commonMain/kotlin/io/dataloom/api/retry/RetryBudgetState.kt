package io.dataloom.api.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable durable state for elapsed-time and cumulative-delay retry budgets.
 *
 * The state contains only bounded timing evidence. It does not contain payloads,
 * credentials, provider instances, exception text, or application metadata.
 * Queue providers persist it unchanged so retry limits survive process death,
 * lease recovery, and non-retry deferral.
 *
 * [windowStartedAt] records the first genuine failure that entered the retry
 * budget window. [lastEvaluatedAt] records the most recent accepted retry
 * evaluation and must never precede [windowStartedAt]. [cumulativeDelay]
 * contains the sum of delays for retries that were accepted for durable
 * rescheduling or scheduling.
 *
 * @param windowStartedAt first genuine retry-budget evaluation instant.
 * @param lastEvaluatedAt most recent accepted retry-budget evaluation instant.
 * @param cumulativeDelay sum of previously accepted retry delays.
 */
public data class RetryBudgetState(
    public val windowStartedAt: DataLoomInstant,
    public val lastEvaluatedAt: DataLoomInstant,
    public val cumulativeDelay: SchedulingDelay,
) {
    init {
        require(lastEvaluatedAt.epochMilliseconds >= windowStartedAt.epochMilliseconds) {
            "RetryBudgetState lastEvaluatedAt must not precede windowStartedAt."
        }
    }
}
