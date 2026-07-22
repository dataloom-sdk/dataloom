package io.dataloom.api.queue

/**
 * Closed set of lifecycle states for a [QueueEntry].
 *
 * States describe where a queue entry is in its processing lifecycle. They do
 * not enforce transitions, schedule work, or automatically remove entries.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted.
 * Use the name of the enum constant for persistence and serialization.
 *
 * ## Lifecycle overview
 *
 * ```text
 * PENDING → LEASED → COMPLETED
 *                  → RETRY_WAITING → PENDING (future cycle)
 *                  → FAILED
 *                  → DEAD_LETTER
 *         → CANCELLED
 * ```
 *
 * Exact transition rules and terminal state retention policy are owned by the
 * DataLoom runtime and future queue implementation.
 */
public enum class QueueEntryState {

    /**
     * The entry is eligible for future acquisition when its availability
     * requirements are satisfied.
     *
     * A `PENDING` entry has no active lease and no active retry attempt.
     * It becomes eligible for acquisition when
     * [QueueEntry.availableAt] is reached.
     */
    PENDING,

    /**
     * The entry has been exclusively acquired by a consumer for processing.
     *
     * A `LEASED` entry must have a non-null [QueueEntry.lease]. The lease
     * protects the entry from concurrent acquisition by other consumers until
     * the lease expires or the entry transitions to a terminal state.
     */
    LEASED,

    /**
     * The entry is waiting until its next eligible execution instant.
     *
     * A `RETRY_WAITING` entry was previously leased and failed processing. The
     * runtime evaluated retry policy and scheduled a future re-attempt. The
     * entry must have a non-null [QueueEntry.retryAttempt] representing the
     * current attempt count.
     */
    RETRY_WAITING,

    /**
     * The requested synchronization work completed successfully.
     *
     * `COMPLETED` is a terminal state. The entry will not be acquired again
     * unless future runtime or retention policy explicitly permits reprocessing.
     */
    COMPLETED,

    /**
     * Processing failed and the entry is not currently scheduled for retry.
     *
     * `FAILED` is a terminal state. The entry may retain the final canonical
     * [QueueEntry.lastError] that caused the failure. Whether the entry is
     * ever retried again is determined by future runtime or operator policy.
     */
    FAILED,

    /**
     * The entry was cancelled before successful completion.
     *
     * `CANCELLED` is a terminal state. Cancellation of an actively leased
     * entry may fail or be deferred according to provider and runtime policy.
     */
    CANCELLED,

    /**
     * The entry was retained for inspection after exhausting or rejecting normal
     * processing.
     *
     * `DEAD_LETTER` is a terminal state. Entries in this state may retain the
     * final canonical [QueueEntry.lastError]. Dead-letter entries remain
     * available for operator inspection, replay, or deletion according to
     * application and runtime retention policy.
     */
    DEAD_LETTER,
}
