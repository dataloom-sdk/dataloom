package io.dataloom.api.synchronization

import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable request for reading a stored [SynchronizationCheckpoint] through
 * a [io.dataloom.api.storage.StorageProvider].
 *
 * A [CheckpointReadRequest] carries the originating [request] together with
 * the [key] identifying the logical synchronization stream whose checkpoint
 * is being read. Construction performs no storage access.
 *
 * ## Equality
 *
 * Equality compares [request] and [key] by value.
 *
 * @param request immutable synchronization request associated with this
 *   checkpoint read.
 * @param key identifier for the logical synchronization stream whose
 *   checkpoint is being read.
 */
public data class CheckpointReadRequest(
    /** Immutable synchronization request associated with this checkpoint read. */
    public val request: SynchronizationRequest,
    /** Identifier for the logical synchronization stream whose checkpoint is being read. */
    public val key: CheckpointKey,
)
