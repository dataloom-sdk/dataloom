package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StrategyExecutionPlanCodecTest {

    @Test
    fun roundTripPreservesAcceptedPlanAndContinuation() {
        val plan = plan()
        assertEquals(plan, StrategyExecutionPlanCodec.decode(StrategyExecutionPlanCodec.encode(plan)))
    }

    @Test
    fun capabilityAndOutcomeSetsEncodeDeterministically() {
        val first = plan(
            continuationCapabilities = linkedSetOf(
                StrategyProviderCapability.TRANSPORT,
                StrategyProviderCapability.STORAGE,
            ),
        )
        val second = plan(
            continuationCapabilities = linkedSetOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
        )
        assertEquals(
            StrategyExecutionPlanCodec.encode(first),
            StrategyExecutionPlanCodec.encode(second),
        )
    }

    @Test
    fun malformedAndUnsupportedFramesFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            StrategyExecutionPlanCodec.decode("not-a-plan")
        }
        val encoded = StrategyExecutionPlanCodec.encode(plan())
        assertFailsWith<IllegalArgumentException> {
            StrategyExecutionPlanCodec.decode(encoded.replace("|1|", "|2|"))
        }
    }

    @Test
    fun diagnosticsDoNotExposeEncodedDynamicIdentifiers() {
        val plan = plan(
            planId = "sensitive-plan",
            profileId = "sensitive-profile",
        )
        val diagnostic = plan.durableContinuation.toString()
        assertFalse("sensitive-plan" in diagnostic)
        assertFalse("sensitive-profile" in diagnostic)
    }

    private fun plan(
        planId: String = "plan-雪-1",
        profileId: String = "profile-1",
        continuationCapabilities: Set<StrategyProviderCapability> = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.TRANSPORT,
        ),
    ): StrategyExecutionPlan {
        val fallback = StrategyFallbackPlan(
            remoteOutcomes = setOf(
                StrategyRemoteOutcome.UNAVAILABLE,
                StrategyRemoteOutcome.SERVER_FAILURE,
            ),
            operations = listOf(StrategyOperation.SERVE_LOCAL),
            dataOrigin = StrategyDataOrigin.LOCAL,
        )
        return StrategyExecutionPlan(
            id = StrategyPlanId(planId),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId(profileId),
            effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            configurationVersion = StrategyConfigurationVersion(11L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNKNOWN,
            durableContinuation = StrategyDurableContinuationPlan(
                operations = listOf(
                    StrategyOperation.READ_CHECKPOINT,
                    StrategyOperation.PULL_REMOTE,
                    StrategyOperation.PERSIST_REMOTE,
                ),
                requiredCapabilities = continuationCapabilities,
                dataOrigin = StrategyDataOrigin.REMOTE,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
                evaluatedCacheState = StrategyCacheState.STALE,
                fallbackPlan = fallback,
            ),
        )
    }
}
