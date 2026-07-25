package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.queue.QueueEntry

/**
 * Sealed result produced by [QueuedSynchronizationWorkResolver.resolve] for
 * a single acquired [QueueEntry].
 *
 * ## Variants
 *
 * | Variant    | Meaning                                                          |
 * |------------|------------------------------------------------------------------|
 * | [Resolved] | Work was successfully resolved and is ready for execution.       |
 * | [Rejected] | Work could not be resolved due to a structural or policy error.  |
 *
 * ## Relationship to execution outcomes
 *
 * A [Rejected] resolution causes the
 * [QueuedSynchronizationExecutionHandler] to return
 * [QueueEntryExecutionOutcome.Failed][io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Failed]
 * immediately, without invoking the synchronization coordinator.
 *
 * ## Sensitive-data restrictions
 *
 * [Rejected.error] must not expose credentials, tokens, encryption keys,
 * personal data, full synchronization payloads, raw [Throwable] instances, or
 * stack traces.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface QueuedSynchronizationWorkResolution {

    /**
     * Work was successfully resolved and is ready for execution.
     *
     * [work] is the exact [QueuedSynchronizationWork] that the
     * [QueuedSynchronizationWorkResolver] produced for this entry.
     *
     * ## Construction restrictions
     *
     * Construction does not execute synchronization, read the clock, or
     * enqueue work.
     *
     * @param work the resolved [QueuedSynchronizationWork]. Required.
     */
    public data class Resolved(
        /** The resolved [QueuedSynchronizationWork] ready for execution. */
        public val work: QueuedSynchronizationWork,
    ) : QueuedSynchronizationWorkResolution

    /**
     * Work could not be resolved due to a structural or policy error.
     *
     * [error] classifies why resolution failed. This resolution causes the
     * [QueuedSynchronizationExecutionHandler] to return
     * [QueueEntryExecutionOutcome.Failed][io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Failed]
     * without invoking the synchronization coordinator.
     *
     * ## Sensitive-data restrictions
     *
     * [error] must not expose credentials, tokens, encryption keys, personal
     * data, full payloads, raw [Throwable] instances, or stack traces.
     *
     * ## Construction restrictions
     *
     * Construction does not execute synchronization, read the clock, or
     * enqueue work.
     *
     * @param error the canonical [DataLoomError] classifying the resolution
     *   failure. Required.
     */
    public data class Rejected(
        /** The canonical error classifying the resolution failure. */
        public val error: DataLoomError,
    ) : QueuedSynchronizationWorkResolution
}
