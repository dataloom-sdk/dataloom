package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken

/**
 * Immutable, opaque synchronization checkpoint for a logical checkpoint
 * stream.
 *
 * A [SynchronizationCheckpoint] pairs a [key] identifying the logical
 * synchronization stream with an opaque [token] representing progress within
 * that stream. DataLoom does not interpret [token] semantics, compare token
 * ordering, or generate checkpoints automatically.
 *
 * Construction performs no persistence and does not advance any stored
 * checkpoint. Persisting or activating a checkpoint is the responsibility of
 * a [io.dataloom.api.storage.StorageProvider] implementation, invoked only
 * after the associated inbound changes have been applied successfully.
 *
 * ## Sensitive-data restrictions
 *
 * [metadata] must not contain access tokens, passwords, encryption keys,
 * private certificates, personal data, or full application payloads.
 * [token] must not be treated as a credential.
 *
 * ## Equality
 *
 * Equality compares [key], [token], and [metadata] by value.
 *
 * @param key identifier for the logical synchronization stream this
 *   checkpoint belongs to.
 * @param token opaque synchronization progress marker.
 * @param metadata optional contextual attributes for this checkpoint.
 *   Defaults to empty metadata.
 */
public data class SynchronizationCheckpoint(
    /** Identifier for the logical synchronization stream this checkpoint belongs to. */
    public val key: CheckpointKey,
    /** Opaque synchronization progress marker. */
    public val token: CheckpointToken,
    /**
     * Optional contextual attributes for this checkpoint.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
