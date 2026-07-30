package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Immutable limits for central retry-budget enforcement.
 *
 * At least one limit is required. [maximumElapsedTime] bounds wall-clock time
 * from the first genuine failure through the proposed next retry availability.
 * [maximumCumulativeDelay] bounds the sum of accepted retry delays. A retry is
 * stopped rather than shortened when its proposed delay would exceed a limit.
 *
 * @param maximumElapsedTime optional maximum retry window duration.
 * @param maximumCumulativeDelay optional maximum sum of accepted retry delays.
 */
public data class RetryBudgetConfiguration(
    public val maximumElapsedTime: SchedulingDelay? = null,
    public val maximumCumulativeDelay: SchedulingDelay? = null,
) {
    init {
        require(maximumElapsedTime != null || maximumCumulativeDelay != null) {
            "RetryBudgetConfiguration requires at least one configured limit."
        }
    }
}
