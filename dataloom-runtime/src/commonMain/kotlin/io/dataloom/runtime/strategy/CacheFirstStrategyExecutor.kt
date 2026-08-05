package io.dataloom.runtime.strategy

import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock

/** Executes the bounded cache-only local-serving slice of cache-first. */
internal class CacheFirstStrategyExecutor(
    private val clock: DataLoomClock,
) {
    suspend fun execute(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        if (request.input !is StrategyOperationInput.ProviderBacked) {
            return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
        }
        if (!isSupportedLocalServingPlan(evaluation)) {
            return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        val evaluatedCacheState = request.evidence.cacheState
        if (
            evaluatedCacheState != StrategyCacheState.FRESH &&
            evaluatedCacheState != StrategyCacheState.STALE
        ) {
            return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        val cacheProvider = providers.storageProvider as? StrategyCacheAccessProvider
            ?: return rejected(
                evaluation,
                StrategyExecutionRejectionReason.CACHE_ACCESS_PROVIDER_NOT_CONFIGURED,
            )

        val accessRequest = try {
            StrategyCacheAccessRequest(
                request = request.request,
                decisionId = evaluation.decisionId,
                planId = evaluation.plan.id,
                profileId = evaluation.plan.effectiveProfileId,
                configurationVersion = evaluation.plan.configurationVersion,
                evaluatedCacheState = evaluatedCacheState,
                allowStale = evaluatedCacheState == StrategyCacheState.STALE,
            )
        } catch (_: IllegalArgumentException) {
            return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        return when (val result = cacheProvider.evaluateCacheAccess(accessRequest)) {
            is ProviderOperationResult.Failure ->
                StrategySynchronizationExecutionResult.Failed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    error = result.error,
                    transportAttempted = false,
                )
            is ProviderOperationResult.Success -> when (val access = result.value) {
                is StrategyCacheAccessResult.Available ->
                    available(
                        evaluation = evaluation,
                        evaluatedCacheState = evaluatedCacheState,
                        access = access,
                    )
                is StrategyCacheAccessResult.Unavailable ->
                    StrategySynchronizationExecutionResult.CacheUnavailable(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        evaluatedCacheState = evaluatedCacheState,
                        providerCacheState = access.cacheState,
                        reason = StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE,
                    )
            }
        }
    }

    private fun available(
        evaluation: StrategyEvaluationResult,
        evaluatedCacheState: StrategyCacheState,
        access: StrategyCacheAccessResult.Available,
    ): StrategySynchronizationExecutionResult {
        val freshness = access.freshness
        if (
            evaluatedCacheState == StrategyCacheState.FRESH &&
            freshness.cacheState == StrategyCacheState.STALE
        ) {
            return StrategySynchronizationExecutionResult.CacheUnavailable(
                evaluation = evaluation,
                completedAt = clock.now(),
                evaluatedCacheState = evaluatedCacheState,
                providerCacheState = freshness.cacheState,
                reason = StrategyCacheUnavailableReason.FRESHNESS_DOWNGRADED,
                providerFreshness = freshness,
            )
        }
        return StrategySynchronizationExecutionResult.CacheServed(
            evaluation = evaluation,
            completedAt = clock.now(),
            evaluatedCacheState = evaluatedCacheState,
            freshness = freshness,
        )
    }

    private fun isSupportedLocalServingPlan(
        evaluation: StrategyEvaluationResult,
    ): Boolean {
        val plan = evaluation.plan
        return plan.effectiveStrategy == BuiltInSynchronizationStrategy.CACHE_FIRST &&
            plan.disposition == StrategyDisposition.EXECUTE &&
            plan.operations == listOf(StrategyOperation.SERVE_LOCAL) &&
            plan.requiredCapabilities == setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
            ) &&
            plan.dataOrigin == StrategyDataOrigin.LOCAL
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
