package io.dataloom.api.change

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeSetId

/**
 * Immutable ordered collection of [ChangeEvent] instances representing a
 * logical unit of synchronization work.
 *
 * A [ChangeSet] must contain at least one event. Empty event collections are
 * rejected at construction time.
 *
 * The supplied event list is defensively copied so that mutating a caller-
 * supplied mutable list after construction does not affect the change set. The
 * exposed [events] collection is read-only and preserves the declared order of
 * the supplied events.
 *
 * Construction performs no runtime action. The caller is responsible for
 * supplying a meaningful [ChangeSetId].
 *
 * This contract does not implement splitting, merging, retry, conflict
 * detection, conflict resolution, queueing, or synchronization execution.
 *
 * ## Equality
 *
 * Equality compares ID, events, and metadata by value. Two [ChangeSet]
 * instances with identical property values are considered equal.
 *
 * @param id unique identifier for this change set. Ownership: change-set
 *   producer.
 * @param events ordered list of change events. Must contain at least one
 *   event.
 * @param metadata optional contextual attributes for this change set.
 *   Defaults to empty metadata.
 * @throws IllegalArgumentException when [events] is empty.
 */
public class ChangeSet(
    /** Unique identifier for this change set. */
    public val id: ChangeSetId,
    events: List<ChangeEvent>,
    /**
     * Optional contextual attributes for this change set.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    init {
        require(events.isNotEmpty()) { "ChangeSet must contain at least one event." }
    }

    private val _events: List<ChangeEvent> = events.toList()

    /**
     * Ordered read-only list of change events in this change set.
     *
     * Order is preserved as declared by the caller. The collection is
     * read-only and is a defensive copy of the supplied list.
     */
    public val events: List<ChangeEvent>
        get() = _events

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChangeSet) return false
        return id == other.id && _events == other._events && metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result: Int = id.hashCode()
        result = 31 * result + _events.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    /**
     * Returns a diagnostic representation that includes the change-set ID and
     * event count.
     */
    override fun toString(): String = "ChangeSet(id=$id, eventCount=${_events.size})"
}
