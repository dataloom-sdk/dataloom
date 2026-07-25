package io.dataloom.runtime.facade

import io.dataloom.runtime.worker.QueueWorkerCoordinator
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult

/**
 * Internal [DataLoomQueueWorker] implementation assembled by [DataLoomBuilder].
 *
 * ## Delegation contract
 *
 * [run] delegates to [QueueWorkerCoordinator.run] and returns the result
 * unchanged. No queue-worker logic is duplicated in this class.
 *
 * ## No automatic start
 *
 * The coordinator is not started automatically. Callers must invoke [run]
 * explicitly.
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.CancellationException] from [coordinator] propagates
 * normally.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom runtime types only. Safe for use
 * in Kotlin Multiplatform common code.
 *
 * @param coordinator the coordinator that executes one queue-worker cycle.
 */
internal class DefaultDataLoomQueueWorker(
    private val coordinator: QueueWorkerCoordinator,
) : DataLoomQueueWorker {

    override suspend fun run(
        request: QueueWorkerRunRequest,
    ): QueueWorkerRunResult = coordinator.run(request)
}
