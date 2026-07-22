package io.dataloom.api.transport

import io.dataloom.api.change.ChangeSet
import io.dataloom.api.synchronization.SynchronizationCheckpoint

/**
 * Immutable result of a transport pull operation.
 *
 * This sealed contract represents either a successful pull that returned no
 * synchronization changes or a successful pull that returned a non-empty
 * [ChangeSet]. It remains platform-independent and does not expose response
 * bodies, status codes, headers, cursors, streams, or other protocol-specific
 * details.
 *
 * Both variants may carry an optional next [SynchronizationCheckpoint]. A
 * next checkpoint may be absent. Returning a checkpoint does not persist or
 * activate it: the runtime must persist it only after the inbound work
 * associated with the pull has succeeded (see
 * [io.dataloom.api.storage.StorageProvider.writeCheckpoint]).
 *
 * The result does not automatically apply inbound changes to storage.
 */
public sealed interface PullChangesResult {
    /**
     * Successful pull result indicating that the remote response contained no
     * synchronization changes.
     *
     * @param nextCheckpoint optional next checkpoint returned by the remote
     *   participant even though no changes were returned. `null` when no next
     *   checkpoint is supplied.
     */
    public data class NoChanges(
        /** Optional next checkpoint returned alongside a no-changes result. */
        public val nextCheckpoint: SynchronizationCheckpoint? = null,
    ) : PullChangesResult

    /**
     * Successful pull result containing inbound synchronization changes.
     *
     * @param changeSet non-empty inbound change set returned by the transport
     *   operation.
     * @param hasMore `true` when another pull may return additional changes;
     *   `false` otherwise.
     * @param nextCheckpoint optional next checkpoint associated with
     *   [changeSet]. `null` when no next checkpoint is supplied.
     */
    public data class Changes(
        /** Non-empty inbound change set returned by the transport operation. */
        public val changeSet: ChangeSet,
        /** Indicates whether another pull may return additional changes. */
        public val hasMore: Boolean,
        /** Optional next checkpoint associated with [changeSet]. */
        public val nextCheckpoint: SynchronizationCheckpoint? = null,
    ) : PullChangesResult
}
