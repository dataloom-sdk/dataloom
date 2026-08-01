package io.dataloom.runtime.strategy

import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.strategy.StrategyEvaluationResult

/**
 * Per-execution provider transformation applied after strategy evaluation and
 * capability-aware resolution, but before a built-in strategy executor runs.
 *
 * The identity boundary preserves the historical direct strategy path. A
 * protected boundary may replace only the resolved provider instances while
 * retaining the exact evaluation and plan.
 */
internal fun interface StrategyProviderExecutionBoundary {
    fun prepare(
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategyProviderExecutionPreparation

    companion object {
        val Identity: StrategyProviderExecutionBoundary =
            StrategyProviderExecutionBoundary { _, providers ->
                StrategyProviderExecutionPreparation.Prepared(providers)
            }
    }
}

/** Result of preparing resolved providers for one strategy execution. */
internal sealed interface StrategyProviderExecutionPreparation {
    data class Prepared(
        val providers: StrategyProviderSet,
    ) : StrategyProviderExecutionPreparation

    data class Rejected(
        val reason: StrategyExecutionRejectionReason,
    ) : StrategyProviderExecutionPreparation
}
