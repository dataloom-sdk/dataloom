package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategyExecutionPlanHardeningTest {

    @Test
    fun fallbackPlanRejectsProtectedRemoteOutcomes() {
        listOf(
            StrategyRemoteOutcome.CANCELLED,
            StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
            StrategyRemoteOutcome.AUTHORIZATION_FAILURE,
            StrategyRemoteOutcome.VALIDATION_FAILURE,
            StrategyRemoteOutcome.INTEGRITY_FAILURE,
            StrategyRemoteOutcome.CONFLICT,
        ).forEach { outcome ->
            assertFailsWith<IllegalArgumentException> {
                StrategyFallbackPlan(
                    remoteOutcomes = setOf(outcome),
                    operations = listOf(StrategyOperation.SERVE_LOCAL),
                    dataOrigin = StrategyDataOrigin.LOCAL,
                )
            }
        }
    }

    @Test
    fun fallbackPlanRequiresAnExplicitLocalServeOperation() {
        assertFailsWith<IllegalArgumentException> {
            StrategyFallbackPlan(
                remoteOutcomes = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                operations = listOf(StrategyOperation.READ_LOCAL),
                dataOrigin = StrategyDataOrigin.LOCAL,
            )
        }
    }

    @Test
    fun durableContinuationRequiresCapabilitiesForEveryOperation() {
        assertFailsWith<IllegalArgumentException> {
            StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.PULL_REMOTE),
                requiredCapabilities = emptySet(),
                dataOrigin = StrategyDataOrigin.REMOTE,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.RECONCILE),
                requiredCapabilities = setOf(StrategyProviderCapability.CONFLICT_STATE),
                dataOrigin = StrategyDataOrigin.NONE,
                consistency = StrategyConsistency.READ_YOUR_WRITES,
            )
        }
    }

    @Test
    fun durableLocalServingRequiresPersistedCacheEvidence() {
        assertFailsWith<IllegalArgumentException> {
            StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.SERVE_LOCAL),
                requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
                dataOrigin = StrategyDataOrigin.LOCAL,
                consistency = StrategyConsistency.EVENTUAL,
            )
        }
    }

    @Test
    fun exposedPlanAndProfileCollectionsCannotMutateInternalSnapshots() {
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan"),
            requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            effectiveProfileId = StrategyProfileId("offline"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ACCEPT_LOCAL),
            requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        )
        runCatching {
            (plan.operations as? MutableList<StrategyOperation>)?.clear()
        }
        runCatching {
            (plan.requiredCapabilities as? MutableSet<StrategyProviderCapability>)?.clear()
        }
        assertEquals(listOf(StrategyOperation.ACCEPT_LOCAL), plan.operations)
        assertEquals(setOf(StrategyProviderCapability.STORAGE), plan.requiredCapabilities)

        val profile = RemoteFirstStrategyProfile(
            id = StrategyProfileId("remote"),
            configurationVersion = StrategyConfigurationVersion(1),
            fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
        )
        runCatching {
            (profile.fallbackOn as? MutableSet<StrategyRemoteOutcome>)?.clear()
        }
        assertEquals(setOf(StrategyRemoteOutcome.UNAVAILABLE), profile.fallbackOn)

        val candidate = OfflineFirstStrategyProfile(
            id = StrategyProfileId("candidate"),
            configurationVersion = StrategyConfigurationVersion(1),
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(1),
            candidates = listOf(candidate),
        )
        runCatching {
            (adaptive.candidates as? MutableList<SynchronizationStrategyProfile>)?.clear()
        }
        assertEquals(listOf(candidate), adaptive.candidates)
    }
}
