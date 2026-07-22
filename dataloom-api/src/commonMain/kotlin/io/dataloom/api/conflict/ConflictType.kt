package io.dataloom.api.conflict

/**
 * Canonical classification of a detected synchronization conflict.
 *
 * Each value represents a distinct kind of conflict that DataLoom or the host
 * application may encounter during synchronization. DataLoom does not
 * automatically classify conflicts; classification is performed by a
 * [ConflictDetector] supplied by the host application or integration.
 *
 * Ordinal values must not be used for persistence or serialization. Compare
 * conflict types by name or by direct reference.
 *
 * This type is closed. Do not add new values without an approved issue.
 */
public enum class ConflictType {

    /**
     * Local and remote participants independently changed the same entity.
     *
     * Both the local and remote participants submitted a change to the same
     * entity without awareness of each other's modification.
     */
    CONCURRENT_CHANGE,

    /**
     * The supplied entity versions do not satisfy the application's expected
     * version relationship.
     *
     * The application defines what constitutes a valid version relationship.
     * DataLoom does not interpret or order version values.
     */
    VERSION_MISMATCH,

    /**
     * A local update conflicts with a remote deletion.
     *
     * The local participant updated an entity that the remote participant has
     * concurrently deleted.
     */
    UPDATE_DELETE,

    /**
     * A local deletion conflicts with a remote update.
     *
     * The local participant deleted an entity that the remote participant has
     * concurrently updated.
     */
    DELETE_UPDATE,

    /**
     * A create operation conflicts with an existing remote or local entity.
     *
     * A create change targets an entity that already exists in the remote or
     * local store.
     */
    CREATE_COLLISION,

    /**
     * The application or provider identified a domain-specific conflict that
     * does not map cleanly to another canonical type.
     *
     * Use this value when the conflict follows application-defined rules that
     * have no canonical representation in this enum.
     */
    CUSTOM,
}
