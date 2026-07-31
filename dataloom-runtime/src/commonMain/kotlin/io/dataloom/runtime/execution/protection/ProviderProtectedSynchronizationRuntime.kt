package io.dataloom.runtime.execution.protection

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.execution.SynchronizationProviderSet
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.retry.ProtectedStorageOperations
import io.dataloom.runtime.retry.ProtectedTransportOperations

/**
 * Result of one existing synchronization pipeline executed through protected
 * storage and transport provider bridges.
 *
 * [operationEvidence] is defensively copied and contains no provider return
 * values or payload data. It preserves the exact provider-execution and circuit
 * recording boundaries required for safe retry and reconciliation decisions.
 */
public class ProviderProtectedSynchronizationResult(
    /** Exact terminal result returned by the existing synchronization pipeline. */
    public val synchronizationResult: SynchronizationResult,
    operationEvidence: List<ProviderProtectionOperationEvidence>,
) {
    /** Defensive immutable evidence snapshot in provider invocation order. */
    public val operationEvidence: List<ProviderProtectionOperationEvidence> =
        operationEvidence.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderProtectedSynchronizationResult) return false
        return synchronizationResult == other.synchronizationResult &&
            operationEvidence == other.operationEvidence
    }

    override fun hashCode(): Int =
        31 * synchronizationResult.hashCode() + operationEvidence.hashCode()

    /** Bounded diagnostics that do not render provider values or payloads. */
    override fun toString(): String =
        "ProviderProtectedSynchronizationResult(" +
            "status=${synchronizationStatus(synchronizationResult)}, " +
            "operationEvidenceCount=${operationEvidence.size}" +
            ")"
}

/**
 * Additive runtime for executing an existing [SynchronizationPipeline] through
 * timeout- and circuit-protected storage and transport operation surfaces.
 *
 * The pipeline contract and its terminal [SynchronizationResult] remain
 * unchanged. Pipeline-local bridges adapt each enriched circuit result back to
 * the historical provider result only after recording bounded exact evidence.
 * An executed provider operation whose later circuit recording is unconfirmed
 * is converted to a fail-closed `Recoverability.UNKNOWN` provider result so the
 * pipeline cannot silently continue or authorize automatic replay.
 */
public object ProviderProtectedSynchronizationRuntime {

    /**
     * Executes [pipeline] once with protected storage and transport providers.
     *
     * Construction and validation perform no provider operation, state-store
     * access, clock read, timeout execution, identifier generation, I/O, or
     * coroutine launch. Caller cancellation and unexpected exceptions propagate.
     */
    public suspend fun execute(
        context: SynchronizationExecutionContext,
        pipeline: SynchronizationPipeline,
        storageOperations: ProtectedStorageOperations,
        transportOperations: ProtectedTransportOperations,
    ): ProviderProtectedSynchronizationResult {
        require(pipeline.direction == context.request.direction) {
            "Protected pipeline direction must match the synchronization request direction."
        }
        require(
            storageOperations.descriptor.id == context.providers.storageProvider.descriptor.id,
        ) {
            "Protected storage operations must match the resolved storage provider."
        }
        require(
            transportOperations.descriptor.id == context.providers.transportProvider.descriptor.id,
        ) {
            "Protected transport operations must match the resolved transport provider."
        }

        val evidenceCollector = ProviderProtectionEvidenceCollector()
        val protectedProviderSet = ProviderProtectedSynchronizationProviderSet(
            original = context.providers,
            storageProvider = ProviderProtectionStorageBridge(
                protectedOperations = storageOperations,
                evidenceCollector = evidenceCollector,
            ),
            transportProvider = ProviderProtectionTransportBridge(
                protectedOperations = transportOperations,
                evidenceCollector = evidenceCollector,
            ),
        )
        val protectedContext = SynchronizationExecutionContext(
            request = context.request,
            providers = protectedProviderSet,
            runtimeDependencies = context.runtimeDependencies,
            lifecycleEventEmitter = context.lifecycleEventEmitter,
        )

        val result = pipeline.execute(protectedContext)
        return ProviderProtectedSynchronizationResult(
            synchronizationResult = result,
            operationEvidence = evidenceCollector.snapshot(),
        )
    }
}

private class ProviderProtectedSynchronizationProviderSet(
    original: SynchronizationProviderSet,
    override val storageProvider: StorageProvider,
    override val transportProvider: TransportProvider,
) : SynchronizationProviderSet {
    override val schedulerProvider: SchedulerProvider? = original.schedulerProvider
    override val connectivityProvider: ConnectivityProvider? = original.connectivityProvider
    override val queueProvider: QueueProvider? = original.queueProvider
}

private fun synchronizationStatus(result: SynchronizationResult): String = when (result) {
    is SynchronizationResult.Succeeded -> "SUCCEEDED"
    is SynchronizationResult.PartiallySucceeded -> "PARTIALLY_SUCCEEDED"
    is SynchronizationResult.Failed -> "FAILED"
    is SynchronizationResult.Cancelled -> "CANCELLED"
    is SynchronizationResult.Skipped -> "SKIPPED"
}
