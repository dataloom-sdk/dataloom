package io.dataloom.api.storage

import io.dataloom.api.change.EntityReference
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for reading the local counterpart of one incoming
 * inbound [io.dataloom.api.change.ChangeEvent], for synchronization conflict
 * detection only.
 *
 * See [StorageProvider.readLocalConflictCandidate] for the narrow scope this
 * request is restricted to — it is not a general entity read.
 *
 * ## Equality
 *
 * Equality compares [request] and [entity] by value.
 *
 * @param request immutable synchronization request associated with this read.
 * @param entity the [EntityReference] of the incoming remote change to find a
 *   local counterpart for.
 */
public data class LocalConflictCandidateReadRequest(
    /** Immutable synchronization request associated with this read. */
    public val request: SynchronizationRequest,

    /** The entity to find a local conflict candidate for. */
    public val entity: EntityReference,
)
