package io.dataloom.api.change

import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.payload.EntityVersion

/**
 * Immutable reference to a domain entity identified by type, ID, and an
 * optional version.
 *
 * An [EntityReference] carries only identifying information. It does not load,
 * validate, or persist the referenced entity. It does not generate identifiers
 * or versions automatically.
 *
 * The host application is responsible for supplying meaningful entity type
 * strings, entity identifiers, and version labels.
 *
 * @param type the type label for the domain entity. Ownership: host
 *   application or domain model.
 * @param id the unique identifier for the domain entity. Ownership: host
 *   application or domain model.
 * @param version the optional version of the entity at the time of reference.
 *   Ownership: host application or remote system. May be `null` when no
 *   version context is available.
 */
public data class EntityReference(
    /** Type label for the referenced domain entity. */
    public val type: EntityType,
    /** Unique identifier for the referenced domain entity. */
    public val id: EntityId,
    /**
     * Optional version of the entity at the time of reference.
     *
     * `null` indicates that no version context is available or applicable.
     */
    public val version: EntityVersion? = null,
)
