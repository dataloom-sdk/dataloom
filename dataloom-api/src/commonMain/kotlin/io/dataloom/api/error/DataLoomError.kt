package io.dataloom.api.error

/**
 * Canonical DataLoom error contract for public consumers.
 *
 * Implementations should expose sanitized diagnostics. `message` must describe
 * the failure without credentials, tokens, keys, personal data, or other
 * sensitive values.
 *
 * This contract classifies errors but does not implement retry decisions,
 * logging, provider-specific exception mapping, or platform-specific behavior.
 */
public interface DataLoomError {
    /** Stable machine-readable code for this error. */
    public val code: ErrorCode

    /** Technology-neutral category for this error. */
    public val category: ErrorCategory

    /** Canonical severity for this error. */
    public val severity: ErrorSeverity

    /** Canonical recoverability classification for this error. */
    public val recoverability: Recoverability

    /** Sanitized human-readable diagnostic summary. */
    public val message: String

    /** Optional underlying cause for diagnostics. */
    public val cause: Throwable?
}
