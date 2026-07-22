package io.dataloom.api.retry

/**
 * Canonical reason why a [RetryPolicy] decided to stop retrying an operation.
 *
 * [RetryStopReason] is a closed type. Every variant must be explicitly handled
 * by consumers.
 *
 * ## Ordinal stability
 *
 * Do not rely on enum ordinals for serialization or persistence. Ordinals may
 * change when variants are added or reordered. Use the variant names for any
 * durable representation.
 *
 * ## Coroutine cancellation
 *
 * Coroutine cancellation must not be converted into a [RetryStopReason].
 * `CancellationException` must propagate normally outside the retry policy.
 */
public enum class RetryStopReason {

    /**
     * The canonical error indicates that repeating the same operation without
     * correction is not expected to succeed.
     *
     * This corresponds to
     * [io.dataloom.api.error.Recoverability.NON_RECOVERABLE] on the
     * [io.dataloom.api.error.DataLoomError] supplied in the evaluation
     * request. The normal policy decision for a non-recoverable error is to
     * return [RetryDecision.Stop] with this reason.
     *
     * A future runtime may reject a policy decision that attempts to retry a
     * non-recoverable error. Enforcement is deferred.
     */
    NON_RECOVERABLE,

    /**
     * The configured retry-attempt budget has been exhausted.
     *
     * The policy determined that the number of attempts already made equals or
     * exceeds the configured limit. The runtime enforces attempt limits;
     * [RetryStopReason.ATTEMPT_LIMIT_REACHED] communicates the reason back to
     * the caller.
     */
    ATTEMPT_LIMIT_REACHED,

    /**
     * The policy decided that the operation should not be retried.
     *
     * This is a general-purpose stop reason for any application-defined or
     * policy-specific rejection that does not fall into another category.
     * The policy may document more specific semantics through metadata on
     * [RetryDecision.Stop].
     */
    POLICY_REJECTED,

    /**
     * The policy does not support retry evaluation for the supplied logical
     * operation.
     *
     * This reason is returned when the [RetryEvaluationRequest.operation]
     * value is not recognised by this policy implementation. The calling
     * runtime should handle the unknown operation appropriately.
     */
    UNSUPPORTED_OPERATION,
}
