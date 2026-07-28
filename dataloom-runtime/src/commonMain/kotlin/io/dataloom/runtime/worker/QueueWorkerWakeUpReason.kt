package io.dataloom.runtime.worker

/**
 * Reason that the [QueueWorkerCoordinator] determined another worker wake-up
 * is required after one bounded processing cycle.
 *
 * ## Variants
 *
 * - [ACQUISITION_LIMIT_REACHED] — the bounded acquisition returned the maximum
 *   requested number of entries. Another bounded processing cycle may process
 *   more immediately available work.
 * - [RESCHEDULED_ENTRY_AVAILABLE] — one or more entries were successfully
 *   persisted into a future rescheduled state. The earliest availability time
 *   is known from the processing result.
 * - [DEFERRED_ENTRY_AVAILABLE] — one or more entries were successfully
 *   deferred without consuming retry history.
 * - [RETRY_AND_DEFERRAL_AVAILABLE] — both retry and deferral availability
 *   evidence exists; the earlier instant is selected.
 * - [BOTH] — the acquisition limit and at least one future-availability
 *   condition exist simultaneously.
 *
 * ## Ordinal contract
 *
 * Enum ordinals are not a compatibility contract and must not be persisted or
 * used in comparisons. Use the named constants directly.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library only. Safe for use in Kotlin Multiplatform
 * common code.
 */
public enum class QueueWorkerWakeUpReason {

    /**
     * The bounded acquisition returned the maximum requested number of entries.
     *
     * Another bounded processing cycle may be useful because more immediately
     * available work may remain in the queue.
     */
    ACQUISITION_LIMIT_REACHED,

    /**
     * One or more queue entries were successfully persisted into a future
     * rescheduled state.
     *
     * The earliest availability time is known from the processing result and
     * is used to calculate the scheduling delay.
     */
    RESCHEDULED_ENTRY_AVAILABLE,

    /**
     * One or more entries were deferred without consuming retry history.
     */
    DEFERRED_ENTRY_AVAILABLE,

    /**
     * Both retry-rescheduled and non-retry deferred entries were persisted.
     *
     * The earlier availability instant is used for scheduling.
     */
    RETRY_AND_DEFERRAL_AVAILABLE,

    /**
     * Both [ACQUISITION_LIMIT_REACHED] and at least one retry or deferral
     * availability condition exist simultaneously.
     *
     * A single schedule operation covers both; the earlier of the two
     * candidate delays is selected.
     */
    BOTH,
}
