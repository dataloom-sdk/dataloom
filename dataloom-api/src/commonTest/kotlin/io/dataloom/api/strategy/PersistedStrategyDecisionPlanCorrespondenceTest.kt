package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistedStrategyDecisionPlanCorrespondenceTest {

    @Test
    fun exactIdentityCorresponds() {
        val plan = plan()
        assertTrue(decision().correspondsTo(plan))
    }

    @Test
    fun everyIdentityDimensionIsChecked() {
        val plan = plan()
        assertFalse(decision(planId = "other").correspondsTo(plan))
        assertFalse(
            decision(requested = BuiltInSynchronizationStrategy.REMOTE_FIRST)
                .correspondsTo(plan),
        )
        assertFalse(decision(profileId = "other").correspondsTo(plan))
        assertFalse(
            decision(effective = BuiltInSynchronizationStrategy.CACHE_FIRST)
                .correspondsTo(plan),
        )
        assertFalse(decision(version = 8L).correspondsTo(plan))
        assertFalse(decision(disposition = StrategyDisposition.EXECUTE).correspondsTo(plan))
    }

    private fun decision(
        planId: String = "plan-1",
        requested: BuiltInSynchronizationStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        profileId: String = "offline-profile",
        effective: BuiltInSynchronizationStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        version: Long = 7L,
        disposition: StrategyDisposition = StrategyDisposition.DEFER,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId(planId),
        requestedStrategy = requested,
        effectiveProfileId = StrategyProfileId(profileId),
        effectiveStrategy = effective,
        configurationVersion = StrategyConfigurationVersion(version),
        disposition = disposition,
    )

    private fun plan(): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(7L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(
            StrategyOperation.ACCEPT_LOCAL,
            StrategyOperation.ENQUEUE_DURABLE_WORK,
        ),
        requiredCapabilities = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.QUEUE,
        ),
        dataOrigin = StrategyDataOrigin.LOCAL,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(StrategyOperation.READ_LOCAL, StrategyOperation.PUSH_REMOTE),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )
}
