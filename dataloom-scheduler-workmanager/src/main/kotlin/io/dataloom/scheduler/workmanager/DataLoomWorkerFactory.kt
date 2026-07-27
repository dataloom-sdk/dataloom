package io.dataloom.scheduler.workmanager

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.dataloom.runtime.facade.DataLoomQueueWorker

/**
 * Explicit WorkManager factory for [DataLoomCoroutineWorker].
 *
 * No global DataLoom instance or reflection-based dependency lookup is used.
 * A fresh queue-worker request is produced by [requestFactory] when the worker
 * actually executes.
 */
public class DataLoomWorkerFactory(
    private val queueWorker: DataLoomQueueWorker,
    private val requestFactory: QueueWorkerRunRequestFactory,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName != DataLoomCoroutineWorker::class.java.name) {
            return null
        }
        return DataLoomCoroutineWorker(
            appContext = appContext.applicationContext ?: appContext,
            workerParams = workerParameters,
            queueWorker = queueWorker,
            requestFactory = requestFactory,
        )
    }
}
