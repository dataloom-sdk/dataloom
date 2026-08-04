package io.dataloom.runtime.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.AdaptiveStrategyProfile
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyEvaluationRequest
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.UnknownConnectivityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrategyDurableContinuationEvaluationTest {

    private val evaluator = BuiltInSynchronizationStrategyEvaluator()

    @Test
    fun offlineFirstFreezesRemoteReconciliationContinuation() {
        val result = evaluate(
            OfflineFirstStrategyProfile(
                id = StrategyProfileId("offline"),
                configurationVersion = StrategyConfigurationVersion(1L),
            ),
            connectivity = StrategyConnectivity.UNAVAILABLE,
        )
        val continuation = assertNotNull(result.plan.durableContinuation)
        assertEquals(
            listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.RECONCILE,
            ),
            continuation.operations,
        )
    }

    @Test
    fun durableCacheRefreshFreezesPullAndPersistence() {
        val result = evaluate(
            CacheFirstStrategyProfile(
                id = StrategyProfileId("cache"),
                configurationVersion = StrategyConfigurationVersion(2L),
                refreshOnFreshHit = true,
                requireDurableRefresh = true,
            ),
            direction = SynchronizationDirection.PULL,
            connectivity = StrategyConnectivity.AVAILABLE,
            cacheState = StrategyCacheState.FRESH,
        )
        val continuation = assertNotNull(result.plan.durableContinuation)
        assertEquals(
            listOf(
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            continuation.operations,
        )
    }

    @Test
    fun remoteFirstUnknownConnectivityFreezesTypedFallbackBranch() {
        val result = evaluate(
            RemoteFirstStrategyProfile(
                id = StrategyProfileId("remote"),
                configurationVersion = StrategyConfigurationVersion(3L),
                fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                unknownConnectivityPolicy = UnknownConnectivityPolicy.DEFER,
            ),
            direction = SynchronizationDirection.PULL,
            connectivity = StrategyConnectivity.UNKNOWN,
            cacheState = StrategyCacheState.STALE,
        )
        val continuation = assertNotNull(result.plan.durableContinuation)
        assertTrue(StrategyOperation.PULL_REMOTE in continuation.operations)
        assertEquals(
            setOf(StrategyRemoteOutcome.UNAVAILABLE),
            assertNotNull(continuation.fallbackPlan).remoteOutcomes,
        )
        assertEquals(StrategyCacheState.STALE, continuation.evaluatedCacheState)
    }

    @Test
    fun adaptiveSelectionFreezesConcreteCandidateContinuation() {
        val offline = OfflineFirstStrategyProfile(
            id = StrategyProfileId("offline-candidate"),
            configurationVersion = StrategyConfigurationVersion(4L),
        )
        val result = evaluate(
            AdaptiveStrategyProfile(
                id = StrategyProfileId("adaptive"),
                configurationVersion = StrategyConfigurationVersion(5L),
                candidates = listOf(offline),
                safeDefaultProfileId = offline.id,
            ),
            connectivity = StrategyConnectivity.UNAVAILABLE,
        )
        assertEquals(offline.id, result.plan.effectiveProfileId)
        assertNotNull(result.plan.durableContinuation)
    }

    private fun evaluate(
        profile: io.dataloom.api.strategy.SynchronizationStrategyProfile,
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        connectivity: StrategyConnectivity,
        cacheState: StrategyCacheState = StrategyCacheState.MISSING,
    ) = evaluator.evaluate(
        StrategyEvaluationRequest(
            decisionId = StrategyDecisionId("decision"),
            planId = StrategyPlanId("plan"),
            profile = profile,
            direction = direction,
            mode = SynchronizationMode.DELTA,
            evidence = StrategyRuntimeEvidence(
                connectivity = connectivity,
                cacheState = cacheState,
                hasPendingLocalChanges = true,
            ),
        ),
    )
}
