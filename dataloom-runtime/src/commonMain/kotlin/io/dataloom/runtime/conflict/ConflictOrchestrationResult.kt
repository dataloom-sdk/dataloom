package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictResolverId

/**
 * Sealed result produced by a single
 * [SynchronizationConflictOrchestrator.detectAndResolve] invocation.
 *
 * ## Variants
 *
 * - [DetectorNotFound] — the configured detector ID was absent from the registry.
 * - [NoConflict] — the detector found no conflict.
 * - [ResolverNotConfigured] — a conflict was detected but no resolver ID was
 *   configured in [ConflictOrchestrationBindings].
 * - [ResolverNotFound] — a conflict was detected and a resolver ID was
 *   configured, but no matching resolver exists in the registry.
 * - [Resolved] — a conflict was detected and the resolver returned a decision.
 *
 * ## No raw Throwable exposure
 *
 * No variant exposes a raw [Throwable] or stack trace. Unexpected exceptions
 * from detectors or resolvers propagate normally and are never captured as a
 * result variant.
 *
 * ## Immutability
 *
 * No variant mutates [SynchronizationConflict] or [ConflictResolutionDecision].
 * All nested contracts are preserved exactly as received from the detector or
 * resolver.
 *
 * ## Security
 *
 * Default `toString()` is overridden on variants that carry
 * [SynchronizationConflict] or [ConflictResolutionDecision] to avoid
 * accidental exposure of payload content. Safe diagnostics include only
 * structural identifiers: conflict IDs, detector IDs, resolver IDs, and
 * variant names.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface ConflictOrchestrationResult {

    /**
     * The explicitly configured detector ID was not found in the
     * [ConflictDetectorRegistry].
     *
     * No detector was invoked. No resolver lookup occurred. No resolver was
     * invoked.
     *
     * @param detectorId the [ConflictDetectorId] that was requested but absent.
     */
    public data class DetectorNotFound(
        /** The [ConflictDetectorId] that was requested but not found. */
        public val detectorId: ConflictDetectorId,
    ) : ConflictOrchestrationResult

    /**
     * The detector completed and reported no conflict between the local and
     * remote changes.
     *
     * No resolver lookup occurred. No resolver was invoked.
     *
     * @param detectorId the [ConflictDetectorId] of the detector that ran.
     */
    public data class NoConflict(
        /** The [ConflictDetectorId] of the detector that reported no conflict. */
        public val detectorId: ConflictDetectorId,
    ) : ConflictOrchestrationResult

    /**
     * A conflict was detected but [ConflictOrchestrationBindings.resolverId]
     * is `null`, meaning automatic resolution is not configured.
     *
     * The exact [SynchronizationConflict] is preserved unchanged. No resolver
     * lookup occurred. No resolver was invoked.
     *
     * @param conflict the exact [SynchronizationConflict] reported by the
     *   detector. Not mutated.
     * @param detectorId the [ConflictDetectorId] of the detector that reported
     *   the conflict.
     */
    public class ResolverNotConfigured(
        /** The exact [SynchronizationConflict] reported by the detector. */
        public val conflict: SynchronizationConflict,

        /** The [ConflictDetectorId] of the detector that reported the conflict. */
        public val detectorId: ConflictDetectorId,
    ) : ConflictOrchestrationResult {

        /**
         * Returns a safe diagnostic string including the conflict ID, conflict
         * type, and detector ID.
         *
         * Does not expose payload content from [conflict].
         */
        override fun toString(): String =
            "ConflictOrchestrationResult.ResolverNotConfigured(" +
                "conflictId=${conflict.id.value}, " +
                "conflictType=${conflict.type}, " +
                "detectorId=${detectorId.value}" +
                ")"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ResolverNotConfigured) return false
            return conflict == other.conflict && detectorId == other.detectorId
        }

        override fun hashCode(): Int {
            var result = conflict.hashCode()
            result = 31 * result + detectorId.hashCode()
            return result
        }
    }

    /**
     * A conflict was detected and a [ConflictResolverId] was configured, but
     * no matching resolver exists in the [ConflictResolverRegistry].
     *
     * The exact [SynchronizationConflict] is preserved unchanged. No resolver
     * was invoked.
     *
     * @param conflict the exact [SynchronizationConflict] reported by the
     *   detector. Not mutated.
     * @param resolverId the [ConflictResolverId] that was requested but absent.
     */
    public class ResolverNotFound(
        /** The exact [SynchronizationConflict] reported by the detector. */
        public val conflict: SynchronizationConflict,

        /** The [ConflictResolverId] that was requested but not found. */
        public val resolverId: ConflictResolverId,
    ) : ConflictOrchestrationResult {

        /**
         * Returns a safe diagnostic string including the conflict ID, conflict
         * type, and resolver ID.
         *
         * Does not expose payload content from [conflict].
         */
        override fun toString(): String =
            "ConflictOrchestrationResult.ResolverNotFound(" +
                "conflictId=${conflict.id.value}, " +
                "conflictType=${conflict.type}, " +
                "resolverId=${resolverId.value}" +
                ")"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ResolverNotFound) return false
            return conflict == other.conflict && resolverId == other.resolverId
        }

        override fun hashCode(): Int {
            var result = conflict.hashCode()
            result = 31 * result + resolverId.hashCode()
            return result
        }
    }

    /**
     * A conflict was detected, the configured resolver was found, and the
     * resolver returned a [ConflictResolutionDecision].
     *
     * The exact [SynchronizationConflict] and [ConflictResolutionDecision] are
     * preserved unchanged. The decision is not applied to storage, queues, or
     * any synchronization pipeline.
     *
     * @param conflict the exact [SynchronizationConflict] reported by the
     *   detector. Not mutated.
     * @param decision the exact [ConflictResolutionDecision] returned by the
     *   resolver. Not reinterpreted.
     * @param detectorId the [ConflictDetectorId] of the detector that reported
     *   the conflict.
     * @param resolverId the [ConflictResolverId] of the resolver that produced
     *   the decision.
     */
    public class Resolved(
        /** The exact [SynchronizationConflict] reported by the detector. */
        public val conflict: SynchronizationConflict,

        /** The exact [ConflictResolutionDecision] returned by the resolver. */
        public val decision: ConflictResolutionDecision,

        /** The [ConflictDetectorId] of the detector that reported the conflict. */
        public val detectorId: ConflictDetectorId,

        /** The [ConflictResolverId] of the resolver that produced the decision. */
        public val resolverId: ConflictResolverId,
    ) : ConflictOrchestrationResult {

        /**
         * Returns a safe diagnostic string including the conflict ID, conflict
         * type, detector ID, and resolver ID.
         *
         * Does not expose payload content from [conflict] or decision content
         * from [decision].
         */
        override fun toString(): String =
            "ConflictOrchestrationResult.Resolved(" +
                "conflictId=${conflict.id.value}, " +
                "conflictType=${conflict.type}, " +
                "detectorId=${detectorId.value}, " +
                "resolverId=${resolverId.value}" +
                ")"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Resolved) return false
            return conflict == other.conflict &&
                decision == other.decision &&
                detectorId == other.detectorId &&
                resolverId == other.resolverId
        }

        override fun hashCode(): Int {
            var result = conflict.hashCode()
            result = 31 * result + decision.hashCode()
            result = 31 * result + detectorId.hashCode()
            result = 31 * result + resolverId.hashCode()
            return result
        }
    }
}
