package io.dataloom.api.provider

/**
 * Closed set of provider lifecycle labels.
 *
 * This type documents lifecycle states only and does not enforce transitions.
 * Enum ordinals are not a compatibility contract and must not be persisted.
 */
public enum class ProviderLifecycleState {
    /** The provider exists but initialization has not started. */
    CREATED,

    /** Provider initialization is in progress. */
    INITIALIZING,

    /** The provider is available for its declared operations. */
    READY,

    /** The provider is partially usable with reduced capability or reliability. */
    DEGRADED,

    /** The provider cannot currently perform its responsibilities. */
    FAILED,

    /** Provider shutdown or resource release is in progress. */
    CLOSING,

    /** The provider completed shutdown and must not accept new operations. */
    CLOSED,
}
