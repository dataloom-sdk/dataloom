package io.dataloom.api.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.StorageProvider

/**
 * Canonical remote failure supplied by an application transport adapter.
 *
 * Returning this contract lets the built-in runtime evaluate the profile's
 * exact fallback allowlist. Unclassified failures remain
 * [StrategyRemoteOutcome.UNKNOWN_FAILURE].
 */
public interface ClassifiedStrategyRemoteError : DataLoomError {
    public val remoteOutcome: StrategyRemoteOutcome
}

/** Immutable, payload-free request for application-owned local fallback. */
public data class StrategyLocalFallbackRequest(
    public val request: SynchronizationRequest,
    public val decisionId: StrategyDecisionId,
    public val planId: StrategyPlanId,
    public val profileId: StrategyProfileId,
    public val configurationVersion: StrategyConfigurationVersion,
    public val remoteOutcome: StrategyRemoteOutcome,
    public val remoteAttempted: Boolean,
    public val evaluatedCacheState: StrategyCacheState,
)

/** Typed local-state decision; domain payloads remain application-owned. */
public sealed interface StrategyLocalFallbackResult {
    public data class Available(
        public val cacheState: StrategyCacheState,
    ) : StrategyLocalFallbackResult {
        init {
            require(
                cacheState == StrategyCacheState.FRESH ||
                    cacheState == StrategyCacheState.STALE,
            ) {
                "Available local fallback requires FRESH or STALE cache state."
            }
        }
    }

    public data class Unavailable(
        public val cacheState: StrategyCacheState,
    ) : StrategyLocalFallbackResult {
        init {
            require(
                cacheState != StrategyCacheState.FRESH &&
                    cacheState != StrategyCacheState.STALE,
            ) {
                "Unavailable local fallback must not claim FRESH or STALE cache state."
            }
        }
    }
}

/**
 * Optional storage capability used only after a typed fallback transition.
 *
 * It reports synchronized local-state availability without exposing domain
 * queries or payloads. Applications continue to read data through their own
 * repositories.
 */
public interface StrategyLocalFallbackProvider : StorageProvider {
    public suspend fun evaluateLocalFallback(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult>
}
