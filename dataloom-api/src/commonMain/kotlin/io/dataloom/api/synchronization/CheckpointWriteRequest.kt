package io.dataloom.api.synchronization

import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for writing a [SynchronizationCheckpoint] through a
 * [io.dataloom.api.storage.StorageProvider].
 *
 * A [CheckpointWriteRequest] carries the originating [request] together with
 * the [checkpoint] to persist. Construction performs no persistence.
 *
 * The runtime must not issue this request until all inbound changes
 * associated with [checkpoint] have been applied successfully.
 *
 * ## Equality
 *
 * Equality compares [request] and [checkpoint] by value.
 *
 * @param request immutable synchronization request associated with this
 *   checkpoint write.
 * @param checkpoint checkpoint to persist in application-controlled storage.
 */
public data class CheckpointWriteRequest(
    /** Immutable synchronization request associated with this checkpoint write. */
    public val request: SynchronizationRequest,
    /** Checkpoint to persist in application-controlled storage. */
    public val checkpoint: SynchronizationCheckpoint,
)
