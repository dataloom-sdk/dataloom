package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationResult

/**
 * Additive facade for built-in strategy execution through plan-aware provider
 * timeout and circuit boundaries.
 *
 * The historical strategy `DataLoom.synchronize` methods remain unchanged.
 * Applications select this capability explicitly through
 * [DataLoom.protectedStrategySynchronization].
 */
public interface DataLoomProtectedStrategySynchronization {
    /** Executes [request] using the configured default strategy bindings. */
    public suspend fun synchronize(
        request: StrategySynchronizationRequest,
    ): ProviderProtectedStrategySynchronizationResult

    /** Executes [request] using the exact plan-aware [bindings]. */
    public suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult

    /** Executes a persisted accepted plan with default protected strategy bindings. */
    public suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): ProviderProtectedStrategySynchronizationResult

    /** Executes a persisted accepted plan with exact protected strategy bindings. */
    public suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult
}
