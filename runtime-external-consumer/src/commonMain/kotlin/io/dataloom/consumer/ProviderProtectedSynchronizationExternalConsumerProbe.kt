package io.dataloom.consumer

import io.dataloom.api.retry.RetryOperation
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationResult
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationRuntime
import io.dataloom.runtime.retry.ProtectedStorageOperations
import io.dataloom.runtime.retry.ProtectedTransportOperations

/** External JVM/iOS compile probe for protected existing-pipeline execution. */
public object ProviderProtectedSynchronizationExternalConsumerProbe {

    public suspend fun execute(
        context: SynchronizationExecutionContext,
        pipeline: SynchronizationPipeline,
        storageOperations: ProtectedStorageOperations,
        transportOperations: ProtectedTransportOperations,
    ): ProviderProtectedSynchronizationResult =
        ProviderProtectedSynchronizationRuntime.execute(
            context = context,
            pipeline = pipeline,
            storageOperations = storageOperations,
            transportOperations = transportOperations,
        )

    public fun operationOrder(
        result: ProviderProtectedSynchronizationResult,
    ): List<RetryOperation> = result.operationEvidence.map { it.operation }
}
