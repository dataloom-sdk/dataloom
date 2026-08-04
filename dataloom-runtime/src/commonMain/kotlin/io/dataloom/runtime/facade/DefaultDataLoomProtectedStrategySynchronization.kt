package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationCoordinator
import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationResult

/** Internal immutable facade assembled by [DataLoomBuilder]. */
internal class DefaultDataLoomProtectedStrategySynchronization(
    private val coordinator: ProviderProtectedStrategySynchronizationCoordinator,
    private val defaultBindings: StrategyProviderBindings,
) : DataLoomProtectedStrategySynchronization {
    override suspend fun synchronize(
        request: StrategySynchronizationRequest,
    ): ProviderProtectedStrategySynchronizationResult =
        coordinator.execute(request, defaultBindings)

    override suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult =
        coordinator.execute(request, bindings)

    override suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): ProviderProtectedStrategySynchronizationResult =
        coordinator.executeAcceptedPlan(request, decision, plan, defaultBindings)

    override suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult =
        coordinator.executeAcceptedPlan(request, decision, plan, bindings)
}
