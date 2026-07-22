package io.dataloom.api.synchronization

import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for acknowledging outbound synchronization changes in
 * application-controlled storage through a
 * [io.dataloom.api.storage.StorageProvider].
 *
 * An [OutboundChangeAcknowledgementRequest] carries the originating [request]
 * together with the remote [acknowledgement] returned by
 * [io.dataloom.api.transport.TransportProvider.pushChanges]. Construction
 * performs no storage operation. It does not automatically delete local
 * changes and does not implement retry handling.
 *
 * ## Equality
 *
 * Equality compares [request] and [acknowledgement] by value.
 *
 * @param request immutable synchronization request associated with this
 *   acknowledgement.
 * @param acknowledgement change-set acknowledgement to record in
 *   application-controlled storage.
 */
public data class OutboundChangeAcknowledgementRequest(
    /** Immutable synchronization request associated with this acknowledgement. */
    public val request: SynchronizationRequest,
    /** Change-set acknowledgement to record in application-controlled storage. */
    public val acknowledgement: ChangeSetAcknowledgement,
)
