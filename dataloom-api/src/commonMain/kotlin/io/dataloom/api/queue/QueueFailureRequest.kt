package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId

/**
 * Immutable request to mark a leased [QueueEntry] as permanently failed or
 * dead-lettered.
 *
 * Construction does not mutate storage. Persistence is the responsibility of
 * the [io.dataloom.api.provider.QueueProvider] that receives this request.
 *
 * ## Failure flow
 *
 * ```text
 * QueueProvider.acquire()
 *       ↓
 * Runtime performs synchronization
 *       ↓
 * Failure
 *       ↓
 * RetryPolicy.evaluate()
 *       ↓
 * RetryDecision.Stop
 *       ↓
 * QueueProvider.fail()
 * ```
 *
 * ## Provider responsibilities
 *
 * A successful fail operation:
 * - Transitions the entry to [QueueEntryState.FAILED] or
 *   [QueueEntryState.DEAD_LETTER] based on [disposition].
 * - Clears the active lease.
 * - Stores the canonical [error].
 * - Verifies that [leaseId] matches the currently active entry lease.
 *
 * The provider must not evaluate retry policy or recoverability.
 *
 * ## Constraints
 *
 * - All properties except [metadata] are required.
 * - [metadata] defaults to [DataLoomMetadata.Empty].
 * - Construction does not mutate storage.
 *
 * @param entryId required identifier of the queue entry to fail.
 * @param leaseId required identifier of the active lease held by the consumer.
 *   The provider must reject this request if [leaseId] does not match the
 *   current entry lease.
 * @param error required canonical error describing the processing failure.
 * @param disposition required disposition supplied by the runtime or host
 *   policy indicating whether the entry should be marked [QueueEntryState.FAILED]
 *   or [QueueEntryState.DEAD_LETTER].
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class QueueFailureRequest(
    /** Required identifier of the queue entry to fail. */
    public val entryId: QueueEntryId,

    /**
     * Required identifier of the active lease held by the consumer.
     *
     * The provider must reject this request if this value does not match the
     * current entry lease.
     */
    public val leaseId: QueueLeaseId,

    /** Required canonical error describing the processing failure. */
    public val error: DataLoomError,

    /**
     * Required disposition supplied by the runtime or host policy.
     *
     * Determines whether the entry transitions to [QueueEntryState.FAILED] or
     * [QueueEntryState.DEAD_LETTER].
     */
    public val disposition: QueueFailureDisposition,

    /**
     * Optional contextual attributes for this request.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
