package io.dataloom.runtime.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.AdaptiveStrategyProfile
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.HybridSource
import io.dataloom.api.strategy.HybridStrategyProfile
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StaleCachePolicy
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationRequest
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRejectionReason
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.SynchronizationStrategyProfile
import io.dataloom.api.strategy.UnknownConnectivityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltInSynchronizationStrategyEvaluatorTest {
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()

    @Test
    fun offlineFirstAcceptsAndQueuesBeforeConnectivityDeferral() {
        val result = evaluate(
            profile = offline(),
            direction = SynchronizationDirection.PUSH,
            evidence = evidence(connectivity = StrategyConnectivity.UNAVAILABLE),
        )

        assertEquals(StrategyDisposition.DEFER, result.plan.disposition)
        assertEquals(
            listOf(
                StrategyOperation.ACCEPT_LOCAL,
                StrategyOperation.ENQUEUE_DURABLE_WORK,
            ),
            result.plan.operations,
        )
        assertEquals(
            StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
            result.plan.deferralReason,
        )
        assertEquals(
            setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.QUEUE,
            ),
            result.plan.requiredCapabilities,
        )
    }

    @Test
    fun offlineFirstOnlinePlanReconcilesAfterDurableAdmission() {
        val result = evaluate(
            profile = offline(),
            direction = SynchronizationDirection.BIDIRECTIONAL,
            evidence = evidence(connectivity = StrategyConnectivity.AVAILABLE),
        )

        assertEquals(StrategyDisposition.EXECUTE, result.plan.disposition)
        assertTrue(
            result.plan.operations.indexOf(StrategyOperation.ACCEPT_LOCAL) <
                result.plan.operations.indexOf(StrategyOperation.PUSH_REMOTE),
        )
        assertTrue(
            result.plan.operations.indexOf(StrategyOperation.ENQUEUE_DURABLE_WORK) <
                result.plan.operations.indexOf(StrategyOperation.RECONCILE),
        )
    }

    @Test
    fun remoteFirstUsesOnlyConfiguredTypedFallback() {
        val withFallback = evaluate(
            profile = remote(fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE)),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.STALE,
            ),
        )
        val withoutFallback = evaluate(
            profile = remote(),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.STALE,
            ),
        )

        assertEquals(StrategyDisposition.EXECUTE, withFallback.plan.disposition)
        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), withFallback.plan.operations)
        assertEquals(StrategyDisposition.REJECT, withoutFallback.plan.disposition)
        assertEquals(
            StrategyRejectionReason.CONNECTIVITY_UNAVAILABLE,
            withoutFallback.plan.rejectionReason,
        )
    }

    @Test
    fun remoteFirstAdmitsFiniteFallbackAndResolvesItsCapabilitiesUpFront() {
        val result = evaluate(
            profile = remote(
                fallbackOn = setOf(
                    StrategyRemoteOutcome.UNAVAILABLE,
                    StrategyRemoteOutcome.TIMEOUT,
                ),
                persistRemoteResult = false,
            ),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.STALE,
            ),
        )

        assertEquals(
            listOf(StrategyOperation.PULL_REMOTE),
            result.plan.operations,
        )
        assertEquals(
            setOf(
                StrategyProviderCapability.TRANSPORT,
                StrategyProviderCapability.STORAGE,
            ),
            result.plan.requiredCapabilities,
        )
        assertEquals(
            setOf(
                StrategyRemoteOutcome.UNAVAILABLE,
                StrategyRemoteOutcome.TIMEOUT,
            ),
            result.plan.fallbackPlan?.remoteOutcomes,
        )
        assertEquals(
            listOf(StrategyOperation.SERVE_LOCAL),
            result.plan.fallbackPlan?.operations,
        )
    }

    @Test
    fun persistedRemoteFirstPullReadsCheckpointBeforeTransportAndPersistence() {
        val result = evaluate(
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("remote-persisted"),
                configurationVersion = version(),
                persistRemoteResult = true,
            ),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(connectivity = StrategyConnectivity.AVAILABLE),
        )

        assertEquals(
            listOf(
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            result.plan.operations,
        )
        assertEquals(
            setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
            result.plan.requiredCapabilities,
        )
    }

    @Test
    fun remoteFirstUnknownConnectivityPolicyIsExplicit() {
        val deferred = evaluate(
            profile = remote(unknown = UnknownConnectivityPolicy.DEFER),
            direction = SynchronizationDirection.PUSH,
            evidence = evidence(connectivity = StrategyConnectivity.UNKNOWN),
        )
        val attempted = evaluate(
            profile = remote(unknown = UnknownConnectivityPolicy.ATTEMPT_REMOTE),
            direction = SynchronizationDirection.PUSH,
            evidence = evidence(connectivity = StrategyConnectivity.UNKNOWN),
        )

        assertEquals(StrategyDisposition.DEFER, deferred.plan.disposition)
        assertEquals(
            StrategyDeferralReason.CONNECTIVITY_UNKNOWN,
            deferred.plan.deferralReason,
        )
        assertEquals(StrategyDisposition.EXECUTE, attempted.plan.disposition)
        assertTrue(StrategyOperation.PUSH_REMOTE in attempted.plan.operations)
    }

    @Test
    fun networkOnlyUnknownConnectivityPolicyIsExplicit() {
        // Network-only shares the same unknownConnectivityResult() dispatch
        // mechanism as remote-first and hybrid (both already covered above),
        // but had zero test coverage of its own despite NetworkOnlyStrategyProfile
        // forbidding DEFER at construction (see
        // networkOnlyCannotPromiseQueueBackedDeferral in StrategyContractsTest) —
        // only ATTEMPT_REMOTE and REJECT are constructible, and only
        // ATTEMPT_REMOTE was ever exercised through the evaluator.
        val rejected = evaluate(
            profile = network(unknown = UnknownConnectivityPolicy.REJECT),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(connectivity = StrategyConnectivity.UNKNOWN),
        )
        val attempted = evaluate(
            profile = network(unknown = UnknownConnectivityPolicy.ATTEMPT_REMOTE),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(connectivity = StrategyConnectivity.UNKNOWN),
        )

        assertEquals(StrategyDisposition.REJECT, rejected.plan.disposition)
        assertEquals(
            StrategyRejectionReason.CONNECTIVITY_UNKNOWN,
            rejected.plan.rejectionReason,
        )
        assertEquals(StrategyDisposition.EXECUTE, attempted.plan.disposition)
        assertTrue(StrategyOperation.PULL_REMOTE in attempted.plan.operations)
    }

    @Test
    fun cacheFirstFreshStaleAndMissingDecisionsAreDistinct() {
        val profile = cache()
        val fresh = evaluate(
            profile,
            SynchronizationDirection.PULL,
            evidence(cacheState = StrategyCacheState.FRESH),
        )
        val stale = evaluate(
            profile,
            SynchronizationDirection.PULL,
            evidence(cacheState = StrategyCacheState.STALE),
        )
        val missingOnline = evaluate(
            profile,
            SynchronizationDirection.PULL,
            evidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
        )
        val missingOffline = evaluate(
            profile,
            SynchronizationDirection.PULL,
            evidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
        )

        assertEquals(StrategyDisposition.EXECUTE, fresh.plan.disposition)
        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), fresh.plan.operations)
        assertEquals(StrategyDisposition.SERVE_AND_REFRESH, stale.plan.disposition)
        assertTrue(StrategyOperation.SCHEDULE_REFRESH in stale.plan.operations)
        assertEquals(StrategyDisposition.EXECUTE, missingOnline.plan.disposition)
        assertTrue(StrategyOperation.PULL_REMOTE in missingOnline.plan.operations)
        assertEquals(StrategyDisposition.REJECT, missingOffline.plan.disposition)
        assertEquals(StrategyRejectionReason.CACHE_MISS, missingOffline.plan.rejectionReason)
    }

    @Test
    fun cacheFirstRejectPolicyNeverServesStaleState() {
        val result = evaluate(
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("cache-strict"),
                configurationVersion = version(),
                staleCachePolicy = StaleCachePolicy.REJECT,
            ),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(cacheState = StrategyCacheState.STALE),
        )

        assertEquals(StrategyDisposition.REJECT, result.plan.disposition)
        assertFalse(StrategyOperation.SERVE_LOCAL in result.plan.operations)
        assertEquals(
            StrategyRejectionReason.STALE_CACHE_NOT_ALLOWED,
            result.plan.rejectionReason,
        )
    }

    @Test
    fun networkOnlyNeverRequiresOrInvokesLocalCapabilitiesAcrossDirections() {
        SynchronizationDirection.entries.forEach { direction ->
            val result = evaluate(
                profile = network(),
                direction = direction,
                evidence = evidence(connectivity = StrategyConnectivity.AVAILABLE),
            )

            assertEquals(StrategyDisposition.EXECUTE, result.plan.disposition)
            assertEquals(
                setOf(StrategyProviderCapability.TRANSPORT),
                result.plan.requiredCapabilities,
            )
            assertTrue(
                result.plan.operations.all {
                    it == StrategyOperation.PUSH_REMOTE ||
                        it == StrategyOperation.PULL_REMOTE
                },
            )
        }
    }

    @Test
    fun networkOnlyUnavailableIsTypedRejectionWithNoOperations() {
        val result = evaluate(
            profile = network(),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(connectivity = StrategyConnectivity.UNAVAILABLE),
        )

        assertEquals(StrategyDisposition.REJECT, result.plan.disposition)
        assertEquals(
            StrategyRejectionReason.CONNECTIVITY_UNAVAILABLE,
            result.plan.rejectionReason,
        )
        assertTrue(result.plan.operations.isEmpty())
        assertTrue(result.plan.requiredCapabilities.isEmpty())
    }

    @Test
    fun hybridRemotePrimaryUsesDeclaredLocalFallbackAndReconciliation() {
        val profile = hybridRemote()
        val primary = evaluate(
            profile,
            SynchronizationDirection.PULL,
            evidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.STALE,
            ),
        )
        val fallback = evaluate(
            profile,
            SynchronizationDirection.PULL,
            evidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.STALE,
            ),
        )

        assertTrue(StrategyOperation.PULL_REMOTE in primary.plan.operations)
        assertEquals(StrategyDisposition.SERVE_AND_REFRESH, fallback.plan.disposition)
        assertTrue(StrategyOperation.SERVE_LOCAL in fallback.plan.operations)
        assertTrue(StrategyOperation.ENQUEUE_DURABLE_WORK in fallback.plan.operations)
        assertTrue(StrategyOperation.RECONCILE in fallback.plan.operations)
    }

    @Test
    fun hybridUnknownConnectivityPolicyDoesNotImproviseFallback() {
        val deferred = evaluate(
            profile = hybridRemote(
                unknownConnectivityPolicy = UnknownConnectivityPolicy.DEFER,
            ),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(
                connectivity = StrategyConnectivity.UNKNOWN,
                cacheState = StrategyCacheState.FRESH,
            ),
        )

        assertEquals(StrategyDisposition.DEFER, deferred.plan.disposition)
        assertEquals(
            StrategyDeferralReason.CONNECTIVITY_UNKNOWN,
            deferred.plan.deferralReason,
        )
        assertFalse(StrategyOperation.SERVE_LOCAL in deferred.plan.operations)
    }

    @Test
    fun adaptiveSelectionIsDeterministicAndRecordsRequestedAndEffectiveStrategy() {
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = version(),
            candidates = listOf(network(), remote(), cache(), offline(), hybridRemote()),
            safeDefaultProfileId = StrategyProfileId("offline"),
        )

        val pending = evaluate(
            adaptive,
            SynchronizationDirection.PUSH,
            evidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                hasPendingLocalChanges = true,
            ),
        )
        val cached = evaluate(
            adaptive,
            SynchronizationDirection.PULL,
            evidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.FRESH,
            ),
        )
        val online = evaluate(
            adaptive,
            SynchronizationDirection.PULL,
            evidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
        )
        val unknown = evaluate(
            adaptive,
            SynchronizationDirection.PULL,
            evidence(
                connectivity = StrategyConnectivity.UNKNOWN,
                cacheState = StrategyCacheState.UNKNOWN,
            ),
        )

        assertEquals(BuiltInSynchronizationStrategy.ADAPTIVE, pending.plan.requestedStrategy)
        assertEquals(BuiltInSynchronizationStrategy.OFFLINE_FIRST, pending.plan.effectiveStrategy)
        assertEquals(BuiltInSynchronizationStrategy.CACHE_FIRST, cached.plan.effectiveStrategy)
        assertEquals(BuiltInSynchronizationStrategy.REMOTE_FIRST, online.plan.effectiveStrategy)
        assertEquals(BuiltInSynchronizationStrategy.OFFLINE_FIRST, unknown.plan.effectiveStrategy)
        assertTrue(pending.reasonCodes.first().startsWith("adaptive.selected."))
    }

    @Test
    fun adaptiveLimitedConnectivityPrefersHybridOverCacheOverOffline() {
        // #102 acceptance: "Adaptive: bounded deterministic selection among
        // configured concrete profiles using ... connectivity ..."
        // LIMITED connectivity had zero test coverage anywhere in this suite
        // despite selectAdaptiveCandidate() giving it its own distinct
        // preference order (HYBRID > CACHE_FIRST > OFFLINE_FIRST) — neither
        // the same as AVAILABLE's (REMOTE_FIRST-led) nor UNAVAILABLE's
        // (OFFLINE_FIRST-led) ordering.
        val allThreeCandidates = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive-limited"),
            configurationVersion = version(),
            candidates = listOf(hybridRemote(), cache(), offline()),
        )
        val hybridSelected = evaluate(
            allThreeCandidates,
            SynchronizationDirection.PULL,
            evidence(connectivity = StrategyConnectivity.LIMITED),
        )

        val cacheAndOfflineOnly = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive-limited-no-hybrid"),
            configurationVersion = version(),
            candidates = listOf(cache(), offline()),
        )
        val cacheSelected = evaluate(
            cacheAndOfflineOnly,
            SynchronizationDirection.PULL,
            evidence(connectivity = StrategyConnectivity.LIMITED),
        )

        val offlineOnly = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive-limited-offline-only"),
            configurationVersion = version(),
            candidates = listOf(offline()),
        )
        val offlineSelected = evaluate(
            offlineOnly,
            SynchronizationDirection.PULL,
            evidence(connectivity = StrategyConnectivity.LIMITED),
        )

        assertEquals(BuiltInSynchronizationStrategy.HYBRID, hybridSelected.plan.effectiveStrategy)
        assertEquals(BuiltInSynchronizationStrategy.CACHE_FIRST, cacheSelected.plan.effectiveStrategy)
        assertEquals(BuiltInSynchronizationStrategy.OFFLINE_FIRST, offlineSelected.plan.effectiveStrategy)
    }

    @Test
    fun adaptiveWithoutEligibleOrSafeDefaultRejectsExplicitly() {
        val result = evaluate(
            profile = AdaptiveStrategyProfile(
                id = StrategyProfileId("adaptive-network-only"),
                configurationVersion = version(),
                candidates = listOf(network()),
            ),
            direction = SynchronizationDirection.PULL,
            evidence = evidence(connectivity = StrategyConnectivity.UNAVAILABLE),
        )

        assertEquals(StrategyDisposition.REJECT, result.plan.disposition)
        assertEquals(
            StrategyRejectionReason.NO_ELIGIBLE_ADAPTIVE_PROFILE,
            result.plan.rejectionReason,
        )
        assertEquals(BuiltInSynchronizationStrategy.ADAPTIVE, result.plan.requestedStrategy)
        assertEquals(BuiltInSynchronizationStrategy.NETWORK_ONLY, result.plan.effectiveStrategy)
    }

    @Test
    fun everyConcreteProfileEvaluatesEveryDirectionWithoutImplicitMutation() {
        val profiles = listOf(
            offline(),
            remote(),
            cache(),
            network(),
            hybridRemote(),
        )

        profiles.forEach { profile ->
            SynchronizationDirection.entries.forEach { direction ->
                val result = evaluate(
                    profile = profile,
                    direction = direction,
                    evidence = evidence(
                        connectivity = StrategyConnectivity.AVAILABLE,
                        cacheState = StrategyCacheState.FRESH,
                    ),
                )

                assertEquals(profile.strategy, result.plan.requestedStrategy)
                assertEquals(profile.strategy, result.plan.effectiveStrategy)
                assertEquals(profile.id, result.plan.effectiveProfileId)
                assertEquals(profile.configurationVersion, result.plan.configurationVersion)
                assertEquals(direction, result.plan.direction)
            }
        }
    }

    private fun evaluate(
        profile: SynchronizationStrategyProfile,
        direction: SynchronizationDirection,
        evidence: StrategyRuntimeEvidence,
    ) = evaluator.evaluate(
        StrategyEvaluationRequest(
            decisionId = StrategyDecisionId("decision"),
            planId = StrategyPlanId("plan"),
            profile = profile,
            direction = direction,
            mode = SynchronizationMode.DELTA,
            evidence = evidence,
        ),
    )

    private fun evidence(
        connectivity: StrategyConnectivity = StrategyConnectivity.NOT_EVALUATED,
        cacheState: StrategyCacheState = StrategyCacheState.NOT_EVALUATED,
        hasPendingLocalChanges: Boolean = false,
    ): StrategyRuntimeEvidence = StrategyRuntimeEvidence(
        connectivity = connectivity,
        cacheState = cacheState,
        storageHealth = StrategyProviderHealth.HEALTHY,
        transportHealth = StrategyProviderHealth.HEALTHY,
        queueHealth = StrategyProviderHealth.HEALTHY,
        hasPendingLocalChanges = hasPendingLocalChanges,
        isBackgroundExecutionAvailable = true,
    )

    private fun version(): StrategyConfigurationVersion = StrategyConfigurationVersion(1)

    private fun offline(): OfflineFirstStrategyProfile = OfflineFirstStrategyProfile(
        id = StrategyProfileId("offline"),
        configurationVersion = version(),
    )

    private fun remote(
        fallbackOn: Set<StrategyRemoteOutcome> = emptySet(),
        unknown: UnknownConnectivityPolicy = UnknownConnectivityPolicy.ATTEMPT_REMOTE,
        persistRemoteResult: Boolean = true,
    ): RemoteFirstStrategyProfile = RemoteFirstStrategyProfile(
        id = StrategyProfileId("remote"),
        configurationVersion = version(),
        fallbackOn = fallbackOn,
        unknownConnectivityPolicy = unknown,
        persistRemoteResult = persistRemoteResult,
    )

    private fun cache(): CacheFirstStrategyProfile = CacheFirstStrategyProfile(
        id = StrategyProfileId("cache"),
        configurationVersion = version(),
    )

    private fun network(
        unknown: UnknownConnectivityPolicy = UnknownConnectivityPolicy.ATTEMPT_REMOTE,
    ): NetworkOnlyStrategyProfile = NetworkOnlyStrategyProfile(
        id = StrategyProfileId("network"),
        configurationVersion = version(),
        unknownConnectivityPolicy = unknown,
    )

    private fun hybridRemote(
        unknownConnectivityPolicy: UnknownConnectivityPolicy =
            UnknownConnectivityPolicy.ATTEMPT_REMOTE,
    ): HybridStrategyProfile = HybridStrategyProfile(
        id = StrategyProfileId("hybrid"),
        configurationVersion = version(),
        primarySource = HybridSource.REMOTE,
        fallbackSource = HybridSource.LOCAL,
        unknownConnectivityPolicy = unknownConnectivityPolicy,
    )
}
