package io.dataloom.runtime.queue

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QueuedStrategyPlanCorrespondenceTest {

    @Test
    fun exactDecisionAndPlanCorrespond() {
        val decision = decision()
        val plan = plan()
        assertNull(
            QueuedStrategyDecisionCorrespondence.validate(
                durableDecision = decision,
                resolvedDecision = decision,
                durablePlan = plan,
                resolvedPlan = plan,
            ),
        )
    }

    @Test
    fun changedDroppedAndInventedPlansFailClosed() {
        val decision = decision()
        assertPlanFailure(
            QueuedStrategyDecisionCorrespondence.validate(
                decision,
                decision,
                plan(
                    continuationConsistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
                ),
                plan(continuationConsistency = StrategyConsistency.EVENTUAL),
            ),
        )
        assertPlanFailure(
            QueuedStrategyDecisionCorrespondence.validate(
                decision,
                decision,
                plan(),
                null,
            ),
        )
        assertPlanFailure(
            QueuedStrategyDecisionCorrespondence.validate(
                decision,
                decision,
                null,
                plan(),
            ),
        )
    }

    @Test
    fun planFailureDiagnosticsExcludeDynamicIdentifiersAndContents() {
        val error = assertNotNull(
            QueuedStrategyDecisionCorrespondence.validate(
                decision(),
                decision(),
                plan(planId = "durable-sensitive"),
                plan(planId = "resolved-sensitive"),
            ),
        )
        val diagnostic = error.toString()
        assertFalse("durable-sensitive" in diagnostic)
        assertFalse("resolved-sensitive" in diagnostic)
    }

    private fun assertPlanFailure(error: io.dataloom.api.error.DataLoomError?) {
        val actual = assertNotNull(error)
        assertEquals("DL-Q-STRATEGY-PLAN-MISMATCH", actual.code.value)
        assertEquals(ErrorCategory.CONFIGURATION, actual.category)
        assertEquals(Recoverability.NON_RECOVERABLE, actual.recoverability)
        assertEquals(null, actual.cause)
    }

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("profile-1"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        disposition = StrategyDisposition.DEFER,
    )

    private fun plan(
        planId: String = "plan-1",
        continuationConsistency: StrategyConsistency =
            StrategyConsistency.LOCAL_AUTHORITATIVE,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId(planId),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("profile-1"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(StrategyOperation.PUSH_REMOTE),
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = continuationConsistency,
        ),
    )
}
