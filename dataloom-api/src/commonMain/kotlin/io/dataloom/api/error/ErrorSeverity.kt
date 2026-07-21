package io.dataloom.api.error

/**
 * Canonical impact severity for DataLoom errors.
 */
public enum class ErrorSeverity {
    /** A meaningful problem occurred but processing may continue or recover. */
    WARNING,

    /** An operation failed and requires handling. */
    ERROR,

    /** Integrity, security, or runtime viability may be at risk. */
    CRITICAL,
}
