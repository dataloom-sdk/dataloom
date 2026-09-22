package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.observation.operational.QueueWorkerSchedulingOperationalEventBridge
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Wraps a [DataLoomQueueWorker] so each run's wake-up scheduling result is
 * bridged into [outbox] under [scope] -- see
 * [DataLoomQueueWorkerSchedulingOperationalEventOutboxSpec]. The run result
 * is returned unchanged; a failure to bridge or append is swallowed and only
 * cancellation propagates.
 */
internal fun DataLoomQueueWorker.bridgingSchedulingEvents(
    outbox: DurableOperationalEventOutbox,
    scope: OperationalEventOutboxScope,
    clock: DataLoomClock,
): DataLoomQueueWorker = object : DataLoomQueueWorker {
    override suspend fun run(request: QueueWorkerRunRequest): QueueWorkerRunResult {
        val result = this@bridgingSchedulingEvents.run(request)
        if (result is QueueWorkerRunResult.ProcessingCompleted) {
            bridgeSchedulingResult(outbox, scope, clock, request, result.schedulingResult)
        }
        return result
    }
}

/** Circuit-aware counterpart of [bridgingSchedulingEvents]. */
internal fun DataLoomCircuitQueueWorker.bridgingSchedulingEvents(
    outbox: DurableOperationalEventOutbox,
    scope: OperationalEventOutboxScope,
    clock: DataLoomClock,
): DataLoomCircuitQueueWorker = object : DataLoomCircuitQueueWorker {
    override suspend fun run(request: QueueWorkerRunRequest): CircuitBreakerQueueWorkerRunResult {
        val result = this@bridgingSchedulingEvents.run(request)
        if (result is CircuitBreakerQueueWorkerRunResult.ProcessingCompleted) {
            bridgeSchedulingResult(outbox, scope, clock, request, result.schedulingResult)
        }
        return result
    }
}

private suspend fun bridgeSchedulingResult(
    outbox: DurableOperationalEventOutbox,
    scope: OperationalEventOutboxScope,
    clock: DataLoomClock,
    request: QueueWorkerRunRequest,
    schedulingResult: QueueWorkerSchedulingResult,
) {
    try {
        val envelope = QueueWorkerSchedulingOperationalEventBridge.toEnvelope(
            leaseId = request.processingRequest.acquireRequest.leaseId,
            result = schedulingResult,
            witnessedAt = clock.now(),
        ) ?: return
        outbox.append(scope, envelope)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (ordinary: Exception) {
        // Intentionally swallowed: bridging is a side-record and must never change the run result.
    }
}
