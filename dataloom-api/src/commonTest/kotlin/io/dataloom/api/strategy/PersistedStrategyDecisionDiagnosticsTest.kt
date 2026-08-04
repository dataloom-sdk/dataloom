package io.dataloom.api.strategy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistedStrategyDecisionDiagnosticsTest {

    @Test
    fun toStringExcludesDynamicIdentifiers() {
        val decision = PersistedStrategyDecision(
            decisionId = StrategyDecisionId("sensitive-decision"),
            planId = StrategyPlanId("sensitive-plan"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("sensitive-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(8L),
            disposition = StrategyDisposition.DEFER,
        )

        val diagnostic = decision.toString()

        assertFalse("sensitive-decision" in diagnostic)
        assertFalse("sensitive-plan" in diagnostic)
        assertFalse("sensitive-profile" in diagnostic)
        assertTrue("effectiveStrategy=OFFLINE_FIRST" in diagnostic)
        assertTrue("disposition=DEFER" in diagnostic)
    }
}
