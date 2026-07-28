package io.dataloom.consumer

import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.execution.SynchronizationProviderSet
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StrategyFallbackPlan
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/**
 * Compile-only use of the supported runtime surface from outside all SDK
 * implementation modules.
 */
internal suspend fun compileRuntimeConsumer(
    dataLoom: DataLoom,
    request: SynchronizationRequest,
    bindings: SynchronizationProviderBindings,
    dependencies: RuntimeDependencies,
    providers: SynchronizationProviderSet,
): List<ProviderBindingFailure> {
    val initialized: ProviderLifecycleResult = dataLoom.initialize()
    val execution: SynchronizationExecutionResult =
        dataLoom.synchronize(request, bindings)
    val shutdown: ProviderLifecycleResult = dataLoom.shutdown()

    dependencies.clock.now()
    providers.storageProvider
    initialized.toString()
    shutdown.toString()

    return when (execution) {
        is SynchronizationExecutionResult.Executed -> emptyList()
        is SynchronizationExecutionResult.Rejected -> execution.providerBindingFailures
    }
}

/** Compile-only use of the plan-aware strategy surface from an external module. */
internal suspend fun compileStrategyRuntimeConsumer(
    dataLoom: DataLoom,
    request: StrategySynchronizationRequest,
    bindings: StrategyProviderBindings,
    providers: StrategyProviderSet,
): StrategySynchronizationExecutionResult {
    providers.transportProvider
    return dataLoom.synchronize(request, bindings)
}

/** Compile-only use of the public remote-first fallback surface. */
internal fun compileRemoteFirstRuntimeConsumer(
    fallbackPlan: StrategyFallbackPlan,
    fallbackProvider: StrategyLocalFallbackProvider,
    remoteError: ClassifiedStrategyRemoteError,
    result: StrategySynchronizationExecutionResult,
): StrategyRemoteOutcome {
    fallbackPlan.operations
    fallbackPlan.remoteOutcomes
    fallbackProvider.descriptor
    if (result is StrategySynchronizationExecutionResult.FallbackActivated) {
        result.cacheState
        result.completedOperations
    }
    return remoteError.remoteOutcome
}
