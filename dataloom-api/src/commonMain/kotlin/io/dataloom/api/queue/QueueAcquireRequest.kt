package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable request to atomically acquire eligible queue entries and assign
 * an exclusive lease.
 *
 * Construction does not acquire any entries. Acquisition is performed
 * atomically by the [io.dataloom.api.queue.QueueProvider] that receives
 * this request.
 *
 * ## Atomic acquisition invariant
 *
 * Acquiring eligible queue entries and assigning their lease must happen in
 * one atomic provider operation. A provider must not read eligible entries,
 * return them, and assign the lease later in a separate step. Doing so could
 * allow multiple consumers to process the same entry concurrently.
 *
 * Eligible entries are conceptually:
 * ```text
 * PENDING where availableAt <= acquiredAt
 * ```
 * or:
 * ```text
 * RETRY_WAITING where availableAt <= acquiredAt
 * ```
 *
 * ## Batch acquisition
 *
 * The same [leaseId] may be shared across a single atomic batch acquisition.
 * All entries acquired in one batch operation share the same lease.
 *
 * ## Constraints
 *
 * - [consumerId] and [leaseId] are required.
 * - [acquiredAt] and [leaseExpiresAt] are required.
 * - [leaseExpiresAt] must be strictly later than [acquiredAt].
 * - [maxEntries] must be greater than zero.
 * - [metadata] defaults to [DataLoomMetadata.Empty].
 * - Construction does not acquire work.
 *
 * @param consumerId required identifier for the consumer performing acquisition.
 * @param leaseId required unique lease identifier for this acquisition batch.
 * @param acquiredAt required instant at which acquisition is performed.
 * @param leaseExpiresAt required instant at which the acquired lease expires.
 *   Must be strictly later than [acquiredAt].
 * @param maxEntries maximum number of entries to acquire in this operation.
 *   Must be greater than zero.
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class QueueAcquireRequest(
    /** Required identifier for the consumer performing acquisition. */
    public val consumerId: QueueConsumerId,

    /** Required unique lease identifier for this acquisition batch. */
    public val leaseId: QueueLeaseId,

    /** Required instant at which acquisition is performed. */
    public val acquiredAt: DataLoomInstant,

    /**
     * Required instant at which the acquired lease expires.
     *
     * Must be strictly later than [acquiredAt].
     */
    public val leaseExpiresAt: DataLoomInstant,

    /**
     * Maximum number of entries to acquire in this operation.
     *
     * Must be greater than zero.
     */
    public val maxEntries: Int,

    /**
     * Optional contextual attributes for this request.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    init {
        require(leaseExpiresAt.epochMilliseconds > acquiredAt.epochMilliseconds) {
            "QueueAcquireRequest leaseExpiresAt (${leaseExpiresAt.epochMilliseconds}) must be " +
                "strictly later than acquiredAt (${acquiredAt.epochMilliseconds})."
        }
        require(maxEntries > 0) {
            "QueueAcquireRequest maxEntries must be greater than zero, but was $maxEntries."
        }
    }
}
