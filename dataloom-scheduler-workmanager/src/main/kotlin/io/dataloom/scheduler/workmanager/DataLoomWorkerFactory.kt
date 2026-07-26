package io.dataloom.scheduler.workmanager

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.runtime.facade.DataLoomQueueWorker

/**
 * Explicit [WorkerFactory] that creates [DataLoomCoroutineWorker] instances
 * with injected dependencies.
 *
 * ## Purpose
 *
 * [DataLoomWorkerFactory] enables constructor injection for
 * [DataLoomCoroutineWorker] without relying on a global DataLoom singleton
 * or reflection-based default WorkManager instantiation.
 *
 * ## Integration
 *
 * Applications must disable WorkManager auto-initialization and provide this
 * factory in their WorkManager configuration:
 *
 * ```kotlin
 * val workerFactory = DataLoomWorkerFactory(
 *     queueWorker = dataLoom.queueWorker!!,
 *     consumerId = QueueConsumerId("my-consumer"),
 *     leaseId = QueueLeaseId("my-lease-id"),
 *     acquiredAtMillis = System.currentTimeMillis(),
 *     leaseExpiresAtMillis = System.currentTimeMillis() + 30_000L,
 *     maxEntries = 10,
 *     recoverExpiredLeases = true,
 * )
 *
 * val config = Configuration.Builder()
 *     .setWorkerFactory(workerFactory)
 *     .build()
 *
 * WorkManager.initialize(context, config)
 * ```
 *
 * ## Delegation
 *
 * [DataLoomWorkerFactory] creates a [DataLoomCoroutineWorker] only for worker
 * class names that match [DataLoomCoroutineWorker]. Any other worker class
 * name returns `null`, allowing a chained factory (e.g.
 * [androidx.work.DelegatingWorkerFactory]) to handle other workers.
 *
 * ## Thread safety
 *
 * This factory is thread-safe. All injected fields are immutable.
 *
 * @param queueWorker injected [DataLoomQueueWorker] instance for the worker.
 * @param consumerId stable consumer identifier passed to each worker invocation.
 * @param leaseId unique lease identifier for each acquisition batch.
 * @param acquiredAtMillis epoch-milliseconds at which each worker acquires its batch.
 * @param leaseExpiresAtMillis epoch-milliseconds at which the acquired lease expires.
 * @param maxEntries maximum number of queue entries to acquire per cycle.
 * @param recoverExpiredLeases when `true`, the worker performs expired-lease recovery
 *   before processing.
 */
public class DataLoomWorkerFactory(
    private val queueWorker: DataLoomQueueWorker,
    private val consumerId: QueueConsumerId,
    private val leaseId: QueueLeaseId,
    private val acquiredAtMillis: Long,
    private val leaseExpiresAtMillis: Long,
    private val maxEntries: Int,
    private val recoverExpiredLeases: Boolean,
) : WorkerFactory() {

    /**
     * Creates a [DataLoomCoroutineWorker] when [workerClassName] matches
     * [DataLoomCoroutineWorker], otherwise returns `null`.
     *
     * Returning `null` signals WorkManager (or a parent [androidx.work.DelegatingWorkerFactory])
     * to try the next factory in the chain.
     *
     * @param appContext Android application context.
     * @param workerClassName fully-qualified worker class name requested by WorkManager.
     * @param workerParameters WorkManager worker parameters.
     * @return a new [DataLoomCoroutineWorker] instance, or `null` when the class
     *   name is not [DataLoomCoroutineWorker].
     */
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName != DataLoomCoroutineWorker::class.java.name) {
            return null
        }
        return DataLoomCoroutineWorker(
            appContext = appContext,
            workerParams = workerParameters,
            queueWorker = queueWorker,
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAtMillis = acquiredAtMillis,
            leaseExpiresAtMillis = leaseExpiresAtMillis,
            maxEntries = maxEntries,
            recoverExpiredLeases = recoverExpiredLeases,
        )
    }
}
