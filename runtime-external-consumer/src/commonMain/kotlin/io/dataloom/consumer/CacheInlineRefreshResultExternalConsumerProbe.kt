package io.dataloom.consumer

import io.dataloom.runtime.strategy.StrategyCacheInlineRefreshDisposition
import io.dataloom.runtime.strategy.StrategyCacheInlineRefreshResult

/** Compile-only use of the public inline cache refresh outcome contract. */
internal fun inspectInlineCacheRefresh(
    result: StrategyCacheInlineRefreshResult,
): StrategyCacheInlineRefreshDisposition {
    result.completedAt
    when (result) {
        is StrategyCacheInlineRefreshResult.Completed -> {
            result.transportAttempted
            result.completedOperations
            result.output.result
        }
        is StrategyCacheInlineRefreshResult.PartiallySucceeded -> {
            result.transportAttempted
            result.completedOperations
            result.output.result
        }
        is StrategyCacheInlineRefreshResult.Failed -> {
            result.error.code
            result.transportAttempted
            result.completedOperations
            result.output.result
            result.remoteOutcome
        }
        is StrategyCacheInlineRefreshResult.Cancelled -> {
            result.transportAttempted
            result.completedOperations
            result.output.result
        }
    }
    return result.disposition
}
