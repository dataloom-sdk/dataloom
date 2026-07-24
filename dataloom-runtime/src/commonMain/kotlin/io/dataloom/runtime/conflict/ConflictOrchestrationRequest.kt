package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictDetectionRequest

/**
 * Immutable request supplied to [SynchronizationConflictOrchestrator] for a
 * single conflict detection and optional resolution cycle.
 *
 * ## Purpose
 *
 * [ConflictOrchestrationRequest] pairs a [ConflictDetectionRequest] with the
 * [ConflictOrchestrationBindings] that determine which detector and resolver
 * to invoke.
 *
 * ## Construction
 *
 * Construction preserves both the [detectionRequest] and [bindings] exactly as
 * supplied. It performs no registry lookup, no detector or resolver invocation,
 * and does not inspect or mutate payload content or version information.
 *
 * ## Callers
 *
 * Callers are responsible for supplying a complete [ConflictDetectionRequest]
 * according to the DL-014 contract. The orchestrator does not read local
 * application storage, query transport providers, or generate payload content.
 *
 * ## Equality
 *
 * Equality is value-based, comparing [detectionRequest] and [bindings].
 *
 * ## Diagnostics
 *
 * The diagnostic representation exposes only the detector ID, resolver ID, and
 * the entity reference from the detection request. It does not expose local
 * payload content, remote payload content, credentials, authorization values,
 * encryption keys, personal data, checkpoint tokens, stack traces, or detector
 * or resolver implementation `toString()` output.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param detectionRequest the [ConflictDetectionRequest] carrying the local
 *   and remote change events to evaluate.
 * @param bindings the [ConflictOrchestrationBindings] identifying the detector
 *   and optional resolver to invoke.
 */
public data class ConflictOrchestrationRequest(
    /** The [ConflictDetectionRequest] to evaluate. */
    public val detectionRequest: ConflictDetectionRequest,

    /** The [ConflictOrchestrationBindings] for this orchestration cycle. */
    public val bindings: ConflictOrchestrationBindings,
) {
    /**
     * Returns a safe diagnostic string including the detector ID, resolver ID,
     * and entity reference.
     *
     * Does not expose local or remote payload content, credentials,
     * authorization values, encryption keys, personal data, checkpoint tokens,
     * stack traces, or detector or resolver `toString()` output.
     */
    override fun toString(): String =
        "ConflictOrchestrationRequest(" +
            "detectorId=${bindings.detectorId.value}, " +
            "resolverId=${bindings.resolverId?.value}, " +
            "entityType=${detectionRequest.localChange.entity.type.value}, " +
            "entityId=${detectionRequest.localChange.entity.id.value}" +
            ")"
}
