package io.dataloom.api.retry

/**
 * Canonical reason why a [RetryPolicy] or the DataLoom runtime stopped retrying
 * an operation.
 *
 * Do not rely on enum ordinals for serialization or persistence. Use stable
 * variant names. Coroutine cancellation must propagate and must not be
 * converted into a [RetryStopReason].
 */
public enum class RetryStopReason {

    /**
     * The canonical error indicates that repeating the same operation without
     * correction is not expected to succeed.
     *
     * Runtime retry paths enforce this outcome before invoking a configured
     * custom policy.
     */
    NON_RECOVERABLE,

    /** The configured retry-attempt budget has been exhausted. */
    ATTEMPT_LIMIT_REACHED,

    /** The proposed next retry would exceed the configured elapsed-time window. */
    ELAPSED_TIME_LIMIT_REACHED,

    /** The proposed next retry would exceed the cumulative-delay budget. */
    CUMULATIVE_DELAY_LIMIT_REACHED,

    /** Durable wall-clock evidence moved backwards and retry stopped fail-closed. */
    CLOCK_REGRESSION_DETECTED,

    /**
     * Retry was rejected by policy or by a fail-closed runtime protection, such
     * as unknown recoverability or a protected failure category.
     */
    POLICY_REJECTED,

    /** The policy does not support the supplied logical operation. */
    UNSUPPORTED_OPERATION,
}
