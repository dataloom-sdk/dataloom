package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable, payload-free request to verify cache availability before serving it.
 *
 * Strategy evaluation records bounded cache evidence before provider resolution.
 * The provider must re-check application-owned synchronized state at the serving
 * boundary so DataLoom never claims a fresh or allowed-stale hit from a stale
 * caller assertion alone.
 */
public class StrategyCacheAccessRequest(
    public val request: SynchronizationRequest,
    public val decisionId: StrategyDecisionId,
    public val planId: StrategyPlanId,
    public val profileId: StrategyProfileId,
    public val configurationVersion: StrategyConfigurationVersion,
    public val evaluatedCacheState: StrategyCacheState,
    public val allowStale: Boolean,
) {
    init {
        require(
            evaluatedCacheState == StrategyCacheState.FRESH ||
                evaluatedCacheState == StrategyCacheState.STALE,
        ) {
            "Cache access requires evaluated FRESH or STALE state."
        }
        require(allowStale || evaluatedCacheState != StrategyCacheState.STALE) {
            "STALE cache access requires allowStale=true."
        }
    }

    /** Bounded diagnostics that exclude workflow, decision, plan, and payload data. */
    override fun toString(): String =
        "StrategyCacheAccessRequest(" +
            "direction=${request.direction}, mode=${request.mode}, " +
            "configurationVersion=${configurationVersion.value}, " +
            "evaluatedCacheState=$evaluatedCacheState, allowStale=$allowStale)"
}

/**
 * Provider-observed freshness at the exact local serving boundary.
 *
 * [observedAt] is the instant at which the provider classified the cached state.
 * [validUntil] is the exclusive freshness deadline. Equality means expired.
 */
public data class StrategyCacheFreshnessEvidence(
    public val cacheState: StrategyCacheState,
    public val observedAt: DataLoomInstant,
    public val validUntil: DataLoomInstant,
) {
    init {
        require(
            cacheState == StrategyCacheState.FRESH ||
                cacheState == StrategyCacheState.STALE,
        ) {
            "Cache freshness evidence requires FRESH or STALE state."
        }
        when (cacheState) {
            StrategyCacheState.FRESH -> require(
                observedAt.epochMilliseconds < validUntil.epochMilliseconds,
            ) {
                "FRESH cache evidence requires observedAt before validUntil."
            }
            StrategyCacheState.STALE -> require(
                observedAt.epochMilliseconds >= validUntil.epochMilliseconds,
            ) {
                "STALE cache evidence requires observedAt at or after validUntil."
            }
            else -> error("Validated above.")
        }
    }
}

/** Typed result of verifying application-owned synchronized cache state. */
public sealed interface StrategyCacheAccessResult {
    /** Local state is available and may be served under the admitted policy. */
    public data class Available(
        public val freshness: StrategyCacheFreshnessEvidence,
    ) : StrategyCacheAccessResult

    /** Local state cannot be served under the admitted policy. */
    public data class Unavailable(
        public val cacheState: StrategyCacheState,
    ) : StrategyCacheAccessResult {
        init {
            require(
                cacheState != StrategyCacheState.FRESH &&
                    cacheState != StrategyCacheState.STALE,
            ) {
                "Unavailable cache access must not claim FRESH or STALE state."
            }
        }
    }
}

/**
 * Storage extension used by cache-first serving plans.
 *
 * The provider verifies freshness and availability without returning domain
 * payloads. Applications continue to read values through their own repositories.
 */
public interface StrategyCacheAccessProvider : StorageProvider {
    public suspend fun evaluateCacheAccess(
        request: StrategyCacheAccessRequest,
    ): ProviderOperationResult<StrategyCacheAccessResult>
}
