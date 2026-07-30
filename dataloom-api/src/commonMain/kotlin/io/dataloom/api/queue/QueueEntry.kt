package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable model representing a single entry in the DataLoom durable
 * synchronization queue.
 *
 * A [QueueEntry] carries the synchronization request and all lifecycle
 * metadata needed for the DataLoom runtime to persist, acquire, process,
 * retry, and complete durable synchronization work.
 *
 * Construction does not enqueue the entry, read the system clock, schedule
 * execution, evaluate retry policy, or perform synchronization.
 *
 * The following invariants are enforced:
 *
 * - [QueueEntryState.LEASED] requires a non-null [lease].
 * - Every state other than [QueueEntryState.LEASED] requires a null [lease].
 * - [QueueEntryState.RETRY_WAITING] requires a non-null [retryAttempt].
 * - [QueueEntryState.PENDING] must not contain retry state.
 * - A non-null [retryBudgetState] requires a non-null [retryAttempt].
 * - [availableAt] must not be earlier than [enqueuedAt].
 *
 * [retryBudgetState] is appended after the original constructor properties so
 * existing source calls keep their parameter order.
 */
public data class QueueEntry(
    public val id: QueueEntryId,
    public val synchronizationRequest: SynchronizationRequest,
    public val state: QueueEntryState,
    public val enqueuedAt: DataLoomInstant,
    public val availableAt: DataLoomInstant,
    public val retryAttempt: RetryAttempt? = null,
    public val lease: QueueLease? = null,
    public val lastError: DataLoomError? = null,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    public val retryBudgetState: RetryBudgetState? = null,
) {
    init {
        require(availableAt.epochMilliseconds >= enqueuedAt.epochMilliseconds) {
            "QueueEntry availableAt (${availableAt.epochMilliseconds}) must not be earlier than " +
                "enqueuedAt (${enqueuedAt.epochMilliseconds})."
        }
        require(state != QueueEntryState.LEASED || lease != null) {
            "QueueEntry in state LEASED must have a non-null lease."
        }
        require(state == QueueEntryState.LEASED || lease == null) {
            "QueueEntry in state $state must have a null lease."
        }
        require(state != QueueEntryState.RETRY_WAITING || retryAttempt != null) {
            "QueueEntry in state RETRY_WAITING must have a non-null retryAttempt."
        }
        require(state != QueueEntryState.PENDING || retryAttempt == null) {
            "QueueEntry in state PENDING must not contain a retryAttempt."
        }
        require(state != QueueEntryState.PENDING || retryBudgetState == null) {
            "QueueEntry in state PENDING must not contain retryBudgetState."
        }
        require(retryBudgetState == null || retryAttempt != null) {
            "QueueEntry retryBudgetState requires a non-null retryAttempt."
        }
    }
}
