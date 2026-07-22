package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable request to mark a leased [QueueEntry] as successfully completed.
 *
 * Construction does not update storage. Persistence is the responsibility of
 * the [io.dataloom.api.provider.QueueProvider] that receives this request.
 *
 * ## Lease validation
 *
 * The provider must verify that [leaseId] matches the currently active lease
 * on the entry identified by [entryId]. Completion using a stale or mismatched
 * lease must fail canonically.
 *
 * ## Constraints
 *
 * - [entryId], [leaseId], and [completedAt] are required.
 * - [metadata] defaults to [DataLoomMetadata.Empty].
 * - Construction does not update storage.
 *
 * @param entryId required identifier of the queue entry to complete.
 * @param leaseId required identifier of the active lease held by the consumer.
 *   The provider must reject this request if [leaseId] does not match the
 *   current entry lease.
 * @param completedAt required instant at which completion is reported.
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class QueueCompletionRequest(
    /** Required identifier of the queue entry to complete. */
    public val entryId: QueueEntryId,

    /**
     * Required identifier of the active lease held by the consumer.
     *
     * The provider must reject this request if this value does not match the
     * current entry lease.
     */
    public val leaseId: QueueLeaseId,

    /** Required instant at which completion is reported. */
    public val completedAt: DataLoomInstant,

    /**
     * Optional contextual attributes for this request.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
