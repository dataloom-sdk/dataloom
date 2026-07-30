package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.time.DataLoomInstant

/** Canonically classified outcome returned by an operation protected by a circuit. */
public sealed interface CircuitProtectedOperationResult<out T> {
    /** Operation completed successfully. */
    public data class Success<T>(public val value: T) : CircuitProtectedOperationResult<T>

    /** Failure eligible to contribute to the selected circuit scope. */
    public data class Failure(
        public val error: DataLoomError,
    ) : CircuitProtectedOperationResult<Nothing>

    /**
     * Semantic failure that proves the protected dependency responded and must
     * therefore be recorded as circuit success rather than an availability failure.
     */
    public data class NonCircuitFailure(
        public val error: DataLoomError,
    ) : CircuitProtectedOperationResult<Nothing>
}

/** Result of one circuit-protected execution attempt. */
public sealed interface CircuitBreakerExecutionResult<out T> {
    /**
     * The operation ran exactly once.
     *
     * [recordResult] preserves the complete post-execution circuit outcome. A
     * persistence, contention, stale-probe, or clock result therefore never hides
     * the fact that [operationResult] already occurred.
     */
    public data class Executed<T>(
        public val operationResult: CircuitProtectedOperationResult<T>,
        public val recordResult: CircuitBreakerRecordResult,
    ) : CircuitBreakerExecutionResult<T>

    /** The operation did not run because the circuit denied permission. */
    public data class Rejected(
        public val reason: CircuitBreakerRejectionReason,
        public val retryAt: DataLoomInstant? = null,
    ) : CircuitBreakerExecutionResult<Nothing>

    /** Circuit-state loading failed before the operation was invoked. */
    public data class PermissionPersistenceFailure(
        public val error: DataLoomError,
    ) : CircuitBreakerExecutionResult<Nothing>

    /** Permission acquisition exhausted its atomic-update contention budget. */
    public data object PermissionContentionLimitReached : CircuitBreakerExecutionResult<Nothing>
}
