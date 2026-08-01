package io.dataloom.consumer

import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.retry.RetryOperation
import io.dataloom.runtime.connectivity.SynchronizationConnectivityConfiguration
import io.dataloom.runtime.facade.DataLoomProtectedSynchronization
import io.dataloom.runtime.queue.ProviderProtectedQueueEntryExecutionResult
import io.dataloom.runtime.queue.ProviderProtectedQueuedSynchronizationExecutionHandler
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator

/** External-consumer compilation probe for evidence-preserving queued execution. */
public object ProviderProtectedQueuedExecutionExternalConsumerProbe {

    public fun createHandler(
        resolver: QueuedSynchronizationWorkResolver,
        protectedSynchronization: DataLoomProtectedSynchronization,
        retryEvaluator: SynchronizationRetryEvaluator,
        retryOperation: RetryOperation,
        connectivityConfiguration: SynchronizationConnectivityConfiguration? = null,
    ): ProviderProtectedQueuedSynchronizationExecutionHandler =
        ProviderProtectedQueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            protectedSynchronization = protectedSynchronization,
            retryEvaluator = retryEvaluator,
            retryOperation = retryOperation,
            connectivityConfiguration = connectivityConfiguration,
        )

    public suspend fun execute(
        handler: ProviderProtectedQueuedSynchronizationExecutionHandler,
        entry: QueueEntry,
    ): ProviderProtectedQueueEntryExecutionResult = handler.execute(entry)

    public suspend fun executeWithExplicitBindings(
        protectedSynchronization: DataLoomProtectedSynchronization,
        entry: QueueEntry,
        bindings: SynchronizationProviderBindings,
    ) = protectedSynchronization.synchronize(
        request = entry.synchronizationRequest,
        bindings = bindings,
    )
}
