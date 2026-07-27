package io.dataloom.scheduler.workmanager

import io.dataloom.runtime.worker.QueueWorkerRunRequest

/**
 * Creates one exact [QueueWorkerRunRequest] for each WorkManager invocation.
 *
 * The host application owns clock reads and identifier generation. Keeping
 * request creation behind this factory prevents a WorkerFactory from reusing a
 * stale lease identifier or timestamp across multiple worker executions.
 */
public fun interface QueueWorkerRunRequestFactory {
    public fun create(): QueueWorkerRunRequest
}
