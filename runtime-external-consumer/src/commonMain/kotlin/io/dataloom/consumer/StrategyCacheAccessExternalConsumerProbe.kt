package io.dataloom.consumer

import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.time.DataLoomInstant

/** Compile-only use of the cache-first access contract from an external module. */
internal fun compileCacheAccessConsumer(
    provider: StrategyCacheAccessProvider,
    request: StrategyCacheAccessRequest,
): StrategyCacheAccessResult {
    provider.descriptor.id
    request.evaluatedCacheState
    val evidence = StrategyCacheFreshnessEvidence(
        cacheState = StrategyCacheState.FRESH,
        observedAt = DataLoomInstant(1_000L),
        validUntil = DataLoomInstant(2_000L),
    )
    return StrategyCacheAccessResult.Available(evidence)
}
