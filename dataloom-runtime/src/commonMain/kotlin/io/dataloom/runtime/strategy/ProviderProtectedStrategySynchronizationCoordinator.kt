package io.dataloom.runtime.strategy

import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyExecutionTrigger
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.TransportProvider
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.StrategyProviderResolutionResult
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter
import io.dataloom.runtime.execution.protection.ProviderProtectionEvidenceCollector
import io.dataloom.runtime.execution.protection.ProviderProtectionStorageBridge
import io.dataloom.runtime.execution.protection.ProviderProtectionTransportBridge
import io.dataloom.runtime.retry.ProtectedStorageOperations
import io.dataloom.runtime.retry.ProtectedTransportOperations

/**
 * Strategy admission and execution through explicit storage and transport
 * timeout/circuit boundaries.
 *
 * The coordinator preserves the existing strategy evaluator, provider
 * resolution, trigger/input validation, network-only executor, and remote-first
 * executor. Only provider instances are replaced with execution-local protected
 * bridges after lifecycle, plan, and binding admission succeeds.
 *
 * Protected local fallback is deliberately rejected until a separately scoped
 * local-fallback operation is implemented. The runtime never bypasses the
 * circuit boundary by casting the raw storage provider for fallback.
 */
internal class ProviderProtectedStrategySynchronizationCoordinator(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val evaluator: BuiltInSynchronizationStrategyEvaluator,
    private val providerResolver: StrategyProviderResolver,
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?,
    private val storageOperations: ProtectedStorageOperations,
    private val transportOperations: ProtectedTransportOperations,
) {

    /** Executes one strategy request with exact ordered provider evidence. */
    suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult {
        val evaluation = evaluator.evaluate(request.evaluationRequest())

        if (lifecycleCoordinator.state != ProviderLifecycleCoordinatorState.INITIALIZED) {
            return result(
                rejected(evaluation, StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED),
            )
        }

        when (evaluation.plan.disposition) {
            StrategyDisposition.REJECT -> return result(
                rejected(evaluation, StrategyExecutionRejectionReason.STRATEGY_REJECTED),
            )
            StrategyDisposition.DEFER -> return result(
                StrategySynchronizationExecutionResult.Deferred(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                ),
            )
            StrategyDisposition.EXECUTE,
            StrategyDisposition.SERVE_AND_REFRESH,
            -> Unit
        }

        if (request.trigger == StrategyExecutionTrigger.DURABLE_QUEUE) {
            return result(
                rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_TRIGGER),
            )
        }

        when (evaluation.plan.effectiveStrategy) {
            BuiltInSynchronizationStrategy.NETWORK_ONLY -> {
                if (request.input !is StrategyOperationInput.DirectTransport) {
                    return result(
                        rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT),
                    )
                }
            }
            BuiltInSynchronizationStrategy.REMOTE_FIRST -> {
                if (request.input !is StrategyOperationInput.ProviderBacked) {
                    return result(
                        rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT),
                    )
                }
                if (requiresLocalFallback(evaluation)) {
                    return result(
                        rejected(
                            evaluation,
                            StrategyExecutionRejectionReason.PROTECTED_LOCAL_FALLBACK_UNSUPPORTED,
                        ),
                    )
                }
            }
            else -> return result(
                rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN),
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
                return result(
                    StrategySynchronizationExecutionResult.Rejected(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        reason = StrategyExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                        missingCapabilities = resolution.missingCapabilities,
                        bindingFailures = resolution.bindingFailures,
                    ),
                )
            }
        }

        if (!providersMatchProtection(providers)) {
            return result(
                rejected(evaluation, StrategyExecutionRejectionReason.PROTECTED_PROVIDER_MISMATCH),
            )
        }

        val evidenceCollector = ProviderProtectionEvidenceCollector()
        val protectedProviders = ProviderProtectedStrategyProviderSet(
            original = providers,
            storageProvider = providers.storageProvider?.let {
                ProviderProtectionStorageBridge(
                    protectedOperations = storageOperations,
                    evidenceCollector = evidenceCollector,
                )
            },
            transportProvider = providers.transportProvider?.let {
                ProviderProtectionTransportBridge(
                    protectedOperations = transportOperations,
                    evidenceCollector = evidenceCollector,
                )
            },
        )

        val strategyResult = when (evaluation.plan.effectiveStrategy) {
            BuiltInSynchronizationStrategy.NETWORK_ONLY ->
                NetworkOnlyStrategyExecutor(clock).execute(
                    request = request,
                    evaluation = evaluation,
                    providers = protectedProviders,
                )

            BuiltInSynchronizationStrategy.REMOTE_FIRST ->
                RemoteFirstStrategyExecutor(
                    clock = clock,
                    runtimeDependencies = runtimeDependencies,
                    pipelineRegistry = pipelineRegistry,
                    lifecycleEventEmitter = lifecycleEventEmitter,
                ).execute(
                    request = request,
                    evaluation = evaluation,
                    providers = protectedProviders,
                )

            else -> rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        return ProviderProtectedStrategySynchronizationResult(
            strategyResult = strategyResult,
            operationEvidence = evidenceCollector.snapshot(),
        )
    }

    private fun providersMatchProtection(providers: StrategyProviderSet): Boolean {
        val storageMatches = providers.storageProvider?.descriptor?.id?.let {
            it == storageOperations.descriptor.id
        } ?: true
        val transportMatches = providers.transportProvider?.descriptor?.id?.let {
            it == transportOperations.descriptor.id
        } ?: true
        return storageMatches && transportMatches
    }

    private fun requiresLocalFallback(
        evaluation: io.dataloom.api.strategy.StrategyEvaluationResult,
    ): Boolean =
        evaluation.plan.fallbackPlan != null ||
            StrategyOperation.SERVE_LOCAL in evaluation.plan.operations

    private fun rejected(
        evaluation: io.dataloom.api.strategy.StrategyEvaluationResult,
        reason: StrategyExecutionRejectionReason,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Rejected(
            evaluation = evaluation,
            completedAt = clock.now(),
            reason = reason,
        )

    private fun result(
        strategyResult: StrategySynchronizationExecutionResult,
    ): ProviderProtectedStrategySynchronizationResult =
        ProviderProtectedStrategySynchronizationResult(
            strategyResult = strategyResult,
            operationEvidence = emptyList(),
        )
}

private class ProviderProtectedStrategyProviderSet(
    original: StrategyProviderSet,
    override val storageProvider: StorageProvider?,
    override val transportProvider: TransportProvider?,
) : StrategyProviderSet {
    override val schedulerProvider: SchedulerProvider? = original.schedulerProvider
    override val connectivityProvider: ConnectivityProvider? = original.connectivityProvider
    override val queueProvider: QueueProvider? = original.queueProvider
}
