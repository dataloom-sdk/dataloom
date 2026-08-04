package io.dataloom.runtime.queue

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QueuedStrategyDecisionCorrespondenceTest {

    @Test
    fun legacyEntryAndLegacyWorkCorrespond() {
        assertNull(
            QueuedStrategyDecisionCorrespondence.validate(
                durableDecision = null,
                resolvedDecision = null,
            ),
        )
    }

    @Test
    fun exactDurableDecisionCorresponds() {
        val decision = decision()

        assertNull(
            QueuedStrategyDecisionCorrespondence.validate(
                durableDecision = decision,
                resolvedDecision = decision,
            ),
        )
    }

    @Test
    fun changedDecisionFailsClosed() {
        val error = assertNotNull(
            QueuedStrategyDecisionCorrespondence.validate(
                durableDecision = decision(version = 3L),
                resolvedDecision = decision(version = 4L),
            ),
        )

        assertEquals("DL-Q-STRATEGY-DECISION-MISMATCH", error.code.value)
        assertEquals(ErrorCategory.CONFIGURATION, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals(null, error.cause)
    }

    @Test
    fun droppedDecisionFailsClosed() {
        assertNotNull(
            QueuedStrategyDecisionCorrespondence.validate(
                durableDecision = decision(),
                resolvedDecision = null,
            ),
        )
    }

    @Test
    fun inventedDecisionFailsClosed() {
        assertNotNull(
            QueuedStrategyDecisionCorrespondence.validate(
                durableDecision = null,
                resolvedDecision = decision(),
            ),
        )
    }

    @Test
    fun failureDiagnosticsExcludeDynamicIdentifiers() {
        val error = assertNotNull(
            QueuedStrategyDecisionCorrespondence.validate(
                durableDecision = decision(
                    decisionId = "sensitive-durable-decision",
                    planId = "sensitive-durable-plan",
                    profileId = "sensitive-durable-profile",
                ),
                resolvedDecision = decision(
                    decisionId = "sensitive-resolved-decision",
                    planId = "sensitive-resolved-plan",
                    profileId = "sensitive-resolved-profile",
                ),
            ),
        )
        val diagnostic = error.toString()

        listOf(
            "sensitive-durable-decision",
            "sensitive-durable-plan",
            "sensitive-durable-profile",
            "sensitive-resolved-decision",
            "sensitive-resolved-plan",
            "sensitive-resolved-profile",
        ).forEach { value -> assertFalse(value in diagnostic) }
    }

    private fun decision(
        version: Long = 3L,
        decisionId: String = "decision-1",
        planId: String = "plan-1",
        profileId: String = "profile-1",
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId(decisionId),
        planId = StrategyPlanId(planId),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId(profileId),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(version),
        disposition = StrategyDisposition.DEFER,
    )
}
