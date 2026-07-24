package io.dataloom.runtime.conflict

/**
 * Canonical status values produced by [SynchronizationConflictOrchestrator].
 *
 * Each variant represents a distinct terminal outcome of a single
 * [SynchronizationConflictOrchestrator.detectAndResolve] invocation.
 *
 * ## Ordinal stability
 *
 * Do not rely on enum ordinals for serialization or persistence. Ordinals may
 * change as variants are added or reordered. Use the variant names for any
 * durable representation.
 *
 * ## Coroutine cancellation
 *
 * [CancellationException][kotlin.coroutines.cancellation.CancellationException]
 * is never classified as a [ConflictOrchestrationStatus]. Thrown cancellation
 * propagates normally out of the orchestrator.
 *
 * ## Unexpected exceptions
 *
 * Programming errors from [io.dataloom.api.conflict.ConflictDetector] or
 * [io.dataloom.api.conflict.ConflictResolver] are not classified as a status.
 * They propagate normally.
 */
public enum class ConflictOrchestrationStatus {

    /**
     * The explicitly configured [io.dataloom.api.identifier.ConflictDetectorId]
     * was not found in the [ConflictDetectorRegistry].
     *
     * No detector was invoked. No resolver lookup occurred. No resolver was
     * invoked.
     */
    DETECTOR_NOT_FOUND,

    /**
     * The detector completed and reported no conflict between the local and
     * remote changes.
     *
     * No resolver lookup occurred. No resolver was invoked.
     */
    NO_CONFLICT,

    /**
     * A conflict was detected but [ConflictOrchestrationBindings.resolverId]
     * is `null`, meaning automatic resolution is not configured.
     *
     * The exact [io.dataloom.api.conflict.SynchronizationConflict] is
     * preserved in [ConflictOrchestrationResult.ResolverNotConfigured].
     *
     * No resolver lookup occurred. No resolver was invoked.
     */
    RESOLVER_NOT_CONFIGURED,

    /**
     * A conflict was detected and a [io.dataloom.api.identifier.ConflictResolverId]
     * was supplied, but no matching resolver exists in the
     * [ConflictResolverRegistry].
     *
     * The exact [io.dataloom.api.conflict.SynchronizationConflict] is
     * preserved in [ConflictOrchestrationResult.ResolverNotFound].
     *
     * No resolver was invoked.
     */
    RESOLVER_NOT_FOUND,

    /**
     * A conflict was detected, the configured resolver was found, and the
     * resolver returned a [io.dataloom.api.conflict.ConflictResolutionDecision].
     *
     * The exact decision is preserved in [ConflictOrchestrationResult.Resolved].
     */
    RESOLVED,
}
