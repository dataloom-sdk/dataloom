package io.dataloom.api.provider

/**
 * Closed set of platform-independent provider categories.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted.
 */
public enum class ProviderType {
    /** Provider category for durable data persistence and retrieval concerns. */
    STORAGE,

    /** Provider category for network or message transport concerns. */
    TRANSPORT,

    /** Provider category for scheduling and execution triggering concerns. */
    SCHEDULER,

    /** Provider category for connectivity state evaluation concerns. */
    CONNECTIVITY,

    /** Provider category for authentication and credential exchange concerns. */
    AUTHENTICATION,

    /** Provider category for serialization and deserialization concerns. */
    SERIALIZATION,

    /** Provider category for cryptographic protection concerns. */
    ENCRYPTION,

    /** Provider category for compression and decompression concerns. */
    COMPRESSION,

    /** Provider category for logging and diagnostic emission concerns. */
    LOGGING,

    /** Provider category for monitoring, metrics, and health reporting concerns. */
    MONITORING,

    /**
     * Infrastructure provider responsible for durable DataLoom queue records,
     * leases, recovery, and queue-state persistence.
     *
     * This is a pre-release public API addition introduced in DL-015.
     */
    QUEUE,
}
