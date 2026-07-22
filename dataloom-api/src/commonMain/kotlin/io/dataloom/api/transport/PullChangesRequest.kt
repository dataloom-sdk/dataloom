package io.dataloom.api.transport

import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint

/**
 * Immutable request for pulling inbound synchronization changes through a
 * [TransportProvider].
 *
 * A [PullChangesRequest] carries the caller's [request], an optional immutable
 * snapshot of selected [entityTypes], an optional [maxEvents] batch hint, and
 * an optional prior [checkpoint]. An empty [entityTypes] set means that no
 * explicit entity-type restriction is requested. A `null` [checkpoint] means
 * no prior checkpoint is supplied.
 *
 * Construction performs no remote communication, serialization,
 * authentication, retry, or synchronization-direction enforcement.
 *
 * The transport provider treats [checkpoint]'s token as opaque unless it owns
 * the token format.
 *
 * ## Equality
 *
 * Equality compares [request], [entityTypes], [maxEvents], and [checkpoint]
 * by value.
 *
 * @param request immutable synchronization request associated with this pull.
 * @param entityTypes optional entity-type restriction. Defaults to an empty
 *   set, which means no explicit restriction.
 * @param maxEvents optional maximum number of events requested from the remote
 *   system. When supplied, the value must be greater than zero.
 * @param checkpoint optional prior synchronization checkpoint. `null` means no
 *   prior checkpoint is supplied.
 * @throws IllegalArgumentException when [maxEvents] is zero or negative.
 */
public class PullChangesRequest(
    /** Immutable synchronization request associated with this pull. */
    public val request: SynchronizationRequest,
    entityTypes: Set<EntityType> = emptySet(),
    /**
     * Optional maximum number of events requested from the remote system.
     *
     * `null` means no explicit event-count limit is requested.
     */
    public val maxEvents: Int? = null,
    /**
     * Optional prior synchronization checkpoint supplied by the caller.
     *
     * `null` means no prior checkpoint is supplied.
     */
    public val checkpoint: SynchronizationCheckpoint? = null,
) {
    init {
        require(maxEvents == null || maxEvents > 0) {
            "PullChangesRequest maxEvents must be greater than zero when supplied."
        }
    }

    private val entityTypesSnapshot: Set<EntityType> = entityTypes.toSet()

    /**
     * Immutable snapshot of requested entity-type restrictions.
     *
     * An empty set means that the pull request does not apply an explicit
     * entity-type restriction.
     */
    public val entityTypes: Set<EntityType>
        get() = entityTypesSnapshot.toSet()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is PullChangesRequest) {
            return false
        }

        return request == other.request &&
            entityTypesSnapshot == other.entityTypesSnapshot &&
            maxEvents == other.maxEvents &&
            checkpoint == other.checkpoint
    }

    override fun hashCode(): Int {
        var result: Int = request.hashCode()
        result = (31 * result) + entityTypesSnapshot.hashCode()
        result = (31 * result) + (maxEvents ?: 0)
        result = (31 * result) + (checkpoint?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "PullChangesRequest(" +
            "request=$request, " +
            "entityTypes=$entityTypesSnapshot, " +
            "maxEvents=$maxEvents, " +
            "checkpoint=$checkpoint" +
            ")"
    }
}
