package io.dataloom.consumer

import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.queue.QueueEntryExecutionHandler
import io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor
import io.dataloom.runtime.retry.RetryTimeoutConfiguration
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.TimeoutEnforcingQueueProvider
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerCoordinator
import io.dataloom.runtime.worker.QueueWorkerProviderTimeoutRuntime

/** Compile-only coverage for public queue-provider timeout surfaces. */
internal fun compileQueueProviderTimeoutConsumer(
    queueProvider: QueueProvider,
    executionHandler: QueueEntryExecutionHandler,
    schedulerProvider: SchedulerProvider?,
    clock: DataLoomClock,
    configuration: QueueWorkerConfiguration,
): Pair<TimeoutEnforcingQueueProvider, QueueWorkerCoordinator> {
    val timeout = SchedulingDelay(5_000L)
    val protectedProvider = TimeoutEnforcingQueueProvider(
        delegate = queueProvider,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(providerTimeout = timeout),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )
    val coordinator = QueueWorkerProviderTimeoutRuntime.create(
        queueProvider = queueProvider,
        executionHandler = executionHandler,
        schedulerProvider = schedulerProvider,
        clock = clock,
        configuration = configuration,
        queueProviderTimeout = timeout,
    )
    return protectedProvider to coordinator
}
