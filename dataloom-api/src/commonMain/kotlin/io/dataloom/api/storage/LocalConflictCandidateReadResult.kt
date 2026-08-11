package io.dataloom.api.storage

import io.dataloom.api.change.ChangeEvent

/**
 * Immutable result of a [StorageProvider.readLocalConflictCandidate] call.
 *
 * This sealed contract represents either "no local counterpart exists for
 * this entity" or "here is the local counterpart," and nothing else. It
 * remains platform-independent and does not expose database cursors, SQL
 * result types, or provider-specific details.
 */
public sealed interface LocalConflictCandidateReadResult {

    /**
     * No local change or state exists for the requested entity. There is
     * nothing to compare the incoming remote change against; a caller should
     * not attempt conflict detection for this entity.
     */
    public data object NotFound : LocalConflictCandidateReadResult

    /**
     * A local counterpart exists for the requested entity.
     *
     * @param localChange the local [ChangeEvent] representing the current
     *   local state for the requested entity, suitable for use as
     *   [io.dataloom.api.conflict.SynchronizationConflict.localChange] or
     *   [io.dataloom.api.conflict.ConflictDetectionRequest.localChange].
     */
    public data class Found(
        /** The local [ChangeEvent] representing the current local state. */
        public val localChange: ChangeEvent,
    ) : LocalConflictCandidateReadResult
}
