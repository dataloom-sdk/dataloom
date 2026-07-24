package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictResolutionRequest

/**
 * Platform-independent orchestrator that coordinates conflict detection and
 * optional resolution for a single detection cycle.
 *
 * ## Purpose
 *
 * [SynchronizationConflictOrchestrator] connects [ConflictDetectorRegistry]
 * and [ConflictResolverRegistry] into a single deterministic operation. It
 * does not apply resolution decisions to storage, mutate payloads, invoke
 * synchronization pipelines, acknowledge outbound changes, advance
 * checkpoints, evaluate retry policy, schedule work, process queue entries,
 * dispatch synchronization events, or initialize providers.
 *
 * ## Required flow
 *
 * [detectAndResolve] follows a strict, deterministic sequence:
 *
 * 1. Look up the configured detector by exact [io.dataloom.api.identifier.ConflictDetectorId].
 * 2. If missing, return [ConflictOrchestrationResult.DetectorNotFound].
 * 3. Invoke the selected detector exactly once.
 * 4. Inspect the exact [io.dataloom.api.conflict.ConflictDetectionResult].
 * 5. If no conflict exists, return [ConflictOrchestrationResult.NoConflict].
 * 6. Preserve the exact [io.dataloom.api.conflict.SynchronizationConflict].
 * 7. If [ConflictOrchestrationBindings.resolverId] is `null`, return
 *    [ConflictOrchestrationResult.ResolverNotConfigured].
 * 8. Look up the resolver by exact [io.dataloom.api.identifier.ConflictResolverId].
 * 9. If missing, return [ConflictOrchestrationResult.ResolverNotFound].
 * 10. Build the exact [ConflictResolutionRequest] required by DL-014.
 * 11. Invoke the selected resolver exactly once.
 * 12. Preserve and return the exact [io.dataloom.api.conflict.ConflictResolutionDecision].
 *
 * ## Caller responsibility
 *
 * Callers must supply a complete [io.dataloom.api.conflict.ConflictDetectionRequest]
 * according to the DL-014 contract. The orchestrator does not read local
 * application storage, query transport providers, or generate payload content.
 *
 * ## Exception and cancellation boundary
 *
 * Unexpected exceptions from [io.dataloom.api.conflict.ConflictDetector] or
 * [io.dataloom.api.conflict.ConflictResolver] propagate normally. They are
 * never converted into a [ConflictOrchestrationResult] variant.
 *
 * Because the DL-014 detector and resolver contracts are synchronous
 * (`fun`, not `suspend fun`), [kotlin.coroutines.cancellation.CancellationException]
 * does not apply to their invocations. If they become suspend in a future
 * contract revision, cancellation must still propagate normally.
 *
 * ## Pipeline and storage boundary
 *
 * This orchestrator must not call:
 * - [io.dataloom.api.provider.StorageProvider]
 * - [io.dataloom.api.provider.TransportProvider]
 * - [io.dataloom.api.scheduling.SchedulerProvider]
 * - [io.dataloom.api.connectivity.ConnectivityProvider]
 * - [io.dataloom.api.provider.QueueProvider]
 * - [io.dataloom.api.retry.RetryPolicy]
 * - [io.dataloom.runtime.execution.SynchronizationPipeline]
 * - [io.dataloom.runtime.execution.SynchronizationExecutionCoordinator]
 * - Any lifecycle coordinator
 * - Any event dispatcher or observer
 *
 * ## Retry boundary
 *
 * This orchestrator does not invoke [io.dataloom.api.retry.RetryPolicy] when:
 * - The detector is absent.
 * - The resolver is absent.
 * - The detector throws.
 * - The resolver throws.
 * - The resolver returns an unresolved or deferred decision.
 *
 * ## Event boundary
 *
 * This orchestrator does not dispatch conflict events, emit
 * [io.dataloom.api.synchronization.SynchronizationEvent] instances, own a
 * [kotlinx.coroutines.CoroutineScope], or use Flow, StateFlow, SharedFlow, or
 * Channel. The result contains sufficient structural information for a future
 * event layer to emit conflict events safely.
 *
 * ## Performance
 *
 * - Exactly one detector is looked up and invoked.
 * - Resolver lookup occurs only after conflict detection.
 * - Exactly one resolver is invoked when a conflict is detected and a resolver
 *   ID is configured and found.
 * - No payload-sized buffers are allocated.
 * - No serialization or deserialization is performed.
 * - No reflection is used.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and runtime types only. Safe
 * for use in Kotlin Multiplatform common code.
 *
 * @param detectorRegistry the immutable registry of available
 *   [io.dataloom.api.conflict.ConflictDetector] instances.
 * @param resolverRegistry the immutable registry of available
 *   [io.dataloom.api.conflict.ConflictResolver] instances.
 */
