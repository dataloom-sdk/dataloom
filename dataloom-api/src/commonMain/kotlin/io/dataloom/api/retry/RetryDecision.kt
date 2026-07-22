package io.dataloom.api.retry

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * The outcome of a [RetryPolicy] evaluation for a failed synchronization
 * operation.
 *
 * A [RetryDecision] is one of two variants:
 *
 * - [Retry] — the policy requests that the operation be retried after the
 *   specified [Retry.delay].
 * - [Stop] — the policy requests that retrying be abandoned for the given
 *   [Stop.reason].
 *
 * ## Immutability
 *
 * Both variants are immutable and provide value-based equality.
 *
 * ## Construction restrictions
 *
 * Creating either variant does not sleep, delay execution, schedule work,
 * mutate queues, execute the operation, or modify runtime state.
 *
 * ## Sensitive-data restrictions
 *
 * [metadata] in either variant must not contain credentials, authentication
 * tokens, encryption keys, personal data, or synchronization payload bytes.
 */
public sealed interface RetryDecision {

    /**
     * The [RetryPolicy] requests that the failed operation be retried after
     * [delay].
     *
     * A [delay] of zero milliseconds represents an immediate retry request.
     * The runtime coordinates the actual scheduling through
     * [io.dataloom.api.scheduling.SchedulerProvider] after receiving this
     * decision.
     *
     * ## Construction restrictions
     *
     * Creating this decision does not sleep, delay execution, schedule work,
     * mutate queues, or execute the operation.
     *
     * ## Backoff semantics
     *
     * [delay] is the canonical output of backoff evaluation. Future built-in
     * policy implementations may compute this value using immediate, fixed,
     * linear, exponential, or custom formulas. No algorithm is implemented by
     * this contract.
     *
     * @param delay the minimum requested delay before the next retry attempt.
     *   Zero milliseconds represents an immediate retry request. Required.
     * @param metadata optional contextual metadata. Defaults to
     *   [DataLoomMetadata.Empty]. Must not contain credentials, keys,
     *   payloads, or personal data.
     */
    public data class Retry(
        /**
         * Minimum requested delay before the next retry attempt.
         *
         * Zero milliseconds represents an immediate retry request. The
         * platform scheduler may defer execution further due to operating-system
         * constraints.
         */
        public val delay: SchedulingDelay,

        /**
         * Optional contextual metadata for this decision.
         *
         * Defaults to [DataLoomMetadata.Empty]. Must not contain credentials,
         * authentication tokens, encryption keys, personal data, or payload
         * bytes.
         */
        public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : RetryDecision

    /**
     * The [RetryPolicy] requests that retrying be abandoned for [reason].
     *
     * Creating this decision does not automatically fail the workflow, remove
     * queued work, or modify runtime state. The DataLoom runtime acts on this
     * decision after receiving it.
     *
     * @param reason the canonical reason why retrying was stopped. Required.
     * @param metadata optional contextual metadata. Defaults to
     *   [DataLoomMetadata.Empty]. Must not contain credentials, keys,
     *   payloads, or personal data.
     */
    public data class Stop(
        /**
         * The canonical reason why the policy decided to stop retrying.
         */
        public val reason: RetryStopReason,

        /**
         * Optional contextual metadata for this decision.
         *
         * Defaults to [DataLoomMetadata.Empty]. Must not contain credentials,
         * authentication tokens, encryption keys, personal data, or payload
         * bytes.
         */
        public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : RetryDecision
}
