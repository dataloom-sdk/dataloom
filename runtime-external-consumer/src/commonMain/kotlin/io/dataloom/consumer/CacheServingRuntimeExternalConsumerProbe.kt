package io.dataloom.consumer

import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.runtime.strategy.StrategyCacheUnavailableReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/** Compile-only use of cache-first runtime outcomes from an external module. */
internal fun compileCacheServingRuntimeConsumer(
    result: StrategySynchronizationExecutionResult,
): StrategyDataOrigin? = when (result) {
    is StrategySynchronizationExecutionResult.CacheServed -> {
        val providerState: StrategyCacheState = result.freshness.cacheState
        providerState.name
        result.dataOrigin
    }
    is StrategySynchronizationExecutionResult.CacheUnavailable -> {
        val reason: StrategyCacheUnavailableReason = result.reason
        val providerState: StrategyCacheState = result.providerCacheState
        reason.name
        providerState.name
        result.providerFreshness?.validUntil
        result.dataOrigin
    }
    else -> null
}
