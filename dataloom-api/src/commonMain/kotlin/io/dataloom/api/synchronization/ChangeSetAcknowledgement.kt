package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId

/**
 * Immutable record of a remote participant's acknowledgement of a pushed
 * [io.dataloom.api.change.ChangeSet].
 *
 * A [ChangeSetAcknowledgement] must contain at least one
 * [ChangeEventAcknowledgement]. Empty event collections are rejected at
 * construction time. Duplicate [ChangeEventId] values across [events] are
 * also rejected.
 *
 * The supplied [events] list is defensively copied so that mutating a
 * caller-supplied mutable list after construction does not affect the
 * acknowledgement. The exposed [events] collection is read-only and preserves
 * the declared order of the supplied acknowledgements.
 *
 * This acknowledgement does not need to contain every event from the
 * original change set. Completeness validation is a runtime responsibility
 * deferred to a later issue. Construction performs no queue or database
 * mutation.
 *
 * ## Equality
 *
 * Equality compares [changeSetId], [events], and [metadata] by value.
 *
 * @param changeSetId identifier of the change set being acknowledged.
 * @param events ordered, non-empty list of per-event acknowledgements. Must
 *   not contain duplicate [ChangeEventId] values.
 * @param metadata optional contextual attributes for this acknowledgement.
 *   Defaults to empty metadata.
 * @throws IllegalArgumentException when [events] is empty or contains
 *   duplicate event identifiers.
 */
public class ChangeSetAcknowledgement(
    /** Identifier of the change set being acknowledged. */
    public val changeSetId: ChangeSetId,
    events: List<ChangeEventAcknowledgement>,
    /**
     * Optional contextual attributes for this acknowledgement.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    init {
        require(events.isNotEmpty()) {
            "ChangeSetAcknowledgement must contain at least one event acknowledgement."
        }
    }

    private val _events: List<ChangeEventAcknowledgement> = events.toList()

    init {
        val eventIds: List<ChangeEventId> = _events.map { it.eventId }
        require(eventIds.size == eventIds.toSet().size) {
            "ChangeSetAcknowledgement must not contain duplicate event identifiers."
        }
    }

    /**
     * Ordered read-only list of per-event acknowledgements.
     *
     * Order is preserved as declared by the caller. The collection is
     * read-only and is a defensive copy of the supplied list.
     */
    public val events: List<ChangeEventAcknowledgement>
        get() = _events

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChangeSetAcknowledgement) return false
        return changeSetId == other.changeSetId &&
            _events == other._events &&
            metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result: Int = changeSetId.hashCode()
        result = 31 * result + _events.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String =
        "ChangeSetAcknowledgement(changeSetId=$changeSetId, eventCount=${_events.size})"
}
