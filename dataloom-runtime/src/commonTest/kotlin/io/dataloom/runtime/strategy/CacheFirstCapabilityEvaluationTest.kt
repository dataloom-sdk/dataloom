package io.dataloom.runtime.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.AdaptiveStrategyProfile
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StaleCachePolicy
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyEvaluationRequest
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheFirstCapabilityEvaluationTest {
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()

    @Test
    fun freshCacheServingRequiresExplicitCacheAccessCapability() {
        val result = evaluate(
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("cache-fresh"),
                configurationVersion = StrategyConfigurationVersion(1),
            ),
            evidence = evidence(cacheState = StrategyCacheState.FRESH),
        )

        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), result.plan.operations)
        assertEquals(
            setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
            ),
            result.plan.requiredCapabilities,
        )
    }

    @Test
    fun staleServingAndDurableRefreshPreserveCacheQueueAndSchedulerCapabilities() {
        val result = evaluate(
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("cache-stale-refresh"),
                configurationVersion = StrategyConfigurationVersion(1),
                staleCachePolicy = StaleCachePolicy.SERVE_STALE_AND_REFRESH,
                requireDurableRefresh = true,
            ),
            evidence = evidence(cacheState = StrategyCacheState.STALE),
        )

        assertTrue(StrategyOperation.SERVE_LOCAL in result.plan.operations)
        assertTrue(StrategyOperation.SCHEDULE_REFRESH in result.plan.operations)
        assertEquals(
            setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
                StrategyProviderCapability.QUEUE,
                StrategyProviderCapability.SCHEDULER,
            ),
            result.plan.requiredCapabilities,
        )
    }

    @Test
    fun remoteCacheMissDoesNotRequireCacheAccessProvider() {
        val result = evaluate(
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("cache-miss"),
                configurationVersion = StrategyConfigurationVersion(1),
            ),
            evidence = evidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
        )

        assertTrue(StrategyOperation.PULL_REMOTE in result.plan.operations)
        assertFalse(
            StrategyProviderCapability.CACHE_ACCESS in result.plan.requiredCapabilities,
        )
    }

    @Test
    fun remoteFirstFallbackDoesNotAcquireCacheFirstContract() {
        val result = evaluate(
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("remote-fallback"),
                configurationVersion = StrategyConfigurationVersion(1),
                fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                persistRemoteResult = false,
            ),
            evidence = evidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.FRESH,
            ),
        )

        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), result.plan.operations)
        assertEquals(
            setOf(StrategyProviderCapability.STORAGE),
            result.plan.requiredCapabilities,
        )
    }

    @Test
    fun adaptiveSelectionOfCacheFirstPreservesCacheAccessCapability() {
        val cache = CacheFirstStrategyProfile(
            id = StrategyProfileId("adaptive-cache"),
            configurationVersion = StrategyConfigurationVersion(4),
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(4),
            candidates = listOf(
                NetworkOnlyStrategyProfile(
                    id = StrategyProfileId("adaptive-network"),
                    configurationVersion = StrategyConfigurationVersion(4),
                ),
                cache,
            ),
            safeDefaultProfileId = cache.id,
        )
        val result = evaluate(
            profile = adaptive,
            evidence = evidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.FRESH,
            ),
        )

        assertEquals(cache.id, result.plan.effectiveProfileId)
        assertTrue(
            StrategyProviderCapability.CACHE_ACCESS in result.plan.requiredCapabilities,
        )
    }

    private fun evaluate(
        profile: io.dataloom.api.strategy.SynchronizationStrategyProfile,
        evidence: StrategyRuntimeEvidence,
    ) = evaluator.evaluate(
        StrategyEvaluationRequest(
            decisionId = StrategyDecisionId("decision"),
            planId = StrategyPlanId("plan"),
            profile = profile,
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            evidence = evidence,
        ),
    )

    private fun evidence(
        connectivity: StrategyConnectivity = StrategyConnectivity.AVAILABLE,
        cacheState: StrategyCacheState,
    ): StrategyRuntimeEvidence = StrategyRuntimeEvidence(
        connectivity = connectivity,
        cacheState = cacheState,
    )
}
