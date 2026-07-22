package io.dataloom.api.storage

import io.dataloom.api.change.ChangeSet

/**
 * Immutable result of an outbound change-read operation performed by a
 * [StorageProvider].
 *
 * This sealed contract represents either a successful read that returned no
 * outbound changes or a successful read that returned a non-empty [ChangeSet].
 * It remains platform-independent and does not expose database cursors, SQL
 * result types, or provider-specific details.
 *
 * The result does not automatically acknowledge, delete, or mark events as
 * synchronized. Acknowledgement and checkpoint semantics are deferred to a
 * later issue.
 */
public sealed interface OutboundChangeReadResult {

    /**
     * Successful read result indicating that no outbound synchronization
     * changes are currently available in storage.
     */
    public data object NoChanges : OutboundChangeReadResult

    /**
     * Successful read result containing outbound synchronization changes.
     *
     * @param changeSet non-empty outbound change set returned from storage.
     * @param hasMore `true` when another read may return additional changes;
     *   `false` otherwise.
     */
    public data class Changes(
        /** Non-empty outbound change set returned from storage. */
        public val changeSet: ChangeSet,
        /** Indicates whether another read may return additional changes. */
        public val hasMore: Boolean,
    ) : OutboundChangeReadResult
}
