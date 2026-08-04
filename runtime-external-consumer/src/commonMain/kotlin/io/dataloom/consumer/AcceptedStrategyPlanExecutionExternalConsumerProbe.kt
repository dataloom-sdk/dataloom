package io.dataloom.consumer

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationResult
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomProtectedStrategySynchronization
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

public suspend fun acceptedStrategyPlanExecutionExternalConsumerProbe(
    dataLoom: DataLoom,
    request: SynchronizationRequest,
    decision: PersistedStrategyDecision,
    plan: StrategyExecutionPlan,
    bindings: StrategyProviderBindings,
): StrategySynchronizationExecutionResult =
    dataLoom.synchronizeAcceptedPlan(request, decision, plan, bindings)

public suspend fun protectedAcceptedStrategyPlanExecutionExternalConsumerProbe(
    protectedStrategy: DataLoomProtectedStrategySynchronization,
    request: SynchronizationRequest,
    decision: PersistedStrategyDecision,
    plan: StrategyExecutionPlan,
    bindings: StrategyProviderBindings,
): ProviderProtectedStrategySynchronizationResult =
    protectedStrategy.synchronizeAcceptedPlan(request, decision, plan, bindings)
