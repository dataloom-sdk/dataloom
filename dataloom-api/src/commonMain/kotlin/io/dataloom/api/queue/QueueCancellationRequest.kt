package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.QueueEntryId

/**
 * Immutable request to cancel a queue entry.
 *
 * Construction does not cancel any queue entry. Cancellation is performed by
 * the [io.dataloom.api.provider.QueueProvider] that receives this request.
 *
 * ## Cancellation semantics
 *
 * - Cancellation of an actively leased entry may fail or be deferred according
 *   to provider and runtime policy.
 * - Cancellation does not automatically cancel a running coroutine. Runtime
 *   execution cancellation is deferred to a future issue.
 * - A successful cancellation transitions the entry to
 *   [QueueEntryState.CANCELLED].
 *
 * ## Constraints
 *
 * - [entryId] and [context] are required.
 * - [metadata] defaults to [DataLoomMetadata.Empty].
 * - Construction does not cancel an entry.
 *
 * @param entryId required identifier of the queue entry to cancel.
 * @param context required immutable execution context for this cancellation
 *   request.
 * @param metadata optional contextual attributes. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class QueueCancellationRequest(
    /** Required identifier of the queue entry to cancel. */
    public val entryId: QueueEntryId,

    /**
     * Required immutable execution context associated with this cancellation
     * request.
     */
    public val context: ExecutionContext,

    /**
     * Optional contextual attributes for this request.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
