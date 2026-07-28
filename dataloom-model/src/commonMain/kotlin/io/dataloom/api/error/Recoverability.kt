package io.dataloom.api.error

/**
 * Canonical recoverability classification for DataLoom errors.
 */
public enum class Recoverability {
    /** Retry or another recovery action may succeed. */
    RECOVERABLE,

    /** Repeating the same operation without correction is not expected to succeed. */
    NON_RECOVERABLE,

    /** The runtime cannot safely classify recoverability yet. */
    UNKNOWN,
}
