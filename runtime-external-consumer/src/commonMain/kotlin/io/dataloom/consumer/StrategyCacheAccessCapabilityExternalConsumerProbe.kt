package io.dataloom.consumer

import io.dataloom.api.strategy.StrategyProviderCapability

/** Compile-only use of the cache-access provider capability from an external module. */
internal val cacheAccessCapability: StrategyProviderCapability =
    StrategyProviderCapability.CACHE_ACCESS
