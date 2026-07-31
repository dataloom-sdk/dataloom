package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationCoordinator

/** Immutable facade capability assembled by [DataLoomBuilder]. */
internal class DefaultDataLoomProtectedSynchronization(
    private val coordinator: ProviderProtectedSynchronizationCoordinator,
    private val defaultBindings: SynchronizationProviderBindings,
) : DataLoomProtectedSynchronization {

    override suspend fun synchronize(
        request: SynchronizationRequest,
    ): ProviderProtectedSynchronizationExecutionResult =
        coordinator.execute(request, defaultBindings)
}
