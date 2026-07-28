package io.dataloom.runtime.facade

import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult

/**
 * Narrow queue-worker capability exposed by [DataLoom].
 *
 * ## Purpose
 *
 * [DataLoomQueueWorker] provides a single, focused entry point for executing
 * one queue-worker cycle. It delegates to the existing
 * [io.dataloom.runtime.worker.QueueWorkerCoordinator] without duplicating any
 * processing logic.
 *
 * ## Availability
 *
 * [DataLoom.queueWorker] is `null` when
 * [DataLoomBuilder.queueWorkerConfiguration] was not supplied or was invalid.
 * Non-null when all required queue dependencies are present and valid.
 *
 * ## No automatic start
 *
 * No background worker is started automatically. Callers must invoke [run]
 * explicitly from an appropriate execution context (for example, a platform
 * WorkManager task).
 *
 * ## Queue submission boundary
 *
 * [DataLoomQueueWorker] does not expose a queue-submission API. Applications
 * must use their [io.dataloom.api.queue.QueueProvider] directly until a
 * public enqueue API is introduced in a future issue.
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.CancellationException] from [run] propagates normally.
 * Cancellation is never converted into a [QueueWorkerRunResult].
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom runtime types only. Safe for use
 * in Kotlin Multiplatform common code.
 */
public interface DataLoomQueueWorker {

    /**
     * Executes one complete queue-worker coordination cycle.
     *
     * Delegates to [io.dataloom.runtime.worker.QueueWorkerCoordinator.run]
     * with the exact [request]. The result is returned unchanged.
     *
     * The cycle performs at most one optional expired-lease recovery, one
     * bounded queue-processing call, and one scheduler wake-up call.
     *
     * [kotlinx.coroutines.CancellationException] propagates normally.
     *
     * @param request the immutable run request carrying the processing request
     *   and optional recovery request.
     * @return a [QueueWorkerRunResult] describing the terminal outcome of this
     *   cycle.
     * @throws IllegalArgumentException when the request is invalid according to
     *   [io.dataloom.runtime.worker.QueueWorkerCoordinator] invariants.
     */
    public suspend fun run(
        request: QueueWorkerRunRequest,
    ): QueueWorkerRunResult
}
