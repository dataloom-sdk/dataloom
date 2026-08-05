package io.dataloom.runtime.strategy

import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock

/** Executes the bounded direct cache-first local-serving slice. */
internal class CacheFirstStrategyExecutor(
    private val clock: DataLoomClock,
) {
    suspend fun execute(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        if (
            request.input !is StrategyOperationInput.ProviderBacked ||
            evaluation.plan.disposition != StrategyDisposition.EXECUTE ||
            evaluation.plan.operations != listOf(StrategyOperation.SERVE_LOCAL) ||
            StrategyProviderCapability.CACHE_ACCESS !in
            evaluation.plan.requiredCapabilities
        ) {
            return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        val evaluatedCacheState = request.evidence.cacheState
        if (
            evaluatedCacheState != StrategyCacheState.FRESH &&
            evaluatedCacheState != StrategyCacheState.STALE
        ) {
            return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        val provider = providers.storageProvider as? StrategyCacheAccessProvider
            ?: return rejected(
                evaluation,
                StrategyExecutionRejectionReason.PROVIDER_PROTECTION_SCOPE_MISMATCH,
            )

        val cacheRequest = StrategyCacheAccessRequest(
            request = request.request,
            decisionId = evaluation.decisionId,
            planId = evaluation.plan.id,
            profileId = evaluation.plan.effectiveProfileId,
            configurationVersion = evaluation.plan.configurationVersion,
            evaluatedCacheState = evaluatedCacheState,
            allowStale = evaluatedCacheState == StrategyCacheState.STALE,
        )

        return when (val result = provider.evaluateCacheAccess(cacheRequest)) {
            is ProviderOperationResult.Success -> when (val access = result.value) {
                is StrategyCacheAccessResult.Available -> {
                    if (
                        evaluatedCacheState == StrategyCacheState.FRESH &&
                        access.freshness.cacheState == StrategyCacheState.STALE
                    ) {
                        StrategySynchronizationExecutionResult.CacheUnavailable(
                            evaluation = evaluation,
                            completedAt = clock.now(),
                            evaluatedCacheState = evaluatedCacheState,
                            observedCacheState = StrategyCacheState.STALE,
                        )
                    } else {
                        StrategySynchronizationExecutionResult.CacheAvailable(
                            evaluation = evaluation,
                            completedAt = clock.now(),
                            freshness = access.freshness,
                        )
                    }
                }
                is StrategyCacheAccessResult.Unavailable ->
                    StrategySynchronizationExecutionResult.CacheUnavailable(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        evaluatedCacheState = evaluatedCacheState,
                        observedCacheState = access.cacheState,
                    )
            }
            is ProviderOperationResult.Failure ->
                StrategySynchronizationExecutionResult.Failed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    error = result.error,
                    transportAttempted = false,
                )
        }
    }

    private fun rejected(
        evaluation: StrategyEvaluationResult,
        reason: StrategyExecutionRejectionReason,
    ): StrategySynchronizationExecutionResult.Rejected =
        StrategySynchronizationExecutionResult.Rejected(
            evaluation = evaluation,
            completedAt = clock.now(),
            reason = reason,
        )
}
