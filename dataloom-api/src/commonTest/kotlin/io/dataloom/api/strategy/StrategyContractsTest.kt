package io.dataloom.api.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class StrategyContractsTest {

    @Test
    fun builtInStrategySetIsExhaustiveForV1() {
        assertEquals(
            setOf(
                "OFFLINE_FIRST",
                "REMOTE_FIRST",
                "CACHE_FIRST",
                "NETWORK_ONLY",
                "HYBRID",
                "ADAPTIVE",
            ),
            BuiltInSynchronizationStrategy.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun identityTypesRejectInvalidValues() {
        assertFailsWith<IllegalArgumentException> { StrategyProfileId(" ") }
        assertFailsWith<IllegalArgumentException> { StrategyConfigurationVersion(0) }
        assertFailsWith<IllegalArgumentException> { StrategyDecisionId("") }
        assertFailsWith<IllegalArgumentException> { StrategyPlanId("\t") }
    }

    @Test
    fun remoteFirstDefensivelyCopiesFallbackAllowlist() {
        val fallback = mutableSetOf(StrategyRemoteOutcome.UNAVAILABLE)
        val profile = RemoteFirstStrategyProfile(
            id = StrategyProfileId("remote"),
            configurationVersion = StrategyConfigurationVersion(1),
            fallbackOn = fallback,
        )

        fallback += StrategyRemoteOutcome.TIMEOUT

        assertEquals(setOf(StrategyRemoteOutcome.UNAVAILABLE), profile.fallbackOn)
        assertNotSame(fallback, profile.fallbackOn)
    }

    @Test
    fun remoteFirstCannotHideProtectedFailureClasses() {
        listOf(
            StrategyRemoteOutcome.CANCELLED,
            StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
            StrategyRemoteOutcome.AUTHORIZATION_FAILURE,
            StrategyRemoteOutcome.VALIDATION_FAILURE,
            StrategyRemoteOutcome.INTEGRITY_FAILURE,
            StrategyRemoteOutcome.CONFLICT,
        ).forEach { protectedOutcome ->
            assertFailsWith<IllegalArgumentException> {
                RemoteFirstStrategyProfile(
                    id = StrategyProfileId("unsafe"),
                    configurationVersion = StrategyConfigurationVersion(1),
                    fallbackOn = setOf(protectedOutcome),
                )
            }
        }
    }

    @Test
    fun fallbackPlanDefensivelyCopiesItsFiniteLocalBranch() {
        val outcomes = mutableSetOf(StrategyRemoteOutcome.UNAVAILABLE)
        val operations = mutableListOf(StrategyOperation.SERVE_LOCAL)
        val fallback = StrategyFallbackPlan(
            remoteOutcomes = outcomes,
            operations = operations,
            dataOrigin = StrategyDataOrigin.LOCAL,
        )

        outcomes += StrategyRemoteOutcome.TIMEOUT
        operations += StrategyOperation.PULL_REMOTE

        assertEquals(setOf(StrategyRemoteOutcome.UNAVAILABLE), fallback.remoteOutcomes)
        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), fallback.operations)
        assertNotSame(outcomes, fallback.remoteOutcomes)
        assertNotSame(operations, fallback.operations)
    }

    @Test
    fun fallbackPlanRejectsRemoteOperationsAndRemoteOrigin() {
        assertFailsWith<IllegalArgumentException> {
            StrategyFallbackPlan(
                remoteOutcomes = setOf(StrategyRemoteOutcome.TIMEOUT),
                operations = listOf(StrategyOperation.PULL_REMOTE),
                dataOrigin = StrategyDataOrigin.LOCAL,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyFallbackPlan(
                remoteOutcomes = setOf(StrategyRemoteOutcome.TIMEOUT),
                operations = listOf(StrategyOperation.SERVE_LOCAL),
                dataOrigin = StrategyDataOrigin.REMOTE,
            )
        }
    }

    @Test
    fun localFallbackAvailabilityStatesAreUnambiguous() {
        StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH)
        StrategyLocalFallbackResult.Available(StrategyCacheState.STALE)
        StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING)

        assertFailsWith<IllegalArgumentException> {
            StrategyLocalFallbackResult.Available(StrategyCacheState.MISSING)
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyLocalFallbackResult.Unavailable(StrategyCacheState.FRESH)
        }
    }

    @Test
    fun hybridRequiresDifferentPrimaryAndFallbackSources() {
        assertFailsWith<IllegalArgumentException> {
            HybridStrategyProfile(
                id = StrategyProfileId("invalid-hybrid"),
                configurationVersion = StrategyConfigurationVersion(1),
                primarySource = HybridSource.LOCAL,
                fallbackSource = HybridSource.LOCAL,
            )
        }
    }

    @Test
    fun adaptiveRequiresFiniteUniqueConcreteCandidates() {
        val offline = OfflineFirstStrategyProfile(
            id = StrategyProfileId("offline"),
            configurationVersion = StrategyConfigurationVersion(1),
        )

        assertFailsWith<IllegalArgumentException> {
            AdaptiveStrategyProfile(
                id = StrategyProfileId("empty"),
                configurationVersion = StrategyConfigurationVersion(1),
                candidates = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AdaptiveStrategyProfile(
                id = StrategyProfileId("duplicate"),
                configurationVersion = StrategyConfigurationVersion(1),
                candidates = listOf(offline, offline.copy()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AdaptiveStrategyProfile(
                id = StrategyProfileId("unknown-default"),
                configurationVersion = StrategyConfigurationVersion(1),
                candidates = listOf(offline),
                safeDefaultProfileId = StrategyProfileId("missing"),
            )
        }
    }

    @Test
    fun strategyExecutionPlanEnforcesNetworkOnlyIsolation() {
        assertFailsWith<IllegalArgumentException> {
            StrategyExecutionPlan(
                id = StrategyPlanId("plan"),
                requestedStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
                effectiveProfileId = StrategyProfileId("network"),
                effectiveStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
                configurationVersion = StrategyConfigurationVersion(1),
                direction = io.dataloom.api.model.SynchronizationDirection.PULL,
                mode = io.dataloom.api.model.SynchronizationMode.DELTA,
                disposition = StrategyDisposition.EXECUTE,
                operations = listOf(StrategyOperation.PULL_REMOTE),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.TRANSPORT,
                    StrategyProviderCapability.STORAGE,
                ),
                dataOrigin = StrategyDataOrigin.REMOTE,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyExecutionPlan(
                id = StrategyPlanId("plan"),
                requestedStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
                effectiveProfileId = StrategyProfileId("network"),
                effectiveStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
                configurationVersion = StrategyConfigurationVersion(1),
                direction = io.dataloom.api.model.SynchronizationDirection.PULL,
                mode = io.dataloom.api.model.SynchronizationMode.DELTA,
                disposition = StrategyDisposition.EXECUTE,
                operations = listOf(StrategyOperation.SERVE_LOCAL),
                requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
                dataOrigin = StrategyDataOrigin.LOCAL,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            )
        }
    }

    @Test
    fun networkOnlyCannotPromiseQueueBackedDeferral() {
        assertFailsWith<IllegalArgumentException> {
            NetworkOnlyStrategyProfile(
                id = StrategyProfileId("network-defer"),
                configurationVersion = StrategyConfigurationVersion(1),
                unknownConnectivityPolicy = UnknownConnectivityPolicy.DEFER,
            )
        }
    }

    @Test
    fun evaluationAndAdaptiveProfilesDefensivelyCopyCollections() {
        val candidates = mutableListOf<SynchronizationStrategyProfile>(
            OfflineFirstStrategyProfile(
                id = StrategyProfileId("offline"),
                configurationVersion = StrategyConfigurationVersion(1),
            ),
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(1),
            candidates = candidates,
        )
        candidates.clear()

        assertEquals(1, adaptive.candidates.size)

        val reasons = mutableListOf("policy.reason")
        val result = StrategyEvaluationResult(
            decisionId = StrategyDecisionId("decision"),
            plan = StrategyExecutionPlan(
                id = StrategyPlanId("plan"),
                requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
                effectiveProfileId = StrategyProfileId("offline"),
                effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
                configurationVersion = StrategyConfigurationVersion(1),
                direction = io.dataloom.api.model.SynchronizationDirection.PUSH,
                mode = io.dataloom.api.model.SynchronizationMode.DELTA,
                disposition = StrategyDisposition.DEFER,
                operations = listOf(StrategyOperation.ACCEPT_LOCAL),
                requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
                dataOrigin = StrategyDataOrigin.NONE,
                consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
                deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
            ),
            reasonCodes = reasons,
        )
        reasons.clear()

        assertEquals(listOf("policy.reason"), result.reasonCodes)
    }
}
