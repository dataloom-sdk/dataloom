package io.dataloom.runtime.submission

/**
 * Narrow public capability for submitting synchronization work to the durable
 * queue.
 *
 * ## Purpose
 *
 * [DataLoomQueueSubmission] provides a single focused entry point for
 * persisting a [QueuedSynchronizationSubmission] in the durable queue. It
 * delegates encoding to the application-supplied
 * [QueuedSynchronizationWorkEncoder] and persistence to the configured
 * [io.dataloom.api.queue.QueueProvider].
 *
 * ## Availability
 *
 * [io.dataloom.runtime.facade.DataLoom.queueSubmission] is `null` when
 * [io.dataloom.runtime.facade.DataLoomBuilder.queueSubmissionEncoder] was not
 * supplied or a valid [io.dataloom.api.queue.QueueProvider] binding was
 * not present. Non-null when all required queue dependencies are present.
 *
 * ## Submission flow
 *
 * Each [submit] call:
 * 1. Passes the exact [QueuedSynchronizationSubmission] to the encoder exactly
 *    once.
 * 2. On encoding rejection, returns [QueueSubmissionResult.EncodingRejected]
 *    without calling the [io.dataloom.api.queue.QueueProvider].
 * 3. Validates structural correspondence between the encoded request and the
 *    submission.
 * 4. On validation failure, returns [QueueSubmissionResult.ContractViolation]
 *    without calling the [io.dataloom.api.queue.QueueProvider].
 * 5. Calls [io.dataloom.api.queue.QueueProvider.enqueue] exactly once.
 * 6. Returns [QueueSubmissionResult.Enqueued] on provider success or
 *    [QueueSubmissionResult.QueueProviderFailure] on provider failure.
 *
 * ## No automatic behavior
 *
 * [submit] does not:
 * - Start [io.dataloom.runtime.worker.QueueWorkerCoordinator].
 * - Invoke [io.dataloom.api.scheduling.SchedulerProvider].
 * - Execute [io.dataloom.runtime.execution.SynchronizationExecutionCoordinator].
 * - Initialize providers.
 * - Invoke [io.dataloom.api.retry.RetryPolicy].
 * - Check connectivity.
 * - Call [io.dataloom.api.queue.QueueProvider.acquire].
 * - Process queue entries.
 * - Recover leases.
 * - Retry [io.dataloom.api.queue.QueueProvider.enqueue] automatically.
 *
 * ## Idempotency boundary
 *
 * [submit] invokes [io.dataloom.api.queue.QueueProvider.enqueue] at most
 * once per call. Duplicate-entry handling belongs to the provider
 * implementation. An unknown external failure may require application-level
 * reconciliation. Use a stable [io.dataloom.api.identifier.QueueEntryId]
 * for retry attempts on the same logical submission.
 *
 * ## Ownership
 *
 * [DataLoomQueueSubmission] owns no
 * [kotlinx.coroutines.CoroutineScope] and selects no dispatcher. The caller
 * owns the coroutine context.
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.CancellationException] from
 * [io.dataloom.api.queue.QueueProvider.enqueue] propagates normally. It is
 * never converted into a [QueueSubmissionResult].
 *
 * ## Provider lifecycle
 *
 * The application must call
 * [io.dataloom.runtime.facade.DataLoom.initialize] before submitting queue
 * work. [DataLoomQueueSubmission] does not initialize providers automatically.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public interface DataLoomQueueSubmission {

    /**
     * Submits the supplied [submission] to the durable queue.
     *
     * Invokes the [QueuedSynchronizationWorkEncoder] exactly once, validates
     * the encoded request, and calls
     * [io.dataloom.api.queue.QueueProvider.enqueue] at most once.
     *
     * [kotlinx.coroutines.CancellationException] propagates normally.
     * Unexpected exceptions from the encoder or provider propagate normally.
     *
     * @param submission the exact [QueuedSynchronizationSubmission] to submit.
     * @return a [QueueSubmissionResult] describing the outcome.
     */
    public suspend fun submit(
        submission: QueuedSynchronizationSubmission,
    ): QueueSubmissionResult
}
