package io.dataloom.runtime.queue

import io.dataloom.api.queue.QueueEntry

/**
 * Contract for executing a single acquired [QueueEntry].
 *
 * [QueueEntryExecutionHandler] is the application-level execution boundary
 * invoked by [DurableQueueExecutionProcessor] for each entry acquired from
 * the durable queue. It receives the exact [QueueEntry] returned by the
 * provider and returns a structured [QueueEntryExecutionOutcome] that describes
 * how the entry should be transitioned.
 *
 * ## Responsibilities
 *
 * - Accept the exact acquired [QueueEntry] unchanged.
 * - Perform entry-specific work (for example: attempt synchronization).
 * - Return one of the four [QueueEntryExecutionOutcome] variants to signal the
 *   desired queue transition.
 *
 * ## What this handler must not do
 *
 * Implementations must not:
 * - Access [io.dataloom.api.queue.QueueProvider] directly.
 * - Own a [kotlinx.coroutines.CoroutineScope] or select a dispatcher.
 * - Retain completed payload batches after returning.
 * - Swallow [kotlin.coroutines.cancellation.CancellationException].
 * - Expose raw [Throwable] instances, stack traces, credentials, tokens,
 *   encryption keys, personal data, or full payloads through the outcome.
 *
 * ## Cancellation
 *
 * A thrown [kotlin.coroutines.cancellation.CancellationException] propagates
 * normally and is never converted into a [QueueEntryExecutionOutcome.Cancelled]
 * result. An explicit [QueueEntryExecutionOutcome.Cancelled] outcome is a
 * distinct, deliberate business-level signal.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public fun interface QueueEntryExecutionHandler {

    /**
     * Executes the supplied [entry] and returns an outcome describing how
     * the [DurableQueueExecutionProcessor] should transition the entry in
     * durable storage.
     *
     * The [entry] is the exact [QueueEntry] returned by the provider. The
     * handler must not mutate it and must not acquire a new queue entry.
     *
     * [kotlin.coroutines.cancellation.CancellationException] must not be
     * caught or suppressed.
     *
     * @param entry the exact acquired [QueueEntry] to execute.
     * @return a [QueueEntryExecutionOutcome] describing the desired queue
     *   transition.
     */
    public suspend fun execute(entry: QueueEntry): QueueEntryExecutionOutcome
}
