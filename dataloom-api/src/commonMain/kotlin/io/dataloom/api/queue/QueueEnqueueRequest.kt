package io.dataloom.api.queue

/**
 * Immutable request to persist one initially pending [QueueEntry].
 *
 * Construction does not persist the entry. Persistence is the responsibility
 * of the queue provider that receives this request.
 *
 * [QueueProvider.enqueue] retains the historical create-only behavior and must
 * return a canonical failure for an existing ID. Applications and runtimes
 * that need first-or-existing reconciliation must explicitly require
 * [QueueIdempotentAdmissionProvider] and call
 * [QueueIdempotentAdmissionProvider.admit]. Duplicate semantics must never be
 * inferred by parsing an enqueue error code or message.
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
