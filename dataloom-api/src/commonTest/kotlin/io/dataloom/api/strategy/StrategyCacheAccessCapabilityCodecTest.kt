package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import kotlin.test.Test
import kotlin.test.assertEquals

class StrategyCacheAccessCapabilityCodecTest {

    @Test
    fun cacheAccessCapabilityRoundTripsThroughV1PlanFrame() {
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("cache-plan"),
            requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            effectiveProfileId = StrategyProfileId("cache-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            configurationVersion = StrategyConfigurationVersion(3),
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
        )

        assertEquals(
            plan,
            StrategyExecutionPlanCodec.decode(StrategyExecutionPlanCodec.encode(plan)),
        )
    }
}
