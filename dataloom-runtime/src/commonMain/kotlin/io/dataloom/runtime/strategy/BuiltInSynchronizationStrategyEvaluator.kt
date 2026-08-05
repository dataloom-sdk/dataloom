package io.dataloom.runtime.strategy

import io.dataloom.api.model.SynchronizationDirection
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
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationRequest
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyFallbackPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRejectionReason
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.SynchronizationStrategyEvaluator
import io.dataloom.api.strategy.SynchronizationStrategyProfile
import io.dataloom.api.strategy.UnknownConnectivityPolicy

/**
 * Deterministic evaluator for all six DataLoom V1 synchronization strategies.
 *
 * Evaluation is pure and bounded. It performs no provider call, I/O, clock
 * read, identifier generation, random selection, or mutation. The caller
 * supplies immutable evidence and correlation identifiers.
 */
public class BuiltInSynchronizationStrategyEvaluator : SynchronizationStrategyEvaluator {

    override fun evaluate(request: StrategyEvaluationRequest): StrategyEvaluationResult {
        val profile = request.profile
        return if (profile is AdaptiveStrategyProfile) {
            evaluateAdaptive(request, profile)
        } else {
            evaluateConcrete(
                request = request,
                profile = profile,
                requestedStrategy = profile.strategy,
                leadingReasons = emptyList(),
            )
        }
    }

    private fun evaluateAdaptive(
        request: StrategyEvaluationRequest,
        profile: AdaptiveStrategyProfile,
    ): StrategyEvaluationResult {
        val selected = selectAdaptiveCandidate(profile, request)
        if (selected == null) {
            return result(
                request = request,
                requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
                profile = fallbackConcreteIdentity(profile),
                disposition = StrategyDisposition.REJECT,
                operations = emptyList(),
                origin = StrategyDataOrigin.NONE,
                consistency = StrategyConsistency.EVENTUAL,
                rejectionReason = StrategyRejectionReason.NO_ELIGIBLE_ADAPTIVE_PROFILE,
                reasons = listOf("adaptive.no-eligible-profile"),
            )
        }

        return evaluateConcrete(
            request = request,
            profile = selected,
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            leadingReasons = listOf(
                "adaptive.selected.${selected.strategy.name.lowercase()}",
                "adaptive.profile.${selected.id.value}",
            ),
        )
    }

    private fun selectAdaptiveCandidate(
        profile: AdaptiveStrategyProfile,
        request: StrategyEvaluationRequest,
    ): SynchronizationStrategyProfile? {
        val candidates = profile.candidates
        val evidence = request.evidence

        fun candidate(strategy: BuiltInSynchronizationStrategy): SynchronizationStrategyProfile? =
            candidates.firstOrNull { it.strategy == strategy }

        if (evidence.hasPendingLocalChanges) {
            candidate(BuiltInSynchronizationStrategy.OFFLINE_FIRST)?.let { return it }
        }

        if (evidence.cacheState == StrategyCacheState.FRESH) {
            candidate(BuiltInSynchronizationStrategy.CACHE_FIRST)?.let { return it }
        }

        when (evidence.connectivity) {
            StrategyConnectivity.AVAILABLE -> {
                if (evidence.transportHealth != StrategyProviderHealth.UNAVAILABLE) {
                    candidate(BuiltInSynchronizationStrategy.REMOTE_FIRST)?.let { return it }
                    candidates.firstOrNull {
                        it is HybridStrategyProfile && it.primarySource == HybridSource.REMOTE
                    }?.let { return it }
                    candidate(BuiltInSynchronizationStrategy.NETWORK_ONLY)?.let { return it }
                }
                candidate(BuiltInSynchronizationStrategy.CACHE_FIRST)?.let { return it }
                candidate(BuiltInSynchronizationStrategy.OFFLINE_FIRST)?.let { return it }
            }

            StrategyConnectivity.LIMITED -> {
                candidate(BuiltInSynchronizationStrategy.HYBRID)?.let { return it }
                candidate(BuiltInSynchronizationStrategy.CACHE_FIRST)?.let { return it }
                candidate(BuiltInSynchronizationStrategy.OFFLINE_FIRST)?.let { return it }
            }

            StrategyConnectivity.UNAVAILABLE -> {
                candidate(BuiltInSynchronizationStrategy.OFFLINE_FIRST)?.let { return it }
                if (evidence.cacheState == StrategyCacheState.STALE) {
                    candidate(BuiltInSynchronizationStrategy.CACHE_FIRST)?.let { return it }
                }
                candidates.firstOrNull {
                    it is HybridStrategyProfile && it.primarySource == HybridSource.LOCAL
                }?.let { return it }
            }

            StrategyConnectivity.UNKNOWN,
            StrategyConnectivity.NOT_EVALUATED,
            -> Unit
        }

        return profile.safeDefaultProfileId?.let { defaultId ->
            candidates.firstOrNull { it.id == defaultId }
        }
    }

