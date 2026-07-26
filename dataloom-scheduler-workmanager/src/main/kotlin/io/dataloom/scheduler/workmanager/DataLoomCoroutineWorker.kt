package io.dataloom.scheduler.workmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.facade.DataLoomQueueWorker
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult

/**
 * AndroidX [CoroutineWorker] bridge that drives one [DataLoomQueueWorker]
 * coordination cycle per WorkManager invocation.
 *
 * ## Purpose
 *
 * [DataLoomCoroutineWorker] adapts WorkManager's execution lifecycle to the
 * DataLoom [DataLoomQueueWorker.run] contract. Each Worker invocation performs
 * exactly one bounded queue-worker cycle: optional expired-lease recovery,
 * one bounded queue processing call, and one scheduler wake-up decision.
 *
 * ## Injection
 *
 * [DataLoomCoroutineWorker] is not instantiated directly by WorkManager's
 * default reflection-based factory. Applications must provide a
 * [DataLoomWorkerFactory] in their WorkManager configuration. This avoids
 * any global DataLoom singleton.
 *
 * ## Result mapping
 *
 * | [QueueWorkerRunResult]                        | WorkManager [Result] |
 * |-----------------------------------------------|----------------------|
 * | [QueueWorkerRunResult.ProcessingCompleted]    | [Result.success]     |
 * | [QueueWorkerRunResult.RecoveryFailed]         | [Result.failure]     |
 * | [QueueWorkerRunResult.ProcessingFailed]       | [Result.failure]     |
 *
 * WorkManager retry is NOT used. All retry decisions are owned by the
 * DataLoom durable queue state machine. Using WorkManager retry would create
 * a duplicate retry mechanism.
 *
 * ## Cancellation
 *
 * [CancellationException] from [DataLoomQueueWorker.run] propagates normally
 * through WorkManager's coroutine cancellation support.
 *
 * ## What this worker must not do
 *
 * - Initialize or shut down DataLoom automatically.
 * - Process more than one queue cycle per invocation.
 * - Implement retry policy.
 *
 * @param appContext Android application context from WorkManager.
 * @param workerParams WorkManager worker parameters.
 * @param queueWorker injected [DataLoomQueueWorker] instance.
 * @param consumerId stable consumer identifier for this worker.
 * @param leaseId unique lease identifier for this invocation's acquisition batch.
 * @param acquiredAtMillis epoch-milliseconds at which this worker acquired its batch.
 * @param leaseExpiresAtMillis epoch-milliseconds at which the acquired lease expires.
 * @param maxEntries maximum number of queue entries to acquire in this cycle.
 * @param recoverExpiredLeases when `true`, an expired-lease recovery request is
 *   constructed and included in the [QueueWorkerRunRequest].
 */
public class DataLoomCoroutineWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val queueWorker: DataLoomQueueWorker,
    private val consumerId: QueueConsumerId,
    private val leaseId: QueueLeaseId,
    private val acquiredAtMillis: Long,
    private val leaseExpiresAtMillis: Long,
    private val maxEntries: Int,
    private val recoverExpiredLeases: Boolean,
) : CoroutineWorker(appContext, workerParams) {

    /**
     * Executes one bounded DataLoom queue-worker coordination cycle.
     *
     * Constructs a [QueueWorkerRunRequest] from the injected parameters and
     * delegates to [DataLoomQueueWorker.run]. Maps the result to a WorkManager
     * [Result]. Does not use WorkManager retry.
     *
     * @return [Result.success] on [QueueWorkerRunResult.ProcessingCompleted],
     *   or [Result.failure] on [QueueWorkerRunResult.RecoveryFailed] or
     *   [QueueWorkerRunResult.ProcessingFailed].
     */
    override suspend fun doWork(): Result {
        val now = DataLoomInstant(acquiredAtMillis)
        val leaseExpiry = DataLoomInstant(leaseExpiresAtMillis)

        val acquireRequest = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = now,
            leaseExpiresAt = leaseExpiry,
            maxEntries = maxEntries,
        )

        val recoveryRequest = if (recoverExpiredLeases) {
            ExpiredLeaseRecoveryRequest(currentTime = now)
        } else {
            null
        }

        val runRequest = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireRequest = acquireRequest),
            recoveryRequest = recoveryRequest,
        )

        return when (queueWorker.run(runRequest)) {
            is QueueWorkerRunResult.ProcessingCompleted -> Result.success()
            is QueueWorkerRunResult.RecoveryFailed -> Result.failure()
            is QueueWorkerRunResult.ProcessingFailed -> Result.failure()
        }
    }
}
