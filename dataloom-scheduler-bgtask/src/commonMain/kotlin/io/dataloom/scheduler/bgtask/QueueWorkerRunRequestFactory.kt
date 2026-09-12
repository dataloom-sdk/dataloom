package io.dataloom.scheduler.bgtask

import io.dataloom.runtime.worker.QueueWorkerRunRequest

/**
 * Creates one exact [QueueWorkerRunRequest] for each `BGTask` invocation.
 *
 * The host application owns clock reads and identifier generation. Keeping
 * request creation behind this factory prevents [DataLoomBackgroundTaskHandler]
 * from reusing a stale lease identifier or timestamp across multiple task
 * executions.
 *
 * Deliberately declared independently of
 * `io.dataloom.scheduler.workmanager.QueueWorkerRunRequestFactory` rather than
 * shared from `dataloom-runtime`: each scheduler bridge module already owns
 * its own narrow copy of this one-method contract, and this module must not
 * depend on `dataloom-scheduler-workmanager` (an Android-only module).
 */
public fun interface QueueWorkerRunRequestFactory {
    public fun create(): QueueWorkerRunRequest
}
