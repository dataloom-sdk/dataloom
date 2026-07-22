package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable model representing a single entry in the DataLoom durable
 * synchronization queue.
 *
 * A [QueueEntry] carries the synchronization request and all lifecycle
 * metadata needed for the DataLoom runtime to persist, acquire, process,
 * retry, and complete durable synchronization work.
 *
 * ## Non-runtime behavior
 *
 * Construction does not enqueue the entry, read the system clock, schedule
 * execution, evaluate retry policy, or perform synchronization.
 *
 * ## State invariants
 *
 * The following invariants are enforced at construction:
 *
 * - [QueueEntryState.LEASED] requires a non-null [lease].
 * - Every state other than [QueueEntryState.LEASED] requires a null [lease].
 * - [QueueEntryState.RETRY_WAITING] requires a non-null [retryAttempt].
 * - [QueueEntryState.PENDING] must not contain a [retryAttempt].
 * - [QueueEntryState.PENDING] must not contain a [lease].
 * - [QueueEntryState.RETRY_WAITING] must not contain a [lease].
 * - [availableAt] must not be earlier than [enqueuedAt].
 *
 * ## Equality
 *
 * Equality compares all properties by value.
 *
 * @param id required unique identifier for this queue entry.
 * @param synchronizationRequest required immutable synchronization intent.
 * @param state required current lifecycle state of this entry.
 * @param enqueuedAt required instant at which this entry was enqueued.
 * @param availableAt required instant at which this entry becomes eligible for
 *   acquisition. Must not be earlier than [enqueuedAt].
 * @param retryAttempt optional retry attempt counter. Required when [state] is
 *   [QueueEntryState.RETRY_WAITING]. Must be null when [state] is
 *   [QueueEntryState.PENDING].
 * @param lease optional exclusive lease held by a consumer. Required when
 *   [state] is [QueueEntryState.LEASED]. Must be null for all other states.
 * @param lastError optional canonical error from the last processing failure.
 *   May be present for [QueueEntryState.RETRY_WAITING],
 *   [QueueEntryState.FAILED], and [QueueEntryState.DEAD_LETTER] states.
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class QueueEntry(
    /** Required unique identifier for this queue entry. */
    public val id: QueueEntryId,

    /** Required immutable synchronization intent for this entry. */
    public val synchronizationRequest: SynchronizationRequest,

    /** Required current lifecycle state of this queue entry. */
    public val state: QueueEntryState,

    /** Required instant at which this entry was enqueued. */
    public val enqueuedAt: DataLoomInstant,

    /**
     * Required instant at which this entry becomes eligible for acquisition.
     *
     * Must not be earlier than [enqueuedAt].
     */
    public val availableAt: DataLoomInstant,

    /**
     * Optional retry attempt counter.
     *
     * Required when [state] is [QueueEntryState.RETRY_WAITING].
     * Must be null when [state] is [QueueEntryState.PENDING].
     */
    public val retryAttempt: RetryAttempt? = null,

    /**
     * Optional exclusive lease held by a consumer while processing this entry.
     *
     * Required when [state] is [QueueEntryState.LEASED].
     * Must be null for all other states.
     */
    public val lease: QueueLease? = null,

    /**
     * Optional canonical error from the last processing failure.
     *
     * May be present for [QueueEntryState.RETRY_WAITING],
     * [QueueEntryState.FAILED], and [QueueEntryState.DEAD_LETTER] states.
     */
    public val lastError: DataLoomError? = null,

    /**
     * Optional contextual attributes for this entry.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied. Must not contain
     * credentials, tokens, encryption keys, personal data, or full payloads.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
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
    }
}
