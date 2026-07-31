package io.dataloom.runtime.facade

import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerCoordinator
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerRunRequest

/** Internal immutable facade over [CircuitBreakerQueueWorkerCoordinator]. */
internal class DefaultDataLoomCircuitQueueWorker(
    private val coordinator: CircuitBreakerQueueWorkerCoordinator,
) : DataLoomCircuitQueueWorker {

    override suspend fun run(
        request: QueueWorkerRunRequest,
    ): CircuitBreakerQueueWorkerRunResult = coordinator.run(request)
}
