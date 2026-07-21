package io.dataloom.api.error

/**
 * Technology-neutral DataLoom error categories.
 */
public enum class ErrorCategory {
    /** Failure linked to communication with a remote participant. */
    NETWORK,

    /** Failure linked to durable or transient storage operations. */
    STORAGE,

    /** Failure linked to identity verification. */
    AUTHENTICATION,

    /** Failure linked to permission or access-control decisions. */
    AUTHORIZATION,

    /** Failure linked to encoding, decoding, or model translation. */
    SERIALIZATION,

    /** Failure linked to semantic or structural validation. */
    VALIDATION,

    /** Failure linked to invalid or missing runtime configuration. */
    CONFIGURATION,

    /** Failure linked to queue orchestration responsibilities. */
    QUEUE,

    /** Failure linked to workflow scheduling responsibilities. */
    SCHEDULER,

    /** Failure linked to policy evaluation responsibilities. */
    POLICY,

    /** Failure linked to conflict detection or reconciliation responsibilities. */
    CONFLICT,

    /** Failure linked to invalid or unexpected lifecycle/state conditions. */
    STATE,

    /** Failure linked to provider integration boundaries. */
    PROVIDER,

    /** Failure linked to plugin integration boundaries. */
    PLUGIN,

    /** Failure linked to security integrity or protections. */
    SECURITY,

    /** Failure with no more specific external category available. */
    INTERNAL,
}
