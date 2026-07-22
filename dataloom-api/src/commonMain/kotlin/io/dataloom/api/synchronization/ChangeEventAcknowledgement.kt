package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.ChangeEventId

/**
 * Immutable record of a remote participant's acknowledgement of a single
 * pushed [io.dataloom.api.change.ChangeEvent].
 *
 * A [ChangeEventAcknowledgement] carries the acknowledged [eventId], the
 * canonical [status], an optional canonical [error], and optional
 * [metadata]. Construction performs no retry, no deletion, and no storage or
 * queue update. Applying the acknowledgement to application-controlled
 * storage is the responsibility of a
 * [io.dataloom.api.storage.StorageProvider] implementation.
 *
 * [status] of [ChangeAcknowledgementStatus.ACCEPTED] does not require an
 * [error]. [ChangeAcknowledgementStatus.RETRY] and
 * [ChangeAcknowledgementStatus.REJECTED] may include a canonical
 * [DataLoomError], but this contract does not enforce a mandatory error for
 * either status. Provider-specific exception types must not be exposed
 * through [error].
 *
 * [metadata] must not contain payload content.
 *
 * ## Equality
 *
 * Equality compares [eventId], [status], [error], and [metadata] by value.
 *
 * @param eventId identifier of the change event being acknowledged.
 * @param status canonical acknowledgement status for the event.
 * @param error optional canonical DataLoom error associated with a non-accepted
 *   status. `null` when no error detail is supplied.
 * @param metadata optional contextual attributes for this acknowledgement.
 *   Defaults to empty metadata.
 */
public data class ChangeEventAcknowledgement(
    /** Identifier of the change event being acknowledged. */
    public val eventId: ChangeEventId,
    /** Canonical acknowledgement status for the event. */
    public val status: ChangeAcknowledgementStatus,
    /**
     * Optional canonical DataLoom error associated with a non-accepted
     * status.
     *
     * `null` when no error detail is supplied.
     */
    public val error: DataLoomError? = null,
    /**
     * Optional contextual attributes for this acknowledgement.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
