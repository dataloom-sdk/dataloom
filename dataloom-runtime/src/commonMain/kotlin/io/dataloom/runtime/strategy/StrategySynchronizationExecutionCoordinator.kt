package io.dataloom.runtime.strategy

import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyExecutionTrigger
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.StrategyProviderResolutionResult
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/** Admits a strategy request before resolving or invoking any provider. */
internal class StrategySynchronizationExecutionCoordinator(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val evaluator: BuiltInSynchronizationStrategyEvaluator,
    private val providerResolver: StrategyProviderResolver,
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?,
) {
    public suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult = execute(
        request = request,
        bindings = bindings,
        providerBoundary = StrategyProviderExecutionBoundary.Identity,
    )

    internal suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
        providerBoundary: StrategyProviderExecutionBoundary,
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

        if (request.trigger == StrategyExecutionTrigger.DURABLE_QUEUE) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.INCOMPATIBLE_TRIGGER,
            )
        }
        when (evaluation.plan.effectiveStrategy) {
            BuiltInSynchronizationStrategy.NETWORK_ONLY -> {
                if (request.input !is StrategyOperationInput.DirectTransport) {
                    return rejected(
                        evaluation = evaluation,
                        reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
                    )
                }
            }
            BuiltInSynchronizationStrategy.REMOTE_FIRST,
            BuiltInSynchronizationStrategy.CACHE_FIRST,
            -> {
                if (request.input !is StrategyOperationInput.ProviderBacked) {
                    return rejected(
                        evaluation = evaluation,
                        reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
                    )
                }
            }
            else -> return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
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

        val executionProviders = when (
            val preparation = providerBoundary.prepare(evaluation, providers)
        ) {
            is StrategyProviderExecutionPreparation.Prepared -> preparation.providers
            is StrategyProviderExecutionPreparation.Rejected -> return rejected(
                evaluation = evaluation,
                reason = preparation.reason,
            )
        }

        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.NETWORK_ONLY
        ) {
            return NetworkOnlyStrategyExecutor(clock).execute(
                request = request,
                evaluation = evaluation,
                providers = executionProviders,
            )
        }
        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.REMOTE_FIRST
        ) {
            return RemoteFirstStrategyExecutor(
                clock = clock,
                runtimeDependencies = runtimeDependencies,
                pipelineRegistry = pipelineRegistry,
                lifecycleEventEmitter = lifecycleEventEmitter,
            ).execute(
                request = request,
                evaluation = evaluation,
                providers = executionProviders,
            )
        }
        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.CACHE_FIRST
        ) {
            return CacheFirstStrategyExecutor(
                clock = clock,
                runtimeDependencies = runtimeDependencies,
                pipelineRegistry = pipelineRegistry,
                lifecycleEventEmitter = lifecycleEventEmitter,
            ).execute(
                request = request,
                evaluation = evaluation,
                providers = executionProviders,
            )
        }
        return rejected(
            evaluation = evaluation,
            reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
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
