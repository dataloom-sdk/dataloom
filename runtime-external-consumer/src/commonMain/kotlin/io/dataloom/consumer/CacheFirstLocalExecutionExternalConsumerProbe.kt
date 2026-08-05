package io.dataloom.consumer

import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/** Compile-only use of direct cache-first local execution results. */
internal fun compileCacheFirstLocalExecutionResult(
    result: StrategySynchronizationExecutionResult,
): StrategyCacheState? = when (result) {
    is StrategySynchronizationExecutionResult.CacheAvailable ->
        result.freshness.cacheState
    is StrategySynchronizationExecutionResult.CacheUnavailable ->
        result.observedCacheState
    else -> null
}
