package io.dataloom.api.transport

import io.dataloom.api.change.ChangeSet

/**
 * Immutable result of a transport pull operation.
 *
 * This sealed contract represents either a successful pull that returned no
 * synchronization changes or a successful pull that returned a non-empty
 * [ChangeSet]. It remains platform-independent and does not expose response
 * bodies, status codes, headers, cursors, streams, or other protocol-specific
 * details.
 *
 * The result does not automatically apply inbound changes to storage.
 */
public sealed interface PullChangesResult {
    /**
     * Successful pull result indicating that the remote response contained no
     * synchronization changes.
     */
    public data object NoChanges : PullChangesResult

    /**
     * Successful pull result containing inbound synchronization changes.
     *
     * @param changeSet non-empty inbound change set returned by the transport
     *   operation.
     * @param hasMore `true` when another pull may return additional changes;
     *   `false` otherwise.
     */
    public data class Changes(
        /** Non-empty inbound change set returned by the transport operation. */
        public val changeSet: ChangeSet,
        /** Indicates whether another pull may return additional changes. */
        public val hasMore: Boolean,
    ) : PullChangesResult
}
