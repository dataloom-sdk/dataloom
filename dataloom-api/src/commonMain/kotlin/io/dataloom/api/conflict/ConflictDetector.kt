package io.dataloom.api.conflict

import io.dataloom.api.identifier.ConflictDetectorId

/**
 * Platform-independent contract for evaluating whether a synchronization
 * conflict exists between local and remote changes.
 *
 * A [ConflictDetector] receives a [ConflictDetectionRequest] containing
 * already-available local and remote change information and returns a
 * [ConflictDetectionResult] indicating whether a conflict was detected.
 *
 * ## Synchronous evaluation
 *
 * Detection operates on already-available change information. It must be
 * synchronous and deterministic for the same input. Detection must not:
 * - Query storage.
 * - Call remote services.
 * - Refresh authentication.
 * - Wait for user input.
 * - Sleep or schedule background work.
 * - Apply changes.
 * - Persist decisions.
 * - Load missing application data.
 *
 * This keeps detection deterministic, fast, testable, multiplatform, and
 * independent of runtime infrastructure.
 *
 * ## Application ownership
 *
 * DataLoom coordinates the conflict-detection workflow, but the host
 * application owns the domain-specific rules that determine whether a conflict
 * exists. DataLoom does not supply a built-in detector implementation in this
 * release.
 *
 * ## Payload opacity
 *
 * Generic detectors must not inspect opaque [io.dataloom.api.payload.DataLoomPayload]
 * content. Application-provided detectors may interpret payload content only
 * through application-controlled serialization outside DataLoom core.
 *
 * ## Version opacity
 *
 * [io.dataloom.api.payload.EntityVersion] is opaque. DataLoom does not assume
 * numeric ordering, timestamps, or ETag semantics.
 *
 * ## Implementation requirements
 *
 * Implementations:
 * - Must expose a stable [id].
 * - Must not expose coroutine scopes or dispatchers.
 * - Must not perform database or network access.
 * - Must not call providers.
 * - Must not modify application data or queues.
 * - Must not automatically log payload content.
 * - Must not generate conflict identifiers unless explicitly configured
 *   through application-controlled configuration.
 * - Must not depend on platform-specific types.
 *
 * Applications may provide implementations using manual construction or any
 * dependency-injection framework they choose.
 */
public interface ConflictDetector {

    /**
     * Stable identifier for this detector implementation.
     *
     * The value must be non-blank and meaningful to the host application.
     */
    public val id: ConflictDetectorId

    /**
     * Evaluates whether a conflict exists between the local and remote changes
     * in [request].
     *
     * Detection must be synchronous and deterministic for the same [request].
     * It must not access storage, network, or perform any I/O.
     *
     * @param request the [ConflictDetectionRequest] carrying the local and
     *   remote change events to evaluate.
     * @return [ConflictDetectionResult.NoConflict] when no conflict is found,
     *   or [ConflictDetectionResult.ConflictDetected] containing the canonical
     *   [SynchronizationConflict].
     */
    public fun detect(request: ConflictDetectionRequest): ConflictDetectionResult
}
