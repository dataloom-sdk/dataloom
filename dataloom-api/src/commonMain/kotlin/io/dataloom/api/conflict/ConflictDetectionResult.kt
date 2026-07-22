package io.dataloom.api.conflict

/**
 * Sealed result of a conflict-detection evaluation performed by a
 * [ConflictDetector].
 *
 * A [ConflictDetectionResult] is either [NoConflict] when the detector finds
 * no conflict, or [ConflictDetected] when the detector identifies a conflict.
 *
 * Creating a result does not mutate storage, queues, or workflow state.
 *
 * ## Variants
 *
 * - [NoConflict] — the detector found no conflict between the local and remote
 *   changes.
 * - [ConflictDetected] — the detector identified a conflict, represented by a
 *   canonical [SynchronizationConflict].
 */
public sealed interface ConflictDetectionResult {

    /**
     * Indicates that no conflict was detected between the local and remote
     * changes.
     *
     * The synchronization runtime may proceed with normal synchronization flow
     * when this result is returned.
     */
    public data object NoConflict : ConflictDetectionResult

    /**
     * Indicates that a conflict was detected between the local and remote
     * changes.
     *
     * The [conflict] carries the canonical [SynchronizationConflict] describing
     * the detected conflict. The synchronization runtime will forward this
     * conflict to a [ConflictResolver].
     *
     * @param conflict the detected [SynchronizationConflict].
     */
    public data class ConflictDetected(
        /** The detected synchronization conflict. */
        public val conflict: SynchronizationConflict,
    ) : ConflictDetectionResult
}
