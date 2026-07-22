package io.dataloom.api.storage

import io.dataloom.api.change.ChangeSet
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for applying inbound synchronization changes to
 * application-controlled storage through a [StorageProvider].
 *
 * An [InboundChangeApplyRequest] carries the originating [request] and the
 * [changeSet] of inbound changes to apply.
 *
 * Construction does not apply changes, inspect payload contents, start a
 * database transaction, or execute synchronization. The provider implementation
 * decides how change events map to the application-controlled database.
 *
 * ## Payload opacity
 *
 * The payload within each [ChangeSet] event remains opaque to DataLoom. The
 * provider implementation is responsible for interpreting and applying payloads
 * according to the application's storage architecture.
 *
 * ## Equality
 *
 * Equality compares [request] and [changeSet] by value.
 *
 * @param request immutable synchronization request associated with this apply.
 * @param changeSet non-empty inbound change set to be applied to storage.
 */
public data class InboundChangeApplyRequest(
    /** Immutable synchronization request associated with this inbound apply. */
    public val request: SynchronizationRequest,

    /** Non-empty inbound change set to be applied to application-controlled storage. */
    public val changeSet: ChangeSet,
)
