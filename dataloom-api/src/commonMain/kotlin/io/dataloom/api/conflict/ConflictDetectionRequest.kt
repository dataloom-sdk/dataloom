package io.dataloom.api.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for conflict detection supplied to a [ConflictDetector].
 *
 * A [ConflictDetectionRequest] carries the originating synchronization request,
 * the local and remote change events to be evaluated, and optional contextual
 * metadata.
 *
 * ## Entity consistency
 *
 * The [localChange] and [remoteChange] must both reference the same entity type
 * and entity ID. Construction rejects changes that reference different entities.
 *
 * ## Construction
 *
 * Construction does not perform detection, access storage, call transport
 * providers, or inspect opaque payload content. The caller is responsible for
 * supplying all required fields.
 *
 * ## Equality
 *
 * Equality compares all properties by value.
 *
 * @param synchronizationRequest the originating [SynchronizationRequest] that
 *   triggered the detection evaluation.
 * @param localChange the [ChangeEvent] representing the local participant's
 *   change. Must reference the same entity type and ID as [remoteChange].
 * @param remoteChange the [ChangeEvent] representing the remote participant's
 *   change. Must reference the same entity type and ID as [localChange].
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty]. Must not include credentials, tokens, encryption
 *   keys, or full payload content.
 * @throws IllegalArgumentException when [localChange] and [remoteChange]
 *   reference different entity types or IDs.
 */
public data class ConflictDetectionRequest(
    /** The originating synchronization request. */
    public val synchronizationRequest: SynchronizationRequest,

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
     * Optional contextual attributes for this detection request.
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
            "ConflictDetectionRequest: localChange and remoteChange must reference the same entity " +
                "type and ID."
        }
    }
}
