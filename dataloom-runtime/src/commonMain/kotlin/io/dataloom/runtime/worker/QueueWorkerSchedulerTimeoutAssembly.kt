package io.dataloom.runtime.worker

import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor
import io.dataloom.runtime.retry.RetryTimeoutConfiguration
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.TimeoutEnforcingSchedulerProvider

/**
 * Applies the queue-worker scheduler timeout without performing any provider
 * operation, clock read, coroutine launch, or scheduling work.
 */
internal fun assembleQueueWorkerSchedulerProvider(
    provider: SchedulerProvider?,
    timeout: SchedulingDelay?,
    clock: DataLoomClock,
): SchedulerProvider? {
    if (provider == null || timeout == null) return provider

    return TimeoutEnforcingSchedulerProvider(
        delegate = provider,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                providerTimeout = timeout,
            ),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )
}
