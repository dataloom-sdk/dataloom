package io.dataloom.runtime.retry

/**
 * Standard jitter applied after [RetryBackoffStrategy] calculates its bounded
 * base delay.
 *
 * Jitter never increases the base delay, so the configured fixed, linear, or
 * exponential maximum remains authoritative. A jitter mode consumes a sample
 * only after central failure protection and attempt-budget checks pass.
 */
public sealed interface RetryJitterStrategy {

    /** Preserves the exact deterministic base delay and consumes no sample. */
    public data object None : RetryJitterStrategy

    /**
     * Selects a bounded delay in the inclusive range `0..baseDelay`.
     *
     * This is the standard full-jitter shape. A zero base delay remains zero and
     * does not call the configured [RetryRandomSource].
     */
    public data object Full : RetryJitterStrategy

    /**
     * Preserves at least half of the base delay and jitters the remaining half.
     *
     * For integer milliseconds the inclusive output range is
     * `ceil(baseDelay / 2)..baseDelay`. A base delay of zero remains zero.
     */
    public data object Equal : RetryJitterStrategy
}
