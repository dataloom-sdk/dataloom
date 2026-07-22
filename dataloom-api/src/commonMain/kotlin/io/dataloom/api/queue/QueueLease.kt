package io.dataloom.api.queue

import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable exclusive lease held by a consumer over a [QueueEntry].
 *
 * A [QueueLease] protects a queue entry from concurrent acquisition by
 * other consumers while it is being processed. The lease is created atomically
 * alongside the entry acquisition and expires at [expiresAt].
 *
 * ## Constraints
 *
 * - All properties are required.
 * - [expiresAt] must be strictly later than [acquiredAt]. Equal and earlier
 *   values are rejected at construction.
 * - Construction does not access the clock, generate identifiers, or extend
 *   the lease.
 *
 * ## Lease renewal
 *
 * Lease renewal is deferred to a future issue.
 *
 * ## Equality
 *
 * Equality compares [id], [consumerId], [acquiredAt], and [expiresAt] by value.
 *
 * @param id required unique identifier for this lease.
 * @param consumerId required identifier for the consumer that holds this lease.
 * @param acquiredAt required instant at which this lease was acquired.
 * @param expiresAt required instant at which this lease expires. Must be
 *   strictly later than [acquiredAt].
 */
public data class QueueLease(
    /** Required unique identifier for this lease. */
    public val id: QueueLeaseId,

    /** Required identifier for the consumer that holds this lease. */
    public val consumerId: QueueConsumerId,

    /** Required instant at which this lease was acquired. */
    public val acquiredAt: DataLoomInstant,

    /**
     * Required instant at which this lease expires.
     *
     * Must be strictly later than [acquiredAt].
     */
    public val expiresAt: DataLoomInstant,
) {
    init {
        require(expiresAt.epochMilliseconds > acquiredAt.epochMilliseconds) {
            "QueueLease expiresAt (${expiresAt.epochMilliseconds}) must be strictly later than " +
                "acquiredAt (${acquiredAt.epochMilliseconds})."
        }
    }
}
