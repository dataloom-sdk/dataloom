package io.dataloom.runtime.facade

import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerRunRequest

/**
 * Narrow circuit-aware queue-worker capability exposed by [DataLoom].
 *
 * The capability executes one bounded worker cycle while preserving expired-
 * lease recovery, queue processing, circuit recording, and follow-up scheduling
 * as separate evidence boundaries.
 *
 * No background worker is started automatically. Callers invoke [run] from a
 * platform-owned execution context. Caller cancellation propagates unchanged.
 */
public interface DataLoomCircuitQueueWorker {

    /** Executes one circuit-aware queue-worker cycle with the exact [request]. */
    public suspend fun run(
        request: QueueWorkerRunRequest,
    ): CircuitBreakerQueueWorkerRunResult
}
