package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.runtime.facade.DataLoomStrategyCacheAccessProtectionSpec
import io.dataloom.runtime.facade.DataLoomStrategyProviderProtectionSpec
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.StrategyCacheAccessCircuitOperation

/** Compile-only use of the public protected cache-access configuration surface. */
internal fun compileProtectedCacheAccessConfiguration(
    configuration: CircuitBreakerConfiguration,
    stateStore: CircuitBreakerStateStore,
    scope: CircuitBreakerScope,
): DataLoomStrategyProviderProtectionSpec {
    StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS.retryOperation.value
    val cacheAccess = DataLoomStrategyCacheAccessProtectionSpec(
        circuitBreakerConfiguration = configuration,
        circuitBreakerStateStore = stateStore,
        scope = scope,
    )
    cacheAccess.toString()
    return DataLoomStrategyProviderProtectionSpec(
        cacheAccess = cacheAccess,
    )
}
