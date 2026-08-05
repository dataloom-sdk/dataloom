package io.dataloom.runtime.execution.protection

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertTrue

class ProviderProtectedCacheStatusTest {

    @Test
    fun availableCacheUsesBoundedStatus() {
        val result = ProviderProtectedStrategySynchronizationResult(
            strategyResult = StrategySynchronizationExecutionResult.CacheAvailable(
                evaluation = evaluation(),
                completedAt = DataLoomInstant(3_000L),
                freshness = StrategyCacheFreshnessEvidence(
                    cacheState = StrategyCacheState.FRESH,
                    observedAt = DataLoomInstant(1_000L),
                    validUntil = DataLoomInstant(2_000L),
                ),
            ),
            operationEvidence = emptyList(),
        )

        assertTrue(result.toString().contains("status=CACHE_AVAILABLE"))
    }

    @Test
    fun unavailableCacheUsesBoundedStatus() {
        val result = ProviderProtectedStrategySynchronizationResult(
            strategyResult = StrategySynchronizationExecutionResult.CacheUnavailable(
                evaluation = evaluation(),
                completedAt = DataLoomInstant(3_000L),
                evaluatedCacheState = StrategyCacheState.FRESH,
                observedCacheState = StrategyCacheState.MISSING,
            ),
            operationEvidence = emptyList(),
        )

        assertTrue(result.toString().contains("status=CACHE_UNAVAILABLE"))
    }

    private fun evaluation(): StrategyEvaluationResult = StrategyEvaluationResult(
        decisionId = StrategyDecisionId("cache-decision"),
        plan = StrategyExecutionPlan(
            id = StrategyPlanId("cache-plan"),
            requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            effectiveProfileId = StrategyProfileId("cache-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.EXECUTE,
            operations = listOf(StrategyOperation.SERVE_LOCAL),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
            ),
            dataOrigin = StrategyDataOrigin.LOCAL,
            consistency = StrategyConsistency.EVENTUAL,
        ),
        reasonCodes = listOf("cache-first.local-verified"),
    )
}
