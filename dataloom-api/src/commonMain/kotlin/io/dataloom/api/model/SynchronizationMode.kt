package io.dataloom.api.model

/**
 * Canonical synchronization mode.
 */
public enum class SynchronizationMode {
    /** Processes the complete selected synchronization scope. */
    FULL,

    /** Processes changes identified since an accepted baseline. */
    DELTA,
}
