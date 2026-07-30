package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.time.DataLoomInstant

/** Canonically classified outcome returned by an operation protected by a circuit. */
public sealed interface CircuitProtectedOperationResult<out T> {
    public data class Success<T>(public val value: T) : CircuitProtectedOperationResult<T>

    /** Failure eligible to contribute to the selected circuit scope. */
    public data class Failure(
        public val error: DataLoomError,
    ) : CircuitProtectedOperationResult<Nothing>
}

/** Result of one circuit-protected execution attempt. */
public sealed interface CircuitBreakerExecutionResult<out T> {
    /** The operation ran once and its classified outcome was recorded or required no mutation. */
    public data class Executed<T>(
        public val operationResult: CircuitProtectedOperationResult<T>,
    ) : CircuitBreakerExecutionResult<T>

    /** The operation did not run because the circuit denied permission. */
    public data class Rejected(
        public val reason: CircuitBreakerRejectionReason,
        public val retryAt: DataLoomInstant? = null,
    ) : CircuitBreakerExecutionResult<Nothing>

    /** Circuit state persistence failed before or after execution. */
    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : CircuitBreakerExecutionResult<Nothing>

    /** Atomic state updates exceeded their configured contention budget. */
    public data object ContentionLimitReached : CircuitBreakerExecutionResult<Nothing>

    /** The controlled probe completed after its persisted generation became stale. */
    public data object StaleProbe : CircuitBreakerExecutionResult<Nothing>

    /** The observed clock moved behind persisted circuit evidence. */
    public data class ClockRegression(
        public val observedAt: DataLoomInstant,
        public val persistedAt: DataLoomInstant,
    ) : CircuitBreakerExecutionResult<Nothing>
}
