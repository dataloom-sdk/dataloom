package io.dataloom.api.strategy

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PersistedStrategyDecisionTest {

    @Test
    fun effectiveStrategyMustBeConcrete() {
        assertFailsWith<IllegalArgumentException> {
            decision(effective = BuiltInSynchronizationStrategy.ADAPTIVE)
        }
    }

    @Test
    fun rejectedDecisionCannotBecomeDurableWork() {
        assertFailsWith<IllegalArgumentException> {
            decision(disposition = StrategyDisposition.REJECT)
        }
    }

    private fun decision(
        effective: BuiltInSynchronizationStrategy =
            BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        disposition: StrategyDisposition = StrategyDisposition.DEFER,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("profile-1"),
        effectiveStrategy = effective,
        configurationVersion = StrategyConfigurationVersion(4L),
        disposition = disposition,
    )
}
