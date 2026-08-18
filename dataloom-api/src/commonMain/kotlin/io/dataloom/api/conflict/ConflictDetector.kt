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
 * ## Reference and application-owned detectors
 *
 * The DataLoom runtime provides deterministic reference detectors selected by
 * exact [ConflictDetectorId] values for structural operation pairs, explicit
 * entity-version mismatch, timestamp evidence, three-way ETag divergence,
 * vector-clock concurrency, and application-owned metadata markers. Reference
 * detectors never inspect payloads or infer business meaning.
 *
 * A reference detector is never selected implicitly. The host still chooses
 * its exact ID through conflict-orchestration bindings and may explicitly
 * override it by registering an application detector under the same ID.
 *
 * Applications continue to own domain-specific detection rules. Examples
 * include financial invariants, aggregate-level conflicts, schema-aware field
 * relationships, or evidence encoded in opaque payload content. A custom
 * detector uses this same contract and does not replace DataLoom's surrounding
 * orchestration and durable-recording boundaries.
 *
 * ## Payload opacity
 *
 * Generic detectors must not inspect opaque [io.dataloom.api.payload.DataLoomPayload]
 * content. Application-provided detectors may interpret payload content only
 * through application-controlled serialization outside DataLoom core.
 *
 * ## Version opacity
 *
 * [io.dataloom.api.payload.EntityVersion] is opaque. The version reference
 * detector compares explicit values only for equality; it never parses,
 * numerically orders, or assigns ETag/timestamp semantics to them. Dedicated
 * timestamp, ETag, and vector-clock reference detectors use separately named,
 * explicit metadata evidence.
 *
 * ## Evidence-unavailable behavior
 *
 * The reference evidence-based detectors fail closed: when required evidence
 * is missing, blank, malformed, duplicated, negative, or over its documented
 * bound, they return a `CUSTOM` conflict with a bounded reason code rather than
 * silently returning [ConflictDetectionResult.NoConflict]. Raw evidence values
 * are not copied into generated conflict metadata.
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
 * - Must not generate conflict identifiers unless their identity discipline is
 *   explicit, deterministic, and application-controlled or documented by the
 *   reference implementation.
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
