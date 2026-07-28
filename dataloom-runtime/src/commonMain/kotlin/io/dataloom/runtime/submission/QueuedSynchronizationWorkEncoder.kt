package io.dataloom.runtime.submission

/**
 * Application-owned contract for encoding a [QueuedSynchronizationSubmission]
 * into a [QueuedSynchronizationWorkEncodingResult].
 *
 * ## Purpose
 *
 * [QueuedSynchronizationWorkEncoder] gives the host application full control
 * over how [io.dataloom.runtime.queue.QueuedSynchronizationWork] is converted
 * into the opaque payload representation required by
 * [io.dataloom.api.queue.QueueEnqueueRequest]. DataLoom does not inspect,
 * interpret, or validate the application-defined payload content beyond
 * structural field correspondence.
 *
 * ## No mandatory serialization format
 *
 * DataLoom does not define JSON, Protocol Buffers, Java serialization, Kotlin
 * Serialization, or any other serialization format. The application chooses
 * its own encoding strategy independently of DataLoom.
 *
 * ## Responsibilities
 *
 * Implementations must:
 * - Accept the exact [QueuedSynchronizationSubmission] passed by the
 *   [DataLoomQueueSubmission] implementation unchanged.
 * - Return a [QueuedSynchronizationWorkEncodingResult.Encoded] when encoding
 *   succeeds, carrying the exact
 *   [io.dataloom.api.queue.QueueEnqueueRequest] ready for persistence.
 * - Return a [QueuedSynchronizationWorkEncodingResult.Rejected] with a
 *   canonical [io.dataloom.api.error.DataLoomError] when the submission
 *   cannot be encoded due to a known structural or policy condition.
 *
 * Implementations must not:
 * - Call any [io.dataloom.api.queue.QueueProvider] operation.
 * - Execute synchronization.
 * - Evaluate retry policy.
 * - Invoke scheduling.
 * - Read the system clock.
 * - Generate identifiers.
 *
 * ## Unexpected exceptions
 *
 * Unexpected programming exceptions from encoder implementations propagate
 * normally. [DataLoomQueueSubmission] does not convert unexpected exceptions
 * into [QueuedSynchronizationWorkEncodingResult.Rejected]. Applications should
 * test their encoder implementations independently.
 *
 * ## Encoder and resolver compatibility
 *
 * The encoder used for submission must be compatible with the
 * [io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver] used by queued
 * execution. DataLoom does not verify payload round-trip compatibility during
 * build or submission. Applications are responsible for ensuring that:
 *
 * ```
 * encode(submission.work)
 *     → durable queue storage
 *     → resolver.resolve(queueEntry)
 *     → equivalent QueuedSynchronizationWork
 * ```
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @see QueuedSynchronizationSubmission
 * @see QueuedSynchronizationWorkEncodingResult
 * @see DataLoomQueueSubmission
 */
public fun interface QueuedSynchronizationWorkEncoder {

    /**
     * Encodes the supplied [submission] into a
     * [QueuedSynchronizationWorkEncodingResult].
     *
     * This method is invoked exactly once per [DataLoomQueueSubmission.submit]
     * call. The exact [submission] is forwarded unchanged.
     *
     * [kotlinx.coroutines.CancellationException] is not produced by this
     * interface but must not be swallowed by implementations.
     *
     * @param submission the exact [QueuedSynchronizationSubmission] to encode.
     * @return a [QueuedSynchronizationWorkEncodingResult] describing the
     *   encoding outcome.
     */
    public fun encode(
        submission: QueuedSynchronizationSubmission,
    ): QueuedSynchronizationWorkEncodingResult
}
