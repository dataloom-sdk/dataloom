package io.dataloom.api.queue

/**
 * Closed set of failure disposition choices for a [QueueEntry] that cannot be
 * retried or recovered normally.
 *
 * The DataLoom runtime or host policy supplies the disposition. Neither the
 * [io.dataloom.api.queue.QueueProvider] nor the queue models select the
 * disposition automatically.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted.
 * Use the name of the enum constant for persistence and serialization.
 */
public enum class QueueFailureDisposition {

    /**
     * The entry processing failed permanently.
     *
     * The entry transitions to [QueueEntryState.FAILED]. It will not be
     * retried unless future operator or runtime policy explicitly requeues it.
     */
    FAILED,

    /**
     * The entry is retained in the dead-letter position for operator
     * inspection, replay, or deletion.
     *
     * The entry transitions to [QueueEntryState.DEAD_LETTER]. Dead-letter
     * entries remain available for inspection according to application and
     * runtime retention policy.
     */
    DEAD_LETTER,
}