    private fun fallbackConcreteIdentity(
        profile: AdaptiveStrategyProfile,
    ): SynchronizationStrategyProfile = profile.candidates.first()

    private fun evaluateConcrete(
        request: StrategyEvaluationRequest,
        profile: SynchronizationStrategyProfile,
        requestedStrategy: BuiltInSynchronizationStrategy,
        leadingReasons: List<String>,
    ): StrategyEvaluationResult = when (profile) {
        is OfflineFirstStrategyProfile ->
            evaluateOfflineFirst(request, profile, requestedStrategy, leadingReasons)
        is RemoteFirstStrategyProfile ->
            evaluateRemoteFirst(request, profile, requestedStrategy, leadingReasons)
        is CacheFirstStrategyProfile ->
            evaluateCacheFirst(request, profile, requestedStrategy, leadingReasons)
        is NetworkOnlyStrategyProfile ->
            evaluateNetworkOnly(request, profile, requestedStrategy, leadingReasons)
        is HybridStrategyProfile ->
            evaluateHybrid(request, profile, requestedStrategy, leadingReasons)
        is AdaptiveStrategyProfile ->
            error("Nested adaptive profiles are rejected by AdaptiveStrategyProfile.")
    }

    private fun evaluateOfflineFirst(
        request: StrategyEvaluationRequest,
        profile: OfflineFirstStrategyProfile,
        requestedStrategy: BuiltInSynchronizationStrategy,
        leadingReasons: List<String>,
    ): StrategyEvaluationResult {
        val localOperations = mutableListOf<StrategyOperation>()
        when (request.direction) {
            SynchronizationDirection.PUSH -> localOperations += StrategyOperation.ACCEPT_LOCAL
            SynchronizationDirection.PULL -> {
                if (isLocalDataAvailable(request.evidence.cacheState)) {
                    localOperations += StrategyOperation.SERVE_LOCAL
                }
            }
            SynchronizationDirection.BIDIRECTIONAL -> {
                localOperations += StrategyOperation.ACCEPT_LOCAL
                if (isLocalDataAvailable(request.evidence.cacheState)) {
                    localOperations += StrategyOperation.SERVE_LOCAL
                }
            }
        }
        if (profile.requireDurableQueue) {
            localOperations += StrategyOperation.ENQUEUE_DURABLE_WORK
        }

        if (request.evidence.connectivity == StrategyConnectivity.AVAILABLE) {
            localOperations += remoteOperations(request.direction, persistRemote = true)
            if (profile.reconcileWhenOnline) {
                localOperations += StrategyOperation.RECONCILE
            }
            return result(
                request,
                requestedStrategy,
                profile,
                StrategyDisposition.EXECUTE,
                localOperations,
                originForOperations(request.direction, localOperations),
                StrategyConsistency.LOCAL_AUTHORITATIVE,
                reasons = leadingReasons + "offline-first.local-accepted.remote-available",
            )
        }

        val deferralReason = when (request.evidence.connectivity) {
            StrategyConnectivity.UNAVAILABLE,
            StrategyConnectivity.LIMITED,
            -> StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE
            StrategyConnectivity.UNKNOWN,
            StrategyConnectivity.NOT_EVALUATED,
            -> StrategyDeferralReason.CONNECTIVITY_UNKNOWN
            StrategyConnectivity.AVAILABLE -> error("Handled above.")
        }
        return result(
            request,
            requestedStrategy,
            profile,
            StrategyDisposition.DEFER,
            localOperations,
            originForOperations(request.direction, localOperations),
            StrategyConsistency.LOCAL_AUTHORITATIVE,
            deferralReason = deferralReason,
            reasons = leadingReasons + "offline-first.local-accepted.remote-deferred",
        )
    }

