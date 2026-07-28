package io.dataloom.api.queue

/**
 * Stable reason for making acquired queue work available again without
 * consuming a retry attempt.
 *
 * A deferral is an execution constraint outcome, not a failed synchronization
 * attempt. Enum ordinals are not a compatibility contract and must not be
 * persisted. Use the named constant when encoding diagnostics or durable
 * records.
 */
public enum class QueueDeferralReason {

    /**
     * The synchronization's declared connectivity requirement was not
     * satisfied before pipeline execution.
     */
    CONNECTIVITY_REQUIREMENT_NOT_MET,
}
