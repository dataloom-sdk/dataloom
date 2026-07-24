package io.dataloom.runtime.queue

/**
 * Immutable processing-cycle counters produced by one
 * [DurableQueueExecutionProcessor.process] call.
 *
 * Counts reflect the truthful state of the processing cycle at the time the
 * result is returned, including partial progress when a provider transition
 * failure stops early.
 *
 * ## Counter semantics
 *
 * - [acquired] — number of entries returned by the provider acquisition.
 * - [executed] — number of entries for which the handler was invoked.
 * - [completed] — number of entries that were successfully transitioned to
 *   [io.dataloom.api.queue.QueueEntryState.COMPLETED].
 * - [rescheduled] — number of entries that were successfully transitioned to
 *   [io.dataloom.api.queue.QueueEntryState.RETRY_WAITING].
 * - [failed] — number of entries that were successfully transitioned to
 *   [io.dataloom.api.queue.QueueEntryState.FAILED] or
 *   [io.dataloom.api.queue.QueueEntryState.DEAD_LETTER].
 * - [cancelled] — number of entries that were successfully transitioned to
 *   [io.dataloom.api.queue.QueueEntryState.CANCELLED].
 *
 * ## Construction-time invariants
 *
 * The following invariants are enforced at construction:
 *
 * - All counts must be non-negative.
 * - [executed] must not exceed [acquired].
 * - The sum of persisted transition counts ([completed] + [rescheduled] +
 *   [failed] + [cancelled]) must not exceed [executed].
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library types only. Safe for use in Kotlin
 * Multiplatform common code.
 *
 * @param acquired number of entries returned by provider acquisition. Must be
 *   non-negative.
 * @param executed number of entries for which the handler was invoked. Must be
 *   non-negative and must not exceed [acquired].
 * @param completed number of entries successfully transitioned via
 *   [io.dataloom.api.queue.QueueCompletionRequest]. Must be non-negative.
 * @param rescheduled number of entries successfully transitioned via
 *   [io.dataloom.api.queue.QueueRescheduleRequest]. Must be non-negative.
 * @param failed number of entries successfully transitioned via
 *   [io.dataloom.api.queue.QueueFailureRequest]. Must be non-negative.
 * @param cancelled number of entries successfully transitioned via
 *   [io.dataloom.api.queue.QueueCancellationRequest]. Must be non-negative.
 */
public data class QueueProcessingSummary(
    /** Number of entries returned by provider acquisition. */
    public val acquired: Int,

    /** Number of entries for which the handler was invoked. */
    public val executed: Int,

    /**
     * Number of entries successfully transitioned via
     * [io.dataloom.api.queue.QueueCompletionRequest].
     */
    public val completed: Int,

    /**
     * Number of entries successfully transitioned via
     * [io.dataloom.api.queue.QueueRescheduleRequest].
     */
    public val rescheduled: Int,

    /**
     * Number of entries successfully transitioned via
     * [io.dataloom.api.queue.QueueFailureRequest].
     */
    public val failed: Int,

    /**
     * Number of entries successfully transitioned via
     * [io.dataloom.api.queue.QueueCancellationRequest].
     */
    public val cancelled: Int,
) {
    init {
        require(acquired >= 0) {
            "QueueProcessingSummary acquired must be non-negative, but was $acquired."
        }
        require(executed >= 0) {
            "QueueProcessingSummary executed must be non-negative, but was $executed."
        }
        require(completed >= 0) {
            "QueueProcessingSummary completed must be non-negative, but was $completed."
        }
        require(rescheduled >= 0) {
            "QueueProcessingSummary rescheduled must be non-negative, but was $rescheduled."
        }
        require(failed >= 0) {
            "QueueProcessingSummary failed must be non-negative, but was $failed."
        }
        require(cancelled >= 0) {
            "QueueProcessingSummary cancelled must be non-negative, but was $cancelled."
        }
        require(executed <= acquired) {
            "QueueProcessingSummary executed ($executed) must not exceed acquired ($acquired)."
        }
        val persistedTotal = completed + rescheduled + failed + cancelled
        require(persistedTotal <= executed) {
            "QueueProcessingSummary persisted transition total ($persistedTotal) must not exceed " +
                "executed ($executed)."
        }
    }
}
