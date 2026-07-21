package io.dataloom.api.model

/**
 * Canonical change operation intent.
 */
public enum class ChangeOperation {
    /** Represents creation of a new entity or record. */
    CREATE,

    /** Represents modification of an existing entity or record. */
    UPDATE,

    /** Represents deletion of an existing entity or record. */
    DELETE,

    /** Represents consolidation of multiple sources into a unified state. */
    MERGE,

    /** Represents restoration of a previously removed or superseded state. */
    RESTORE,
}
