package io.dataloom.consumer

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.testing.identifier.ConstantIdentifierGenerator
import io.dataloom.testing.storage.InMemoryStorageProvider
import io.dataloom.testing.time.FixedDataLoomClock
import io.dataloom.testing.transport.ScriptedTransportProvider

/** Compile-only probe matching the getting-started walkthrough. */
public suspend fun gettingStartedExternalConsumerProbe(): String {
    val storageProvider = InMemoryStorageProvider()
    val transportProvider = ScriptedTransportProvider().apply {
        enqueuePullResult(ProviderOperationResult.Success(PullChangesResult.NoChanges()))
    }

    val bindings = SynchronizationProviderBindings(
        storageProviderId = storageProvider.descriptor.id,
        transportProviderId = transportProvider.descriptor.id,
    )

    val dataLoom = DataLoomBuilder()
        .runtimeDependencies(gettingStartedRuntimeDependencies())
        .providers(storageProvider, transportProvider)
        .defaultProviderBindings(bindings)
        .build()

    val initializeResult: ProviderLifecycleResult = dataLoom.initialize()
    if (initializeResult != ProviderLifecycleResult.InitializeSuccess) {
        return "Initialization did not complete: $initializeResult"
    }

    return try {
        when (val execution = dataLoom.synchronize(gettingStartedRequest())) {
            is SynchronizationExecutionResult.Executed -> when (val result = execution.result) {
                is SynchronizationResult.Succeeded -> "Synchronization completed: ${result.summary}"
                is SynchronizationResult.PartiallySucceeded ->
                    "Synchronization partially succeeded with ${result.errors.size} error(s)"
                is SynchronizationResult.Failed -> "Synchronization failed: ${result.error.code}"
                is SynchronizationResult.Cancelled -> "Synchronization cancelled at ${result.completedAt}"
                is SynchronizationResult.Skipped -> "Synchronization skipped: ${result.reason}"
            }

            is SynchronizationExecutionResult.Rejected ->
                "Synchronization rejected: ${execution.reason}"
        }
    } finally {
        dataLoom.shutdown()
    }
}

private fun gettingStartedRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
    clock = FixedDataLoomClock(DataLoomInstant(1_000L)),
    identifiers = RuntimeIdentifierGenerators(
        synchronizationEventIds = ConstantIdentifierGenerator(SynchronizationEventId("event-001")),
        queueEntryIds = ConstantIdentifierGenerator(QueueEntryId("queue-001")),
        queueLeaseIds = ConstantIdentifierGenerator(QueueLeaseId("lease-001")),
        conflictIds = ConstantIdentifierGenerator(ConflictId("conflict-001")),
    ),
)

private fun gettingStartedRequest(): SynchronizationRequest = SynchronizationRequest(
    workflowId = WorkflowId("contacts"),
    sessionId = SynchronizationSessionId("session-001"),
    direction = SynchronizationDirection.PULL,
    mode = SynchronizationMode.DELTA,
    context = ExecutionContext(
        executionId = ExecutionId("execution-001"),
        correlationId = CorrelationId("correlation-001"),
    ),
)
