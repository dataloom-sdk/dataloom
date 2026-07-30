package io.dataloom.runtime.worker

import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor
import io.dataloom.runtime.retry.RetryTimeoutConfiguration
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.TimeoutEnforcingQueueProvider

/**
 * Structurally assembles the optional queue-provider timeout used by the
 * DataLoom queue-worker runtime.
 *
 * Construction performs no provider operation, clock read, coroutine launch,
 * queue acquisition, or durable transition.
 */
internal fun assembleQueueWorkerQueueProvider(
    queueProvider: QueueProvider,
    clock: DataLoomClock,
    providerTimeout: SchedulingDelay?,
): QueueProvider {
    if (providerTimeout == null) return queueProvider

    return TimeoutEnforcingQueueProvider(
        delegate = queueProvider,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                providerTimeout = providerTimeout,
            ),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )
}
