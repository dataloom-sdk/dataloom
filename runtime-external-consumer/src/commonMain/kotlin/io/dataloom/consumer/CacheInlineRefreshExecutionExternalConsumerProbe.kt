package io.dataloom.consumer

import io.dataloom.runtime.strategy.StrategyCacheInlineRefreshResult
import io.dataloom.runtime.strategy.StrategyCacheServedWithInlineRefreshResult
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/** Compile-only inspection of the public cache-served inline-refresh outcome. */
internal fun inspectCacheInlineRefreshExecution(
    result: StrategySynchronizationExecutionResult,
): String = when (result) {
    is StrategyCacheServedWithInlineRefreshResult -> {
        result.evaluation.plan.id
        result.completedAt
        result.evaluatedCacheState
        result.freshness.cacheState
        result.dataOrigin
        when (val refresh = result.refresh) {
            is StrategyCacheInlineRefreshResult.Completed -> {
                refresh.transportAttempted
                refresh.completedOperations
                refresh.output.result
            }
            is StrategyCacheInlineRefreshResult.PartiallySucceeded -> {
                refresh.transportAttempted
                refresh.completedOperations
                refresh.output.result
            }
            is StrategyCacheInlineRefreshResult.Failed -> {
                refresh.error.code
                refresh.transportAttempted
                refresh.completedOperations
                refresh.output.result
                refresh.remoteOutcome
            }
            is StrategyCacheInlineRefreshResult.Cancelled -> {
                refresh.transportAttempted
                refresh.completedOperations
                refresh.output.result
            }
        }
        result.refresh.disposition.name
    }
    else -> "OTHER"
}
