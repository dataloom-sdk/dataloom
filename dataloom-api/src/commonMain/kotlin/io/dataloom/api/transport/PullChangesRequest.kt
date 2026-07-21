package io.dataloom.api.transport

import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for pulling inbound synchronization changes through a
 * [TransportProvider].
 *
 * A [PullChangesRequest] carries the caller's [request], an optional immutable
 * snapshot of selected [entityTypes], and an optional [maxEvents] batch hint.
 * An empty [entityTypes] set means that no explicit entity-type restriction is
 * requested.
 *
 * Construction performs no remote communication, serialization,
 * authentication, retry, or synchronization-direction enforcement.
 *
 * ## Equality
 *
 * Equality compares [request], [entityTypes], and [maxEvents] by value.
 *
 * @param request immutable synchronization request associated with this pull.
 * @param entityTypes optional entity-type restriction. Defaults to an empty
 *   set, which means no explicit restriction.
 * @param maxEvents optional maximum number of events requested from the remote
 *   system. When supplied, the value must be greater than zero.
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
            maxEvents == other.maxEvents
    }

    override fun hashCode(): Int {
        var result: Int = request.hashCode()
        result = (31 * result) + entityTypesSnapshot.hashCode()
        result = (31 * result) + (maxEvents ?: 0)
        return result
    }

    override fun toString(): String {
        return "PullChangesRequest(" +
            "request=$request, " +
            "entityTypes=$entityTypesSnapshot, " +
            "maxEvents=$maxEvents" +
            ")"
    }
}
