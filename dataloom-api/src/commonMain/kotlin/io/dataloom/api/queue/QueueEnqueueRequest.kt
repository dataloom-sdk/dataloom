package io.dataloom.api.queue

/**
 * Immutable request to enqueue a new [QueueEntry] in the durable
 * synchronization queue.
 *
 * Construction does not persist the entry. Persistence is the responsibility
 * of the [io.dataloom.api.queue.QueueProvider] that receives this request.
 *
 * ## Duplicate handling
 *
 * Duplicate-entry handling belongs to the provider implementation. A provider
 * must return a canonical error rather than silently replacing an existing
 * entry unless future policy explicitly permits replacement.
 *
 * ## Constraints
 *
 * - [entry] is required.
 * - The supplied entry must have state [QueueEntryState.PENDING].
 * - The supplied entry must not contain a lease.
 * - The supplied entry must not contain a retry attempt.
 *
 * @param entry required queue entry to enqueue. Must be in
 *   [QueueEntryState.PENDING] state with no lease and no retry attempt.
 */
public data class QueueEnqueueRequest(
    /** Required queue entry to persist. Must be in [QueueEntryState.PENDING] state. */
    public val entry: QueueEntry,
) {
    init {
        require(entry.state == QueueEntryState.PENDING) {
            "QueueEnqueueRequest entry must be in PENDING state, but was ${entry.state}."
        }
        require(entry.lease == null) {
            "QueueEnqueueRequest entry must not contain a lease."
        }
        require(entry.retryAttempt == null) {
            "QueueEnqueueRequest entry must not contain a retryAttempt."
        }
    }
}
