package io.dataloom.consumer

import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationResult
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomStrategyProviderProtectionSpec

/** External-consumer compilation probe for plan-aware protected strategy execution. */
public object ProtectedStrategySynchronizationExternalConsumerProbe {
    public fun configure(
        builder: DataLoomBuilder,
        spec: DataLoomStrategyProviderProtectionSpec,
    ): DataLoomBuilder = builder.strategyProviderProtectionConfiguration(spec)

    public suspend fun executeDefault(
        dataLoom: DataLoom,
        request: StrategySynchronizationRequest,
    ): ProviderProtectedStrategySynchronizationResult? =
        dataLoom.protectedStrategySynchronization?.synchronize(request)

    public suspend fun executeExplicit(
        dataLoom: DataLoom,
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult? =
        dataLoom.protectedStrategySynchronization?.synchronize(request, bindings)

    public fun evidenceCount(
        result: ProviderProtectedStrategySynchronizationResult,
    ): Int = result.operationEvidence.size
}
