package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable request to reschedule a leased [QueueEntry] for a future retry
 * attempt.
 *
 * Construction does not schedule or delay execution. The DataLoom runtime
 * supplies the [retryAttempt] and [availableAt] values after evaluating retry
 * policy externally.
 *
 * ## Retry flow
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
 * RetryDecision.Retry
 *       ↓
 * QueueProvider.reschedule()
 * ```
 *
 * ## Provider responsibilities
 *
 * A successful reschedule operation:
 * - Transitions the entry to [QueueEntryState.RETRY_WAITING].
 * - Clears the active lease.
 * - Stores the [retryAttempt], [availableAt], and [error].
 * - Verifies that [leaseId] matches the currently active entry lease.
 *
 * The provider must not evaluate retry policy itself.
 *
 * ## Constraints
 *
 * - All properties except [metadata] are required.
 * - [metadata] defaults to [DataLoomMetadata.Empty].
 * - Construction does not schedule or delay execution.
 *
 * @param entryId required identifier of the queue entry to reschedule.
 * @param leaseId required identifier of the active lease held by the consumer.
 *   The provider must reject this request if [leaseId] does not match the
 *   current entry lease.
 * @param retryAttempt required retry attempt counter supplied by the runtime
 *   after evaluating retry policy.
 * @param availableAt required instant at which the entry becomes eligible for
 *   re-acquisition. Supplied by the runtime after evaluating retry policy.
 * @param error required canonical error that caused the retry to be scheduled.
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class QueueRescheduleRequest(
    /** Required identifier of the queue entry to reschedule. */
    public val entryId: QueueEntryId,

    /**
     * Required identifier of the active lease held by the consumer.
     *
     * The provider must reject this request if this value does not match the
     * current entry lease.
     */
    public val leaseId: QueueLeaseId,

    /**
     * Required retry attempt counter supplied by the runtime after evaluating
     * retry policy.
     */
    public val retryAttempt: RetryAttempt,

    /**
     * Required instant at which the entry becomes eligible for re-acquisition.
     *
     * Supplied by the runtime after evaluating retry policy.
     */
    public val availableAt: DataLoomInstant,

    /** Required canonical error that caused the retry to be scheduled. */
    public val error: DataLoomError,

    /**
     * Optional contextual attributes for this request.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
