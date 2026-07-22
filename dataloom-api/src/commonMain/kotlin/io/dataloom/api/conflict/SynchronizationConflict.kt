package io.dataloom.api.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ConflictId

/**
 * Immutable canonical model representing a detected synchronization conflict.
 *
 * A [SynchronizationConflict] carries the conflict identifier, classification,
 * canonical entity reference, the conflicting local and remote changes, and
 * optional contextual metadata.
 *
 * ## Entity consistency
 *
 * The [localChange] and [remoteChange] must both reference the same entity type
 * and entity ID. The [entity] reference must match the entity type and ID
 * referenced by both changes. Entity versions may differ between [localChange],
 * [remoteChange], and [entity].
 *
 * ## Construction
 *
 * Construction validates entity consistency and rejects changes that reference
 * different entities. Construction does not:
 * - Inspect or compare payload content.
 * - Interpret or order entity version values.
 * - Resolve the conflict.
 * - Persist or transmit any data.
 * - Generate the conflict identifier.
 *
 * ## Equality
 *
 * Equality compares all properties by value. Two instances with identical
 * property values are considered equal.
 *
 * @param id unique identifier for this conflict. Ownership: conflict producer.
 *   Must be supplied by the caller; DataLoom does not generate this value.
 * @param type canonical [ConflictType] classification for this conflict.
 * @param entity canonical [EntityReference] identifying the logical entity
 *   involved in this conflict. Must match the entity type and ID referenced by
 *   both [localChange] and [remoteChange].
 * @param localChange the [ChangeEvent] representing the local participant's
 *   change. Must reference the same entity type and ID as [remoteChange].
 * @param remoteChange the [ChangeEvent] representing the remote participant's
 *   change. Must reference the same entity type and ID as [localChange].
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty]. Must not include credentials, tokens, encryption
 *   keys, or full payload content.
 * @throws IllegalArgumentException when [localChange] and [remoteChange]
 *   reference different entity types or IDs, or when [entity] does not match
 *   the entity type and ID referenced by the changes.
 */
public data class SynchronizationConflict(
    /** Unique identifier for this conflict. Ownership: conflict producer. */
    public val id: ConflictId,

    /** Canonical classification of the conflict. */
    public val type: ConflictType,

    /**
     * Canonical entity reference identifying the logical entity involved in
     * this conflict.
     *
     * Must match the entity type and ID referenced by both [localChange] and
     * [remoteChange]. Entity version may differ from those in the change events.
     */
    public val entity: EntityReference,

    /**
     * The local participant's [ChangeEvent].
     *
     * Must reference the same entity type and ID as [remoteChange].
     */
    public val localChange: ChangeEvent,

    /**
     * The remote participant's [ChangeEvent].
     *
     * Must reference the same entity type and ID as [localChange].
     */
    public val remoteChange: ChangeEvent,

    /**
     * Optional contextual attributes for this conflict.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied. Must not include
     * credentials, encryption keys, or full payload content.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    init {
        require(
            localChange.entity.type == remoteChange.entity.type &&
                localChange.entity.id == remoteChange.entity.id,
        ) {
            "SynchronizationConflict: localChange and remoteChange must reference the same entity " +
                "type and ID."
        }
        require(
            entity.type == localChange.entity.type &&
                entity.id == localChange.entity.id,
        ) {
            "SynchronizationConflict: entity must match the entity type and ID referenced by both " +
                "localChange and remoteChange."
        }
    }
}