    private fun evaluateRemoteFirst(
        request: StrategyEvaluationRequest,
        profile: RemoteFirstStrategyProfile,
        requestedStrategy: BuiltInSynchronizationStrategy,
        leadingReasons: List<String>,
    ): StrategyEvaluationResult {
        val unavailable = request.evidence.connectivity == StrategyConnectivity.UNAVAILABLE ||
            request.evidence.transportHealth == StrategyProviderHealth.UNAVAILABLE
        if (unavailable) {
            val hasLocalFallback = StrategyRemoteOutcome.UNAVAILABLE in profile.fallbackOn &&
                isLocalDataAvailable(request.evidence.cacheState)
            if (hasLocalFallback) {
                return result(
                    request,
                    requestedStrategy,
                    profile,
                    StrategyDisposition.EXECUTE,
                    localFallbackOperations(request.direction),
                    localOrigin(request.direction),
                    StrategyConsistency.EVENTUAL,
                    reasons = leadingReasons + "remote-first.typed-local-fallback",
                )
            }
            return result(
                request,
                requestedStrategy,
                profile,
                StrategyDisposition.REJECT,
                emptyList(),
                StrategyDataOrigin.NONE,
                StrategyConsistency.REMOTE_AUTHORITATIVE,
                rejectionReason = StrategyRejectionReason.CONNECTIVITY_UNAVAILABLE,
                reasons = leadingReasons + "remote-first.remote-unavailable",
            )
        }

        val unknown = request.evidence.connectivity == StrategyConnectivity.UNKNOWN ||
            request.evidence.connectivity == StrategyConnectivity.NOT_EVALUATED
        if (unknown && profile.unknownConnectivityPolicy != UnknownConnectivityPolicy.ATTEMPT_REMOTE) {
            return unknownConnectivityResult(
                request,
                requestedStrategy,
                profile,
                profile.unknownConnectivityPolicy,
                StrategyConsistency.REMOTE_AUTHORITATIVE,
                leadingReasons + "remote-first.connectivity-unknown",
            )
        }

        val operations = remoteOperations(
            request.direction,
            persistRemote = profile.persistRemoteResult,
        )
        val fallbackPlan = remoteFallbackPlan(profile, request.direction)
        return result(
            request,
            requestedStrategy,
            profile,
            StrategyDisposition.EXECUTE,
            operations,
            remoteOrigin(request.direction),
            StrategyConsistency.REMOTE_AUTHORITATIVE,
            fallbackPlan = fallbackPlan,
            reasons = leadingReasons + "remote-first.remote-selected",
        )
    }

