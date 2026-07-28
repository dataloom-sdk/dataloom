package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable request to defer leased queue work without consuming a retry
 * attempt.
 *
 * A successful provider transition must:
 *
 * - verify that [leaseId] is still the active lease;
 * - set [availableAt] and clear the active lease;
 * - preserve the entry's retry attempt exactly;
 * - transition to [QueueEntryState.PENDING] when the preserved attempt is
 *   `null`; or
 * - transition to [QueueEntryState.RETRY_WAITING] when a prior attempt exists.
 *
 * The provider must not fabricate an attempt, evaluate retry policy, or encode
 * [reason] as a retry failure.
 *
 * @param entryId identifier of the acquired queue entry.
 * @param leaseId identifier of the active lease held by the caller.
 * @param availableAt instant at which the entry becomes eligible again.
 * @param reason stable reason for the non-retry deferral.
 * @param metadata optional safe contextual attributes.
 */
public data class QueueDeferralRequest(
    public val entryId: QueueEntryId,
    public val leaseId: QueueLeaseId,
    public val availableAt: DataLoomInstant,
    public val reason: QueueDeferralReason,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
