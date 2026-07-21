package io.dataloom.api.model

/**
 * Canonical synchronization direction.
 */
public enum class SynchronizationDirection {
    /** Local changes are sent to a remote participant. */
    PUSH,

    /** Remote changes are received locally. */
    PULL,

    /** Both directions may participate in one logical synchronization process. */
    BIDIRECTIONAL,
}