    private fun evaluateCacheFirst(
        request: StrategyEvaluationRequest,
        profile: CacheFirstStrategyProfile,
        requestedStrategy: BuiltInSynchronizationStrategy,
        leadingReasons: List<String>,
    ): StrategyEvaluationResult {
        if (request.direction == SynchronizationDirection.PUSH) {
            return evaluateCacheFirstPush(
                request,
                profile,
                requestedStrategy,
                leadingReasons,
            )
        }

        return when (request.evidence.cacheState) {
            StrategyCacheState.FRESH -> {
                val operations = mutableListOf(StrategyOperation.SERVE_LOCAL)
                if (profile.refreshOnFreshHit) {
                    operations += refreshOperations(profile.requireDurableRefresh)
                }
                result(
                    request,
                    requestedStrategy,
                    profile,
                    if (profile.refreshOnFreshHit) {
                        StrategyDisposition.SERVE_AND_REFRESH
                    } else {
                        StrategyDisposition.EXECUTE
                    },
                    operations,
                    StrategyDataOrigin.LOCAL,
                    StrategyConsistency.EVENTUAL,
                    reasons = leadingReasons + "cache-first.fresh-hit",
                )
            }

            StrategyCacheState.STALE -> when (profile.staleCachePolicy) {
                StaleCachePolicy.REJECT -> result(
                    request,
                    requestedStrategy,
                    profile,
                    StrategyDisposition.REJECT,
                    emptyList(),
                    StrategyDataOrigin.NONE,
                    StrategyConsistency.EVENTUAL,
                    rejectionReason = StrategyRejectionReason.STALE_CACHE_NOT_ALLOWED,
                    reasons = leadingReasons + "cache-first.stale-rejected",
                )
                StaleCachePolicy.SERVE_STALE -> result(
                    request,
                    requestedStrategy,
                    profile,
                    StrategyDisposition.EXECUTE,
                    listOf(StrategyOperation.SERVE_LOCAL),
                    StrategyDataOrigin.LOCAL,
                    StrategyConsistency.EVENTUAL,
                    reasons = leadingReasons + "cache-first.stale-served",
                )
                StaleCachePolicy.SERVE_STALE_AND_REFRESH -> result(
                    request,
                    requestedStrategy,
                    profile,
                    StrategyDisposition.SERVE_AND_REFRESH,
                    listOf(StrategyOperation.SERVE_LOCAL) +
                        refreshOperations(profile.requireDurableRefresh),
                    StrategyDataOrigin.LOCAL,
                    StrategyConsistency.EVENTUAL,
                    reasons = leadingReasons + "cache-first.stale-served-refresh",
                )
            }

            StrategyCacheState.MISSING -> {
                if (request.evidence.connectivity == StrategyConnectivity.AVAILABLE) {
                    result(
                        request,
                        requestedStrategy,
                        profile,
                        StrategyDisposition.EXECUTE,
                        remoteOperations(request.direction, persistRemote = true),
                        remoteOrigin(request.direction),
                        StrategyConsistency.EVENTUAL,
                        reasons = leadingReasons + "cache-first.miss-remote-selected",
                    )
                } else {
                    result(
                        request,
                        requestedStrategy,
                        profile,
                        StrategyDisposition.REJECT,
                        emptyList(),
                        StrategyDataOrigin.NONE,
                        StrategyConsistency.EVENTUAL,
                        rejectionReason = StrategyRejectionReason.CACHE_MISS,
                        reasons = leadingReasons + "cache-first.miss-no-remote",
                    )
                }
            }

            StrategyCacheState.UNKNOWN,
            StrategyCacheState.NOT_EVALUATED,
            -> result(
                request,
                requestedStrategy,
                profile,
                StrategyDisposition.REJECT,
                emptyList(),
                StrategyDataOrigin.NONE,
                StrategyConsistency.EVENTUAL,
                rejectionReason = StrategyRejectionReason.REQUIRED_CAPABILITY_UNAVAILABLE,
                reasons = leadingReasons + "cache-first.cache-state-unknown",
            )
        }
    }

