package io.dataloom.api.queue

/**
 * Immutable request to enqueue a new [QueueEntry] in the durable
 * synchronization queue.
 *
 * Construction does not persist the entry. Persistence is the responsibility
 * of the [QueueProvider] that receives this request.
 *
 * Duplicate-entry handling belongs to the provider implementation. A provider
 * must return a canonical error rather than silently replacing an existing
 * entry unless explicit policy permits replacement.
 *
 * The supplied entry must be PENDING and must not contain a lease, retry
 * attempt, or retry-budget state. Budget state starts only after a genuine
 * failure is accepted for retry.
 */
public data class QueueEnqueueRequest(
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
        require(entry.retryBudgetState == null) {
            "QueueEnqueueRequest entry must not contain retryBudgetState."
        }
    }
}
