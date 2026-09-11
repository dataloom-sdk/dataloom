package io.dataloom.scheduler.bgtask.internal

import io.dataloom.runtime.worker.QueueWorkerRunResult

/**
 * Translates a [QueueWorkerRunResult] into the single boolean
 * `BGTask.setTaskCompletedWithSuccess(_:)` accepts.
 *
 * `BGTaskScheduler` has no richer completion vocabulary than this one
 * boolean, so every [QueueWorkerRunResult] variant collapses onto it:
 *
 * - [QueueWorkerRunResult.ProcessingCompleted] -- the queue-processing cycle
 *   itself did not fail (it may have been [io.dataloom.runtime.queue.QueueProcessingResult.NoWork]
 *   or [io.dataloom.runtime.queue.QueueProcessingResult.Processed]; a
 *   follow-up scheduler failure inside [QueueWorkerRunResult.ProcessingCompleted.schedulingResult]
 *   does not change this) -- reported as `true`.
 * - [QueueWorkerRunResult.RecoveryFailed] and
 *   [QueueWorkerRunResult.ProcessingFailed] -- a genuine provider failure --
 *   reported as `false`, so `BGTaskScheduler` may apply its own backoff
 *   before the next opportunity.
 *
 * Pure and platform-independent so it is unit-testable from `commonTest`
 * without touching `BGTask` at all; the only caller,
 * [io.dataloom.scheduler.bgtask.DataLoomBackgroundTaskHandler], lives in
 * `iosMain` because *it* must reference `BGTask` directly.
 */
internal fun QueueWorkerRunResult.succeeded(): Boolean = when (this) {
    is QueueWorkerRunResult.ProcessingCompleted -> true
    is QueueWorkerRunResult.RecoveryFailed -> false
    is QueueWorkerRunResult.ProcessingFailed -> false
}