    private fun evaluateCacheFirstPush(
        request: StrategyEvaluationRequest,
        profile: CacheFirstStrategyProfile,
        requestedStrategy: BuiltInSynchronizationStrategy,
        leadingReasons: List<String>,
    ): StrategyEvaluationResult {
        val operations = listOf(StrategyOperation.READ_LOCAL, StrategyOperation.PUSH_REMOTE)
        return if (request.evidence.connectivity == StrategyConnectivity.AVAILABLE) {
            result(
                request,
                requestedStrategy,
                profile,
                StrategyDisposition.EXECUTE,
                operations,
                StrategyDataOrigin.LOCAL,
                StrategyConsistency.EVENTUAL,
                reasons = leadingReasons + "cache-first.push-local-source",
            )
        } else if (profile.requireDurableRefresh) {
            result(
                request,
                requestedStrategy,
                profile,
                StrategyDisposition.DEFER,
                listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.ENQUEUE_DURABLE_WORK,
                ),
                StrategyDataOrigin.LOCAL,
                StrategyConsistency.EVENTUAL,
                deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
                reasons = leadingReasons + "cache-first.push-deferred",
            )
        } else {
            result(
                request,
                requestedStrategy,
                profile,
                StrategyDisposition.REJECT,
                emptyList(),
                StrategyDataOrigin.NONE,
                StrategyConsistency.EVENTUAL,
                rejectionReason = StrategyRejectionReason.CONNECTIVITY_UNAVAILABLE,
                reasons = leadingReasons + "cache-first.push-unavailable",
            )
        }
    }

    private fun evaluateNetworkOnly(
        request: StrategyEvaluationRequest,
        profile: NetworkOnlyStrategyProfile,
        requestedStrategy: BuiltInSynchronizationStrategy,
        leadingReasons: List<String>,
    ): StrategyEvaluationResult {
        if (request.evidence.connectivity == StrategyConnectivity.UNAVAILABLE) {
            return result(
                request,
                requestedStrategy,
                profile,
                StrategyDisposition.REJECT,
                emptyList(),
                StrategyDataOrigin.NONE,
                StrategyConsistency.REMOTE_AUTHORITATIVE,
                rejectionReason = StrategyRejectionReason.CONNECTIVITY_UNAVAILABLE,
                reasons = leadingReasons + "network-only.connectivity-unavailable",
            )
        }
        val unknown = request.evidence.connectivity == StrategyConnectivity.UNKNOWN ||
            request.evidence.connectivity == StrategyConnectivity.NOT_EVALUATED
        if (unknown && profile.unknownConnectivityPolicy != UnknownConnectivityPolicy.ATTEMPT_REMOTE) {
            return unknownConnectivityResult(
                request,
                requestedStrategy,
                profile,
                profile.unknownConnectivityPolicy,
                StrategyConsistency.REMOTE_AUTHORITATIVE,
                leadingReasons + "network-only.connectivity-unknown",
            )
        }
        return result(
            request,
            requestedStrategy,
            profile,
            StrategyDisposition.EXECUTE,
            networkOnlyOperations(request.direction),
            remoteOrigin(request.direction),
            StrategyConsistency.REMOTE_AUTHORITATIVE,
            reasons = leadingReasons + "network-only.transport-selected",
        )
    }

    private fun evaluateHybrid(
        request: StrategyEvaluationRequest,
        profile: HybridStrategyProfile,
        requestedStrategy: BuiltInSynchronizationStrategy,
        leadingReasons: List<String>,
    ): StrategyEvaluationResult {
        val remoteAvailable = request.evidence.connectivity == StrategyConnectivity.AVAILABLE &&
            request.evidence.transportHealth != StrategyProviderHealth.UNAVAILABLE
        val localAvailable = isLocalDataAvailable(request.evidence.cacheState) &&
            request.evidence.storageHealth != StrategyProviderHealth.UNAVAILABLE

        val connectivityUnknown =
            request.evidence.connectivity == StrategyConnectivity.UNKNOWN ||
                request.evidence.connectivity == StrategyConnectivity.NOT_EVALUATED
        if (
            connectivityUnknown &&
            profile.primarySource == HybridSource.REMOTE &&
            profile.unknownConnectivityPolicy != UnknownConnectivityPolicy.ATTEMPT_REMOTE
        ) {
            return unknownConnectivityResult(
                request,
                requestedStrategy,
                profile,
                profile.unknownConnectivityPolicy,
                StrategyConsistency.READ_YOUR_WRITES,
                leadingReasons + "hybrid.connectivity-unknown",
            )
        }

        val remoteEligible = remoteAvailable ||
            (
                connectivityUnknown &&
                    profile.unknownConnectivityPolicy == UnknownConnectivityPolicy.ATTEMPT_REMOTE &&
                    request.evidence.transportHealth != StrategyProviderHealth.UNAVAILABLE
                )

        val selectedSource = when (profile.primarySource) {
            HybridSource.REMOTE -> when {
                remoteEligible -> HybridSource.REMOTE
                localAvailable -> HybridSource.LOCAL
                else -> null
            }
            HybridSource.LOCAL -> when {
                localAvailable -> HybridSource.LOCAL
                remoteEligible -> HybridSource.REMOTE
                else -> null
            }
        }

        if (selectedSource == null) {
            return result(
                request,
                requestedStrategy,
                profile,
                StrategyDisposition.REJECT,
                emptyList(),
                StrategyDataOrigin.NONE,
                StrategyConsistency.EVENTUAL,
                rejectionReason = StrategyRejectionReason.REQUIRED_CAPABILITY_UNAVAILABLE,
                reasons = leadingReasons + "hybrid.no-source-available",
            )
        }

        val usedFallback = selectedSource != profile.primarySource
        val operations = when (selectedSource) {
            HybridSource.REMOTE -> remoteOperations(
                request.direction,
                persistRemote = profile.persistRemoteResult,
            )
            HybridSource.LOCAL -> localFallbackOperations(request.direction).toMutableList().also {
                if (usedFallback && profile.reconcileAfterFallback) {
                    it += StrategyOperation.ENQUEUE_DURABLE_WORK
                    it += StrategyOperation.RECONCILE
                }
            }
        }

        return result(
            request,
            requestedStrategy,
            profile,
            if (usedFallback && profile.reconcileAfterFallback) {
                StrategyDisposition.SERVE_AND_REFRESH
            } else {
                StrategyDisposition.EXECUTE
            },
            operations,
            when (selectedSource) {
                HybridSource.LOCAL -> localOrigin(request.direction)
                HybridSource.REMOTE -> remoteOrigin(request.direction)
            },
            StrategyConsistency.READ_YOUR_WRITES,
            reasons = leadingReasons + if (usedFallback) {
                "hybrid.explicit-fallback.${selectedSource.name.lowercase()}"
            } else {
                "hybrid.primary.${selectedSource.name.lowercase()}"
            },
        )
    }

    private fun unknownConnectivityResult(
        request: StrategyEvaluationRequest,
        requestedStrategy: BuiltInSynchronizationStrategy,
        profile: SynchronizationStrategyProfile,
        policy: UnknownConnectivityPolicy,
        consistency: StrategyConsistency,
        reasons: List<String>,
    ): StrategyEvaluationResult = when (policy) {
        UnknownConnectivityPolicy.ATTEMPT_REMOTE ->
            error("ATTEMPT_REMOTE is handled before unknownConnectivityResult.")
        UnknownConnectivityPolicy.DEFER -> result(
            request,
            requestedStrategy,
            profile,
            StrategyDisposition.DEFER,
            listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            StrategyDataOrigin.NONE,
            consistency,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNKNOWN,
            reasons = reasons,
        )
        UnknownConnectivityPolicy.REJECT -> result(
            request,
            requestedStrategy,
            profile,
            StrategyDisposition.REJECT,
            emptyList(),
            StrategyDataOrigin.NONE,
            consistency,
            rejectionReason = StrategyRejectionReason.CONNECTIVITY_UNKNOWN,
            reasons = reasons,
        )
    }

    private fun result(
        request: StrategyEvaluationRequest,
        requestedStrategy: BuiltInSynchronizationStrategy,
        profile: SynchronizationStrategyProfile,
        disposition: StrategyDisposition,
        operations: List<StrategyOperation>,
        origin: StrategyDataOrigin,
        consistency: StrategyConsistency,
        deferralReason: StrategyDeferralReason? = null,
        rejectionReason: StrategyRejectionReason? = null,
        fallbackPlan: StrategyFallbackPlan? = null,
        reasons: List<String>,
    ): StrategyEvaluationResult {
        val capabilities = deriveCapabilities(
            operations + (fallbackPlan?.operations ?: emptyList()),
        ).toMutableSet()
        if (
            profile is CacheFirstStrategyProfile &&
            StrategyOperation.SERVE_LOCAL in operations
        ) {
            capabilities += StrategyProviderCapability.CACHE_ACCESS
        }
        val durableContinuation = deriveDurableContinuation(
            request = request,
            profile = profile,
            operations = operations,
            consistency = consistency,
        )
        return StrategyEvaluationResult(
            decisionId = request.decisionId,
            plan = StrategyExecutionPlan(
                id = request.planId,
                requestedStrategy = requestedStrategy,
                effectiveProfileId = profile.id,
                effectiveStrategy = profile.strategy,
                configurationVersion = profile.configurationVersion,
                direction = request.direction,
                mode = request.mode,
                disposition = disposition,
                operations = operations,
                requiredCapabilities = capabilities,
                dataOrigin = origin,
                consistency = consistency,
                deferralReason = deferralReason,
                rejectionReason = rejectionReason,
                fallbackPlan = fallbackPlan,
                durableContinuation = durableContinuation,
            ),
            reasonCodes = reasons,
        )
    }

    private fun deriveDurableContinuation(
        request: StrategyEvaluationRequest,
        profile: SynchronizationStrategyProfile,
        operations: List<StrategyOperation>,
        consistency: StrategyConsistency,
    ): io.dataloom.api.strategy.StrategyDurableContinuationPlan? {
        if (StrategyOperation.ENQUEUE_DURABLE_WORK !in operations) return null

        val continuationOperations = when (profile) {
            is OfflineFirstStrategyProfile ->
                remoteOperations(request.direction, persistRemote = true).toMutableList().also {
                    if (profile.reconcileWhenOnline) it += StrategyOperation.RECONCILE
                }
            is RemoteFirstStrategyProfile ->
                remoteOperations(
                    request.direction,
                    persistRemote = profile.persistRemoteResult,
                )
            is CacheFirstStrategyProfile -> when (request.direction) {
                SynchronizationDirection.PUSH -> listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PUSH_REMOTE,
                )
                SynchronizationDirection.PULL,
                SynchronizationDirection.BIDIRECTIONAL,
                -> remoteOperations(request.direction, persistRemote = true)
            }
            is HybridStrategyProfile ->
                remoteOperations(
                    request.direction,
                    persistRemote = profile.persistRemoteResult,
                ).toMutableList().also {
                    if (profile.reconcileAfterFallback) it += StrategyOperation.RECONCILE
                }
            is NetworkOnlyStrategyProfile ->
                error("Network-only plans cannot admit durable queue work.")
            is AdaptiveStrategyProfile ->
                error("Nested adaptive profiles are rejected before plan construction.")
        }
        val continuationFallback = when (profile) {
            is RemoteFirstStrategyProfile -> remoteFallbackPlan(profile, request.direction)
            else -> null
        }
        return io.dataloom.api.strategy.StrategyDurableContinuationPlan(
            operations = continuationOperations,
            requiredCapabilities = deriveCapabilities(
                continuationOperations +
                    (continuationFallback?.operations ?: emptyList()),
            ),
            dataOrigin = originForOperations(request.direction, continuationOperations),
            consistency = consistency,
            evaluatedCacheState = request.evidence.cacheState,
            fallbackPlan = continuationFallback,
        )
    }

    private fun deriveCapabilities(
        operations: List<StrategyOperation>,
    ): Set<StrategyProviderCapability> {
        val capabilities = mutableSetOf<StrategyProviderCapability>()
        operations.forEach { operation ->
            when (operation) {
                StrategyOperation.READ_LOCAL,
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.ACCEPT_LOCAL,
                StrategyOperation.SERVE_LOCAL,
                StrategyOperation.PERSIST_REMOTE,
                -> capabilities += StrategyProviderCapability.STORAGE
                StrategyOperation.ENQUEUE_DURABLE_WORK ->
                    capabilities += StrategyProviderCapability.QUEUE
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.PULL_REMOTE,
                -> capabilities += StrategyProviderCapability.TRANSPORT
                StrategyOperation.SCHEDULE_REFRESH -> {
                    capabilities += StrategyProviderCapability.SCHEDULER
                    capabilities += StrategyProviderCapability.QUEUE
                }
                StrategyOperation.RECONCILE -> {
                    capabilities += StrategyProviderCapability.STORAGE
                    capabilities += StrategyProviderCapability.CONFLICT_STATE
                }
            }
        }
        if (
            StrategyOperation.ACCEPT_LOCAL in operations &&
            StrategyOperation.ENQUEUE_DURABLE_WORK in operations
        ) {
            capabilities += StrategyProviderCapability.ATOMIC_LOCAL_ADMISSION
        }
        return capabilities
    }

    private fun remoteOperations(
        direction: SynchronizationDirection,
        persistRemote: Boolean,
    ): List<StrategyOperation> {
        val operations = mutableListOf<StrategyOperation>()
        when (direction) {
            SynchronizationDirection.PUSH -> {
                operations += StrategyOperation.READ_LOCAL
                operations += StrategyOperation.PUSH_REMOTE
            }
            SynchronizationDirection.PULL -> {
                if (persistRemote) operations += StrategyOperation.READ_CHECKPOINT
                operations += StrategyOperation.PULL_REMOTE
                if (persistRemote) operations += StrategyOperation.PERSIST_REMOTE
            }
            SynchronizationDirection.BIDIRECTIONAL -> {
                operations += StrategyOperation.READ_LOCAL
                operations += StrategyOperation.PUSH_REMOTE
                if (persistRemote) operations += StrategyOperation.READ_CHECKPOINT
                operations += StrategyOperation.PULL_REMOTE
                if (persistRemote) operations += StrategyOperation.PERSIST_REMOTE
            }
        }
        return operations
    }

    private fun remoteFallbackPlan(
        profile: RemoteFirstStrategyProfile,
        direction: SynchronizationDirection,
    ): StrategyFallbackPlan? {
        if (profile.fallbackOn.isEmpty() || direction == SynchronizationDirection.PUSH) {
            return null
        }
        return StrategyFallbackPlan(
            remoteOutcomes = profile.fallbackOn,
            operations = localFallbackOperations(direction),
            dataOrigin = localOrigin(direction),
        )
    }

    private fun networkOnlyOperations(
        direction: SynchronizationDirection,
    ): List<StrategyOperation> = when (direction) {
        SynchronizationDirection.PUSH -> listOf(StrategyOperation.PUSH_REMOTE)
        SynchronizationDirection.PULL -> listOf(StrategyOperation.PULL_REMOTE)
        SynchronizationDirection.BIDIRECTIONAL -> listOf(
            StrategyOperation.PUSH_REMOTE,
            StrategyOperation.PULL_REMOTE,
        )
    }

    private fun localFallbackOperations(
        direction: SynchronizationDirection,
    ): List<StrategyOperation> = when (direction) {
        SynchronizationDirection.PUSH -> listOf(StrategyOperation.READ_LOCAL)
        SynchronizationDirection.PULL -> listOf(StrategyOperation.SERVE_LOCAL)
        SynchronizationDirection.BIDIRECTIONAL -> listOf(
            StrategyOperation.READ_LOCAL,
            StrategyOperation.SERVE_LOCAL,
        )
    }

    private fun refreshOperations(durable: Boolean): List<StrategyOperation> =
        if (durable) {
            listOf(
                StrategyOperation.ENQUEUE_DURABLE_WORK,
                StrategyOperation.SCHEDULE_REFRESH,
            )
        } else {
            listOf(StrategyOperation.PULL_REMOTE, StrategyOperation.PERSIST_REMOTE)
        }

    private fun localOrigin(direction: SynchronizationDirection): StrategyDataOrigin =
        when (direction) {
            SynchronizationDirection.PUSH -> StrategyDataOrigin.NONE
            SynchronizationDirection.PULL -> StrategyDataOrigin.LOCAL
            SynchronizationDirection.BIDIRECTIONAL -> StrategyDataOrigin.LOCAL
        }

    private fun originForOperations(
        direction: SynchronizationDirection,
        operations: List<StrategyOperation>,
    ): StrategyDataOrigin =
        if (StrategyOperation.SERVE_LOCAL in operations) {
            localOrigin(direction)
        } else if (
            StrategyOperation.PULL_REMOTE in operations &&
            direction == SynchronizationDirection.PULL
        ) {
            StrategyDataOrigin.REMOTE
        } else if (
            StrategyOperation.PULL_REMOTE in operations &&
            direction == SynchronizationDirection.BIDIRECTIONAL
        ) {
            StrategyDataOrigin.MIXED
        } else {
            StrategyDataOrigin.NONE
        }

    private fun remoteOrigin(direction: SynchronizationDirection): StrategyDataOrigin =
        when (direction) {
            SynchronizationDirection.PUSH -> StrategyDataOrigin.NONE
            SynchronizationDirection.PULL -> StrategyDataOrigin.REMOTE
            SynchronizationDirection.BIDIRECTIONAL -> StrategyDataOrigin.MIXED
        }

    private fun isLocalDataAvailable(cacheState: StrategyCacheState): Boolean =
        cacheState == StrategyCacheState.FRESH || cacheState == StrategyCacheState.STALE
}
