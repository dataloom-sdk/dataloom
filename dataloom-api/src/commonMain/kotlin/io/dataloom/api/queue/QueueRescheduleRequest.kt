package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable request to reschedule a leased [QueueEntry] for a future retry.
 *
 * The runtime evaluates retry policy and central budgets before constructing
 * this request. A successful provider transition stores [retryAttempt],
 * [retryBudgetState], [availableAt], and [error] atomically while clearing the
 * active lease. A null [retryBudgetState] means central elapsed/cumulative
 * budgets are not enabled for this work item.
 *
 * The provider must verify [leaseId] and must not evaluate policy itself.
 */
public data class QueueRescheduleRequest(
    public val entryId: QueueEntryId,
    public val leaseId: QueueLeaseId,
    public val retryAttempt: RetryAttempt,
    public val availableAt: DataLoomInstant,
    public val error: DataLoomError,
    public val retryBudgetState: RetryBudgetState? = null,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
