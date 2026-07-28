package io.dataloom.api.queue

/**
 * Immutable result of an expired-lease recovery operation.
 *
 * [ExpiredLeaseRecoveryResult] reports how many queue entries were recovered
 * from an expired-lease state during a
 * [io.dataloom.api.queue.QueueProvider.recoverExpiredLeases] operation.
 *
 * ## Constraints
 *
 * - [recoveredEntries] must be zero or greater.
 * - Negative values are rejected at construction.
 * - No mutable collection is exposed.
 *
 * ## Equality
 *
 * Equality compares [recoveredEntries] by value.
 *
 * @param recoveredEntries the number of queue entries that were recovered from
 *   an expired lease. Must be zero or greater.
 */
public data class ExpiredLeaseRecoveryResult(
    /**
     * The number of queue entries recovered from an expired lease during this
     * operation.
     *
     * Must be zero or greater.
     */
    public val recoveredEntries: Int,
) {
    init {
        require(recoveredEntries >= 0) {
            "ExpiredLeaseRecoveryResult recoveredEntries must be zero or greater, " +
                "but was $recoveredEntries."
        }
    }
}
