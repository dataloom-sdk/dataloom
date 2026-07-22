package io.dataloom.api.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError

/**
 * Sealed decision returned by a [ConflictResolver] after evaluating a
 * [ConflictResolutionRequest].
 *
 * Each variant represents a distinct resolution strategy that the
 * synchronization runtime will apply when conflict orchestration is
 * implemented. Creating a decision does not mutate storage, transport, queues,
 * or workflow state. Decisions do not automatically retry.
 *
 * ## Variants
 *
 * - [UseLocal] — use the conflict's local change as the resolution candidate.
 * - [UseRemote] — use the conflict's remote change as the resolution candidate.
 * - [Merge] — the application resolver supplies a resolved [ChangeEvent].
 * - [Defer] — the conflict remains unresolved for future handling.
 * - [Fail] — the conflict cannot be resolved under the current policy.
 *
 * ## Metadata
 *
 * All variants carry optional [DataLoomMetadata]. Metadata defaults to
 * [DataLoomMetadata.Empty] and must not contain credentials, encryption keys,
 * full payloads, or personal data.
 *
 * ## Application ownership
 *
 * DataLoom coordinates conflict resolution, while the host application owns
 * domain-specific conflict rules and merge behavior.
 */
public sealed interface ConflictResolutionDecision {

    /**
     * Resolution decision indicating that the runtime should use the conflict's
     * local change as the resolution candidate.
     *
     * Creating this decision does not apply the local change, mutate storage,
     * or trigger transport operations.
     *
     * @param metadata optional contextual attributes. Defaults to
     *   [DataLoomMetadata.Empty]. Must not include credentials, keys, or
     *   payload content.
     */
    public data class UseLocal(
        /**
         * Optional contextual attributes for this decision.
         *
         * Defaults to [DataLoomMetadata.Empty] when not supplied.
         */
        public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : ConflictResolutionDecision

    /**
     * Resolution decision indicating that the runtime should use the conflict's
     * remote change as the resolution candidate.
     *
     * Creating this decision does not apply the remote change, mutate storage,
     * or trigger transport operations.
     *
     * @param metadata optional contextual attributes. Defaults to
     *   [DataLoomMetadata.Empty]. Must not include credentials, keys, or
     *   payload content.
     */
    public data class UseRemote(
        /**
         * Optional contextual attributes for this decision.
         *
         * Defaults to [DataLoomMetadata.Empty] when not supplied.
         */
        public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : ConflictResolutionDecision

    /**
     * Resolution decision where the application resolver supplies a resolved
     * [ChangeEvent] representing the merged outcome.
     *
     * The [resolvedChange] must reference the same entity type and ID as the
     * original conflict (represented by [expectedEntity]). A different entity
     * version may be supplied. DataLoom does not inspect or generate the merged
     * payload; merge semantics are owned by the application resolver.
     *
     * Creating this decision does not apply the merged change, persist data,
     * or trigger transport operations.
     *
     * @param expectedEntity the [EntityReference] identifying the entity
     *   involved in the original conflict. The [resolvedChange] must reference
     *   the same entity type and ID.
     * @param resolvedChange the application-supplied [ChangeEvent] representing
     *   the merged resolution. Must reference the same entity type and ID as
     *   [expectedEntity].
     * @param metadata optional contextual attributes. Defaults to
     *   [DataLoomMetadata.Empty]. Must not include credentials, keys, or
     *   payload content.
     * @throws IllegalArgumentException when [resolvedChange] references a
     *   different entity type or ID than [expectedEntity].
     */
    public data class Merge(
        /**
         * The expected entity reference from the original conflict.
         *
         * Used to validate that [resolvedChange] references the correct entity.
         */
        public val expectedEntity: EntityReference,

        /**
         * The application-supplied resolved [ChangeEvent].
         *
         * Must reference the same entity type and ID as [expectedEntity].
         * A different entity version is permitted.
         */
        public val resolvedChange: ChangeEvent,

        /**
         * Optional contextual attributes for this decision.
         *
         * Defaults to [DataLoomMetadata.Empty] when not supplied.
         */
        public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : ConflictResolutionDecision {
        init {
            require(
                resolvedChange.entity.type == expectedEntity.type &&
                    resolvedChange.entity.id == expectedEntity.id,
            ) {
                "ConflictResolutionDecision.Merge: resolvedChange must reference the same entity " +
                    "type and ID as the original conflict (expectedEntity)."
            }
        }
    }

    /**
     * Resolution decision indicating that the conflict remains unresolved and
     * should be handled by future application or runtime logic.
     *
     * A deferred conflict is not automatically a retry decision. Creating this
     * decision does not enqueue or persist the conflict.
     *
     * @param metadata optional contextual attributes. Defaults to
     *   [DataLoomMetadata.Empty]. Must not include credentials, keys, or
     *   payload content.
     */
    public data class Defer(
        /**
         * Optional contextual attributes for this decision.
         *
         * Defaults to [DataLoomMetadata.Empty] when not supplied.
         */
        public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : ConflictResolutionDecision

    /**
     * Resolution decision indicating that the conflict cannot be resolved under
     * the current policy.
     *
     * The canonical [error] is required and must describe the failure without
     * revealing credentials, keys, or sensitive payload content.
     *
     * A failed resolution is not automatically retryable. Creating this
     * decision does not automatically fail the workflow or trigger retry
     * evaluation.
     *
     * @param error the canonical [DataLoomError] describing why resolution
     *   failed.
     * @param metadata optional contextual attributes. Defaults to
     *   [DataLoomMetadata.Empty]. Must not include credentials, keys, or
     *   payload content.
     */
    public data class Fail(
        /** The canonical error describing the resolution failure. */
        public val error: DataLoomError,

        /**
         * Optional contextual attributes for this decision.
         *
         * Defaults to [DataLoomMetadata.Empty] when not supplied.
         */
        public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : ConflictResolutionDecision
}
