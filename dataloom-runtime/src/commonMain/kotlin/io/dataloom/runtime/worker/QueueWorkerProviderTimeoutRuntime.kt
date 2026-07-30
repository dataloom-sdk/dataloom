package io.dataloom.runtime.worker

import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.queue.DurableQueueExecutionProcessor
import io.dataloom.runtime.queue.QueueEntryExecutionHandler

/**
 * Additive production assembly for a queue worker whose queue-provider
 * operations are bounded by one explicit provider timeout.
 *
 * The same timeout-enforcing [QueueProvider] instance is used for expired-lease
 * recovery, atomic acquisition, and every lease-guarded durable transition. This
 * prevents one part of the worker from bypassing the configured boundary.
 *
 * Existing [QueueWorkerCoordinator] and [DurableQueueExecutionProcessor]
 * constructors remain unchanged and preserve their historical direct provider
 * path. Construction performs no provider call, clock read, coroutine launch,
 * queue acquisition, scheduling, or durable transition.
 */
public object QueueWorkerProviderTimeoutRuntime {

    /**
     * Creates one fully assembled queue-worker coordinator.
     *
     * A provider timeout is reported through existing queue result variants:
     *
     * - recovery timeout -> [QueueWorkerRunResult.RecoveryFailed];
     * - acquisition timeout -> [QueueWorkerRunResult.ProcessingFailed] carrying
     *   [io.dataloom.runtime.queue.QueueProcessingFailureStage.ACQUISITION];
     * - transition timeout -> [QueueWorkerRunResult.ProcessingFailed] carrying
     *   the exact transition stage and truthful confirmed counters.
     *
     * Timed-out mutations have unknown durable completion. This runtime does not
     * replay them automatically. Lease expiry, state lookup, and ordinary queue
     * recovery remain the reconciliation boundaries.
     */
    public fun create(
        queueProvider: QueueProvider,
        executionHandler: QueueEntryExecutionHandler,
        schedulerProvider: SchedulerProvider?,
        clock: DataLoomClock,
        configuration: QueueWorkerConfiguration,
        queueProviderTimeout: SchedulingDelay,
    ): QueueWorkerCoordinator {
        val protectedQueueProvider = assembleQueueWorkerQueueProvider(
            queueProvider = queueProvider,
            clock = clock,
            providerTimeout = queueProviderTimeout,
        )
        val processor = DurableQueueExecutionProcessor(
            queueProvider = protectedQueueProvider,
            executionHandler = executionHandler,
        )
        return QueueWorkerCoordinator(
            queueProvider = protectedQueueProvider,
            queueProcessor = processor,
            schedulerProvider = schedulerProvider,
            clock = clock,
            configuration = configuration,
        )
    }
}
