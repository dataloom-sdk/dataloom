package io.dataloom.api.change

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.DataLoomPayload

/**
 * Immutable record of a single synchronization change intent for a domain entity.
 *
 * A [ChangeEvent] carries the identity of the change, the entity being changed,
 * the operation intent, an optional payload, and optional metadata. It does not
 * execute synchronization, persist data, transmit the payload, or generate any
 * identifiers or timestamps.
 *
 * Construction performs no runtime action. The caller is responsible for
 * supplying a meaningful [ChangeEventId].
 *
 * ## Payload optionality
 *
 * Payload may be absent for operations such as [ChangeOperation.DELETE] where
 * no content body is required. Payload presence rules for individual operations
 * are not enforced by this contract.
 *
 * ## Equality
 *
 * Equality compares all properties by value. Two [ChangeEvent] instances with
 * identical property values are considered equal.
 *
 * @param id unique identifier for this change event. Ownership: change producer.
 * @param entity reference to the domain entity affected by this change.
 * @param operation the semantic intent of this change.
 * @param payload optional opaque payload carrying content for this change.
 *   May be `null` when no payload body is applicable.
 * @param metadata optional contextual attributes for this event. Defaults to
 *   empty metadata.
 */
public data class ChangeEvent(
    /** Unique identifier for this change event. */
    public val id: ChangeEventId,
    /** Reference to the domain entity affected by this change. */
    public val entity: EntityReference,
    /** Semantic intent of this change operation. */
    public val operation: ChangeOperation,
    /**
     * Optional opaque payload carrying content for this change.
     *
     * `null` when no payload body is applicable for the operation.
     */
    public val payload: DataLoomPayload? = null,
    /**
     * Optional contextual attributes for this event.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
