package io.dataloom.api.storage

import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for reading outbound synchronization changes from
 * application-controlled storage through a [StorageProvider].
 *
 * An [OutboundChangeReadRequest] carries the originating [request], an optional
 * immutable snapshot of selected [entityTypes], and an optional [maxEvents]
 * batch hint. An empty [entityTypes] set means that no explicit entity-type
 * restriction is requested.
 *
 * Construction performs no storage access, serialization, or
 * synchronization-direction enforcement. Runtime direction validation is
 * deferred to the caller and provider implementation.
 *
 * The provider may use the synchronization mode, workflow, session, and
 * execution context contained in [request].
 *
 * ## Defensive copy
 *
 * The supplied [entityTypes] set is defensively copied at construction time.
 * Mutating a source set after construction does not affect this request.
 *
 * ## Equality
 *
 * Equality compares [request], [entityTypes], and [maxEvents] by value.
 *
 * @param request immutable synchronization request associated with this read.
 * @param entityTypes optional entity-type restriction. Defaults to an empty
 *   set, which means no explicit restriction.
 * @param maxEvents optional maximum number of events requested from storage.
 *   When supplied, the value must be greater than zero. `null` means no
 *   explicit event-count limit is requested.
 * @throws IllegalArgumentException when [maxEvents] is zero or negative.
 */
public class OutboundChangeReadRequest(
    /** Immutable synchronization request associated with this outbound read. */
    public val request: SynchronizationRequest,
    entityTypes: Set<EntityType> = emptySet(),
    /**
     * Optional maximum number of events requested from storage.
     *
     * `null` means no explicit event-count limit is requested.
     */
    public val maxEvents: Int? = null,
) {
    init {
        require(maxEvents == null || maxEvents > 0) {
            "OutboundChangeReadRequest maxEvents must be greater than zero when supplied."
        }
    }

    private val entityTypesSnapshot: Set<EntityType> = entityTypes.toSet()

    /**
     * Immutable snapshot of requested entity-type restrictions.
     *
     * An empty set means that the read request does not apply an explicit
     * entity-type restriction.
     */
    public val entityTypes: Set<EntityType>
        get() = entityTypesSnapshot.toSet()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboundChangeReadRequest) return false
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

    override fun toString(): String =
        "OutboundChangeReadRequest(" +
            "request=$request, " +
            "entityTypes=$entityTypesSnapshot, " +
            "maxEvents=$maxEvents" +
            ")"
}
