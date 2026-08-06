package io.dataloom.runtime.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomInstant

/**
 * Local cache use plus the terminal outcome of one foreground PULL refresh.
 *
 * DataLoom exposes no application domain value. [freshness] records the state
 * admitted for local use before refresh, while [refresh] independently records
 * whether the canonical inbound refresh completed, partially succeeded,
 * failed, or was explicitly cancelled.
 */
public class StrategyCacheServedWithInlineRefreshResult(
    override val evaluation: StrategyEvaluationResult,
    public val evaluatedCacheState: StrategyCacheState,
    public val freshness: StrategyCacheFreshnessEvidence,
    public val refresh: StrategyCacheInlineRefreshResult,
) : StrategySynchronizationExecutionResult {
    init {
        val plan = evaluation.plan
        require(
            plan.effectiveStrategy == BuiltInSynchronizationStrategy.CACHE_FIRST &&
                plan.direction == SynchronizationDirection.PULL &&
                plan.disposition == StrategyDisposition.SERVE_AND_REFRESH &&
                plan.operations == listOf(
                    StrategyOperation.SERVE_LOCAL,
                    StrategyOperation.PULL_REMOTE,
                    StrategyOperation.PERSIST_REMOTE,
                ) &&
                plan.requiredCapabilities == setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.CACHE_ACCESS,
                    StrategyProviderCapability.TRANSPORT,
                ) &&
                plan.dataOrigin == StrategyDataOrigin.LOCAL &&
                plan.durableContinuation == null &&
                plan.fallbackPlan == null,
        ) {
            "Inline cache refresh result requires the exact non-durable cache-first PULL plan."
        }
        require(
            evaluatedCacheState == StrategyCacheState.FRESH ||
                evaluatedCacheState == StrategyCacheState.STALE,
        ) {
            "Inline cache refresh requires evaluated FRESH or STALE state."
        }
        require(
            evaluatedCacheState != StrategyCacheState.FRESH ||
                freshness.cacheState == StrategyCacheState.FRESH,
        ) {
            "A fresh admission must not be served from stale provider evidence."
        }
        val refreshResult = refresh.synchronizationResult()
        require(refreshResult.request.direction == SynchronizationDirection.PULL) {
            "Inline cache refresh output must retain PULL direction."
        }
        require(refreshResult.request.mode == plan.mode) {
            "Inline cache refresh output mode must match the immutable strategy plan."
        }
    }

    override val completedAt: DataLoomInstant
        get() = refresh.completedAt

    public val dataOrigin: StrategyDataOrigin = StrategyDataOrigin.LOCAL

    override fun equals(other: Any?): Boolean =
        other is StrategyCacheServedWithInlineRefreshResult &&
            evaluation == other.evaluation &&
            evaluatedCacheState == other.evaluatedCacheState &&
            freshness == other.freshness &&
            refresh == other.refresh

    override fun hashCode(): Int {
        var result = evaluation.hashCode()
        result = (31 * result) + evaluatedCacheState.hashCode()
        result = (31 * result) + freshness.hashCode()
        result = (31 * result) + refresh.hashCode()
        return result
    }

    override fun toString(): String =
        "StrategyCacheServedWithInlineRefreshResult(" +
            "decisionId=${evaluation.decisionId}, " +
            "planId=${evaluation.plan.id}, " +
            "evaluatedCacheState=$evaluatedCacheState, " +
            "providerCacheState=${freshness.cacheState}, " +
            "refreshDisposition=${refresh.disposition}, " +
            "dataOrigin=$dataOrigin)"
}

private fun StrategyCacheInlineRefreshResult.synchronizationResult(): SynchronizationResult =
    when (this) {
        is StrategyCacheInlineRefreshResult.Completed -> output.result
        is StrategyCacheInlineRefreshResult.PartiallySucceeded -> output.result
        is StrategyCacheInlineRefreshResult.Failed -> output.result
        is StrategyCacheInlineRefreshResult.Cancelled -> output.result
    }
