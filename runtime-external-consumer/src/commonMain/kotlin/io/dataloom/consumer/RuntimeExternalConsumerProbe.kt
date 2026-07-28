package io.dataloom.consumer

import io.dataloom.api.execution.SynchronizationProviderSet
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.facade.DataLoom

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
