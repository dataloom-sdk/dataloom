package io.dataloom.runtime.submission

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueuedSynchronizationWork

/**
 * Immutable value carrying the application-supplied inputs for one queue
 * submission.
 *
 * ## Purpose
 *
 * [QueuedSynchronizationSubmission] is the entry point for the
 * [DataLoomQueueSubmission.submit] operation. It preserves the exact
 * [QueueEntryId], [QueuedSynchronizationWork], and availability timestamp
 * without modification.
 *
 * ## Explicit caller-supplied values
 *
 * The caller supplies [queueEntryId] and [availableAt] explicitly. DataLoom
 * does not generate identifiers or read the system clock on behalf of the
 * caller. Explicit identifiers make queue submission deterministic and allow
 * applications to apply their own idempotency strategy.
 *
 * ## No encoding at construction
 *
 * Construction does not encode [work], call any [QueueProvider][io.dataloom.api.provider.QueueProvider],
 * read the clock, generate identifiers, or perform any I/O. Encoding is
 * deferred to the [QueuedSynchronizationWorkEncoder] invoked by the
 * [DataLoomQueueSubmission] implementation.
 *
 * ## Idempotency guidance
 *
 * [queueEntryId] should remain stable when the application retries the same
 * logical submission. The [io.dataloom.api.provider.QueueProvider] determines
 * duplicate-ID handling. DataLoom invokes enqueue at most once per
 * [DataLoomQueueSubmission.submit] call and does not automatically retry
 * provider failures.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param queueEntryId the unique identifier for this queue entry, supplied
 *   explicitly by the caller. Required.
 * @param work the synchronization work to enqueue, carrying the exact
 *   [io.dataloom.api.model.SynchronizationRequest] and
 *   [io.dataloom.core.provider.SynchronizationProviderBindings] needed for
 *   execution. Required.
 * @param availableAt the instant at which this entry becomes eligible for
 *   acquisition. Supplied explicitly by the caller. Required.
 */
public class QueuedSynchronizationSubmission(
    /** Unique identifier for this queue entry, supplied explicitly by the caller. */
    public val queueEntryId: QueueEntryId,

    /**
     * The synchronization work to enqueue.
     *
     * Carries the exact [io.dataloom.api.model.SynchronizationRequest] and
     * [io.dataloom.core.provider.SynchronizationProviderBindings] required for
     * execution. Preserved without modification.
     */
    public val work: QueuedSynchronizationWork,

    /**
     * The instant at which this entry becomes eligible for acquisition.
     *
     * Supplied explicitly by the caller. DataLoom does not read the system
     * clock or substitute a default value.
     */
    public val availableAt: DataLoomInstant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueuedSynchronizationSubmission) return false
        return queueEntryId == other.queueEntryId &&
            work == other.work &&
            availableAt == other.availableAt
    }

    override fun hashCode(): Int {
        var result = queueEntryId.hashCode()
        result = 31 * result + work.hashCode()
        result = 31 * result + availableAt.hashCode()
        return result
    }

    /**
     * Returns a safe diagnostic representation.
     *
     * Includes only the queue entry ID and availability instant. Does not
     * expose credentials, tokens, encryption keys, personal data, or encoded
     * payload bytes.
     */
    override fun toString(): String =
        "QueuedSynchronizationSubmission(" +
            "queueEntryId=${queueEntryId.value}, " +
            "availableAt=$availableAt" +
            ")"
}
