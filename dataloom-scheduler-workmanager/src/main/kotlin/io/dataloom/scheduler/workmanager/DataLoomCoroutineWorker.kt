package io.dataloom.scheduler.workmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.dataloom.runtime.facade.DataLoomQueueWorker
import io.dataloom.runtime.worker.QueueWorkerRunResult

/**
 * WorkManager bridge that executes exactly one bounded DataLoom queue-worker
 * cycle per invocation.
 *
 * The exact run request is created at execution time by
 * [QueueWorkerRunRequestFactory]. This prevents stale lease identifiers and
 * timestamps from being captured when WorkManager is configured.
 */
public class DataLoomCoroutineWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val queueWorker: DataLoomQueueWorker,
    private val requestFactory: QueueWorkerRunRequestFactory,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val runRequest = requestFactory.create()
        return when (queueWorker.run(runRequest)) {
            is QueueWorkerRunResult.ProcessingCompleted -> Result.success()
            is QueueWorkerRunResult.RecoveryFailed -> Result.failure()
            is QueueWorkerRunResult.ProcessingFailed -> Result.failure()
        }
    }
}
