package io.dataloom.api.synchronization

/**
 * Canonical reason why a synchronization workflow execution was skipped.
 *
 * A [SynchronizationSkipReason] is attached to a
 * [SynchronizationResult.Skipped] result to explain why the runtime did not
 * proceed with execution.
 *
 * Do not rely on enum ordinals for persistence or comparison.
 */
public enum class SynchronizationSkipReason {

    /**
     * No eligible synchronization changes were available.
     *
     * The runtime determined that there was no work to perform at the time of
     * execution.
     */
    NO_CHANGES,

    /**
     * Required execution constraints were not met.
     *
     * The runtime determined that one or more preconditions necessary for
     * execution were not satisfied (for example connectivity requirements or
     * schedule window constraints).
     */
    CONSTRAINTS_NOT_SATISFIED,

    /**
     * Runtime or application policy prevented execution.
     *
     * A configured policy or rule rejected the execution request before any
     * provider operations were performed.
     */
    POLICY_REJECTED,

    /**
     * Equivalent work was already active or accepted.
     *
     * The runtime determined that an equivalent synchronization request was
     * already in progress or queued, preventing duplicate execution.
     *
     * Deduplication logic is implemented by the future runtime engine and is
     * not enforced by this type.
     */
    DUPLICATE_REQUEST,
}
