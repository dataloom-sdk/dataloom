package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable request to recover queue entries whose exclusive leases have
 * expired.
 *
 * Expired-lease recovery is triggered by the DataLoom runtime to handle
 * entries that were leased by a consumer whose process terminated before
 * completing or failing the entry. Recovery ensures that stuck entries become
 * eligible for processing again.
 *
 * ## Process-death recovery flow
 *
 * ```text
 * Entry is LEASED
 *       ↓
 * Process terminates
 *       ↓
 * Lease expires
 *       ↓
 * recoverExpiredLeases(...)
 *       ↓
 * Entry becomes recoverable
 * ```
 *
 * ## Provider responsibilities
 *
 * - Recovery must be based on persisted lease information.
 * - Recovery must not assume in-memory state survived.
 * - Expired leases must not remain permanently stuck.
 * - Recovery policy must not process an unexpired lease.
 * - The exact recovered state transition ([QueueEntryState.PENDING] or
 *   [QueueEntryState.RETRY_WAITING]) is determined by the concrete provider
 *   implementation. Implementations must document their transactional
 *   guarantees and the recovered state.
 *
 * ## Constraints
 *
 * - [currentTime] is required.
 * - [metadata] defaults to [DataLoomMetadata.Empty].
 * - Construction does not access storage or read the system clock.
 *
 * @param currentTime required current instant used to identify entries with
 *   expired leases. Supplied by the caller; construction does not read the
 *   system clock.
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class ExpiredLeaseRecoveryRequest(
    /**
     * Required current instant used to identify entries with expired leases.
     *
     * The provider compares each leased entry's
     * [QueueLease.expiresAt] against this instant to determine
     * which entries are eligible for recovery.
     */
    public val currentTime: DataLoomInstant,

    /**
     * Optional contextual attributes for this request.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
