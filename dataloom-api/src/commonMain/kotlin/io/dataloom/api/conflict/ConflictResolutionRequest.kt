package io.dataloom.api.conflict

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for conflict resolution supplied to a [ConflictResolver].
 *
 * A [ConflictResolutionRequest] carries the originating synchronization
 * request, the detected [SynchronizationConflict] to be resolved, and optional
 * contextual metadata.
 *
 * ## Construction
 *
 * Construction does not execute resolution, mutate storage, call transport
 * providers, or inspect payload content. The caller is responsible for
 * supplying all required fields.
 *
 * ## Equality
 *
 * Equality compares all properties by value.
 *
 * @param synchronizationRequest the originating [SynchronizationRequest] that
 *   triggered the resolution evaluation.
 * @param conflict the [SynchronizationConflict] to be resolved.
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty]. Must not include credentials, tokens, encryption
 *   keys, or full payload content.
 */
public data class ConflictResolutionRequest(
    /** The originating synchronization request. */
    public val synchronizationRequest: SynchronizationRequest,

    /** The synchronization conflict to be resolved. */
    public val conflict: SynchronizationConflict,

    /**
     * Optional contextual attributes for this resolution request.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied. Must not include
     * credentials, encryption keys, or full payload content.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