public class SynchronizationConflictOrchestrator(
    private val detectorRegistry: ConflictDetectorRegistry,
    private val resolverRegistry: ConflictResolverRegistry,
) {

    /**
     * Performs conflict detection using the configured detector and, when a
     * conflict is found and a resolver is configured, resolves the conflict
     * using the configured resolver.
     *
     * Follows the deterministic flow documented on
     * [SynchronizationConflictOrchestrator]:
     *
     * 1. Look up the detector by exact [ConflictOrchestrationBindings.detectorId].
     * 2. If missing, return [ConflictOrchestrationResult.DetectorNotFound].
     * 3. Invoke the detector exactly once.
     * 4. If no conflict, return [ConflictOrchestrationResult.NoConflict].
     * 5. If [ConflictOrchestrationBindings.resolverId] is `null`, return
     *    [ConflictOrchestrationResult.ResolverNotConfigured].
     * 6. Look up the resolver by exact [ConflictOrchestrationBindings.resolverId].
     * 7. If missing, return [ConflictOrchestrationResult.ResolverNotFound].
     * 8. Invoke the resolver exactly once with a [ConflictResolutionRequest]
     *    built from the detected conflict and the originating synchronization
     *    request.
     * 9. Preserve and return the exact [io.dataloom.api.conflict.ConflictResolutionDecision].
     *
     * Unexpected exceptions from the detector or resolver propagate normally
     * and are never converted into a result variant.
     *
     * @param request the [ConflictOrchestrationRequest] carrying the detection
     *   request and bindings.
     * @return the [ConflictOrchestrationResult] describing the terminal outcome
     *   of this orchestration cycle.
     */
    public fun detectAndResolve(
        request: ConflictOrchestrationRequest,
    ): ConflictOrchestrationResult {
        val detectorId = request.bindings.detectorId
        val detector = detectorRegistry.lookup(detectorId)
            ?: return ConflictOrchestrationResult.DetectorNotFound(detectorId = detectorId)

        val detectionResult = detector.detect(request.detectionRequest)

        return when (detectionResult) {
            is ConflictDetectionResult.NoConflict ->
                ConflictOrchestrationResult.NoConflict(detectorId = detectorId)

            is ConflictDetectionResult.ConflictDetected -> {
                val conflict = detectionResult.conflict
                val resolverId = request.bindings.resolverId
                    ?: return ConflictOrchestrationResult.ResolverNotConfigured(
                        conflict = conflict,
                        detectorId = detectorId,
                    )

                val resolver = resolverRegistry.lookup(resolverId)
                    ?: return ConflictOrchestrationResult.ResolverNotFound(
                        conflict = conflict,
                        resolverId = resolverId,
                    )

                val resolutionRequest = ConflictResolutionRequest(
                    synchronizationRequest = request.detectionRequest.synchronizationRequest,
                    conflict = conflict,
                )

                val decision = resolver.resolve(resolutionRequest)

                ConflictOrchestrationResult.Resolved(
                    conflict = conflict,
                    decision = decision,
                    detectorId = detectorId,
                    resolverId = resolverId,
                )
            }
        }
    }

    /**
     * Returns a safe diagnostic string listing the registry sizes.
     *
     * Does not invoke any detector or resolver `toString()` method.
     */
    override fun toString(): String =
        "SynchronizationConflictOrchestrator(" +
            "detectorCount=${detectorRegistry.detectors.size}, " +
            "resolverCount=${resolverRegistry.resolvers.size}" +
            ")"
}
