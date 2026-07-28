package io.dataloom.runtime.strategy

import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyExecutionTrigger
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.StrategyProviderResolutionResult
import io.dataloom.core.provider.StrategyProviderResolver

/** Admits a strategy request before resolving or invoking any provider. */
internal class StrategySynchronizationExecutionCoordinator(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val evaluator: BuiltInSynchronizationStrategyEvaluator,
    private val providerResolver: StrategyProviderResolver,
    private val clock: DataLoomClock,
) {
    public suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult {
        val evaluation = evaluator.evaluate(request.evaluationRequest())

        if (lifecycleCoordinator.state != ProviderLifecycleCoordinatorState.INITIALIZED) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            )
        }

        when (evaluation.plan.disposition) {
            StrategyDisposition.REJECT -> return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.STRATEGY_REJECTED,
            )
            StrategyDisposition.DEFER -> return StrategySynchronizationExecutionResult.Deferred(
                evaluation = evaluation,
                completedAt = clock.now(),
            )
            StrategyDisposition.EXECUTE,
            StrategyDisposition.SERVE_AND_REFRESH,
            -> Unit
        }

        if (evaluation.plan.effectiveStrategy != BuiltInSynchronizationStrategy.NETWORK_ONLY) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
        }
        if (request.trigger == StrategyExecutionTrigger.DURABLE_QUEUE) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.INCOMPATIBLE_TRIGGER,
            )
        }
        if (request.input !is StrategyOperationInput.DirectTransport) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
            )
        }

        val providers = when (
            val resolution = providerResolver.resolve(
                bindings = bindings,
                requiredCapabilities = evaluation.plan.requiredCapabilities,
            )
        ) {
            is StrategyProviderResolutionResult.Success -> resolution.providers
            is StrategyProviderResolutionResult.Failure -> {
                return StrategySynchronizationExecutionResult.Rejected(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    reason = StrategyExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                    missingCapabilities = resolution.missingCapabilities,
                    bindingFailures = resolution.bindingFailures,
                )
            }
        }

        return NetworkOnlyStrategyExecutor(clock).execute(
            request = request,
            evaluation = evaluation,
            providers = providers,
        )
    }

    private fun rejected(
        evaluation: io.dataloom.api.strategy.StrategyEvaluationResult,
        reason: StrategyExecutionRejectionReason,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Rejected(
            evaluation = evaluation,
            completedAt = clock.now(),
            reason = reason,
        )
}
