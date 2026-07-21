package io.dataloom.api.transport

import io.dataloom.api.change.ChangeSet
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for pushing outbound synchronization changes through a
 * [TransportProvider].
 *
 * A [PushChangesRequest] carries a previously defined synchronization request
 * together with the outbound [ChangeSet] selected for transport. Construction
 * performs no network communication, serialization, authentication, retry, or
 * payload inspection.
 *
 * ## Equality
 *
 * Equality compares [request] and [changeSet] by value.
 *
 * @param request immutable synchronization request associated with this push.
 * @param changeSet immutable outbound change set to transport.
 */
public data class PushChangesRequest(
    /** Immutable synchronization request associated with this push. */
    public val request: SynchronizationRequest,
    /** Immutable outbound change set to transport. */
    public val changeSet: ChangeSet,
)
