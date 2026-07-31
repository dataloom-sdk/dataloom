package io.dataloom.runtime.execution.protection

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderResolutionResult
import io.dataloom.core.provider.SynchronizationProviderResolver
import io.dataloom.runtime.connectivity.ConnectivityPreflightResult
import io.dataloom.runtime.connectivity.SynchronizationConnectivityConfiguration
import io.dataloom.runtime.connectivity.SynchronizationConnectivityPreflight
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult
import io.dataloom.runtime.retry.ProtectedStorageOperations
import io.dataloom.runtime.retry.ProtectedTransportOperations

/**
 * Internal admission coordinator for one protected direct synchronization call.
 *
 * It uses the same lifecycle, provider resolver, pipeline registry, connectivity
 * preflight, runtime dependencies, and lifecycle emitter as direct execution.
 * Construction performs no provider, state-store, clock, timeout, I/O,
 * identifier, event, or coroutine activity.
 */
internal class ProviderProtectedSynchronizationCoordinator(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val providerResolver: SynchronizationProviderResolver,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val runtimeDependencies: RuntimeDependencies,
    private val storageOperations: ProtectedStorageOperations,
    private val transportOperations: ProtectedTransportOperations,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter? = null,
    private val connectivityConfiguration: SynchronizationConnectivityConfiguration =
        SynchronizationConnectivityConfiguration.NONE,
    private val connectivityPreflight: SynchronizationConnectivityPreflight =
        SynchronizationConnectivityPreflight(),
) {

    /** Executes the deterministic lifecycle, resolution, pipeline, and connectivity sequence. */
    suspend fun execute(
        request: SynchronizationRequest,
        bindings: SynchronizationProviderBindings,
    ): ProviderProtectedSynchronizationExecutionResult {
        if (lifecycleCoordinator.state != ProviderLifecycleCoordinatorState.INITIALIZED) {
            return rejected(SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED)
        }

        val resolved = when (val resolution = providerResolver.resolve(bindings)) {
            is ProviderResolutionResult.Success -> resolution.providers
            is ProviderResolutionResult.Failure -> {
                return ProviderProtectedSynchronizationExecutionResult.Rejected(
                    SynchronizationExecutionResult.Rejected(
                        reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                        providerBindingFailures = resolution.bindingFailures,
                    ),
                )
            }
        }

        val pipeline = pipelineRegistry.lookup(request.direction)
            ?: return rejected(SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND)

        when (
            val preflightResult = connectivityPreflight.evaluate(
                requirement = connectivityConfiguration.requirement,
                provider = resolved.connectivityProvider,
                request = request,
            )
        ) {
            ConnectivityPreflightResult.NotRequired,
            is ConnectivityPreflightResult.Satisfied,
            -> Unit

            ConnectivityPreflightResult.ProviderNotConfigured ->
                return rejected(
                    SynchronizationExecutionRejectionReason.CONNECTIVITY_PROVIDER_NOT_CONFIGURED,
                )

            is ConnectivityPreflightResult.RequirementNotMet ->
                return rejected(
                    SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                )

            is ConnectivityPreflightResult.CheckFailed ->
                return ProviderProtectedSynchronizationExecutionResult.Rejected(
                    SynchronizationExecutionResult.Rejected(
                        reason = SynchronizationExecutionRejectionReason.CONNECTIVITY_CHECK_FAILED,
                        connectivityCheckError = preflightResult.error,
                    ),
                )
        }

        val context = SynchronizationExecutionContext(
            request = request,
            providers = resolved,
            runtimeDependencies = runtimeDependencies,
            lifecycleEventEmitter = lifecycleEventEmitter,
        )

        lifecycleEventEmitter?.emitStarted(context)
        val protectedResult = ProviderProtectedSynchronizationRuntime.execute(
            context = context,
            pipeline = pipeline,
            storageOperations = storageOperations,
            transportOperations = transportOperations,
        )
        lifecycleEventEmitter?.emitCompleted(
            context = context,
            result = protectedResult.synchronizationResult,
        )

        return ProviderProtectedSynchronizationExecutionResult.Executed(protectedResult)
    }

    private fun rejected(
        reason: SynchronizationExecutionRejectionReason,
    ): ProviderProtectedSynchronizationExecutionResult =
        ProviderProtectedSynchronizationExecutionResult.Rejected(
            SynchronizationExecutionResult.Rejected(reason = reason),
        )
}
