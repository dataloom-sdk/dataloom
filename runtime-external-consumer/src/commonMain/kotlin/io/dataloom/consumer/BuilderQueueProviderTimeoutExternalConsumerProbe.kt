package io.dataloom.consumer

import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.runtime.facade.DataLoomQueueWorkerSpec
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.worker.QueueWorkerConfiguration

/** External-consumer probe for additive builder queue-provider timeout assembly. */
public fun queueWorkerSpecWithProviderTimeout(
    workResolver: QueuedSynchronizationWorkResolver,
    retryPolicy: RetryPolicy,
    retryOperation: RetryOperation,
    configuration: QueueWorkerConfiguration,
): DataLoomQueueWorkerSpec = DataLoomQueueWorkerSpec(
    workResolver = workResolver,
    retryPolicy = retryPolicy,
    retryOperation = retryOperation,
    configuration = configuration,
    queueProviderTimeout = SchedulingDelay(5_000L),
)

/** Proves the original four-argument constructor remains available. */
public fun legacyQueueWorkerSpecWithoutProviderTimeout(
    workResolver: QueuedSynchronizationWorkResolver,
    retryPolicy: RetryPolicy,
    retryOperation: RetryOperation,
    configuration: QueueWorkerConfiguration,
): DataLoomQueueWorkerSpec = DataLoomQueueWorkerSpec(
    workResolver = workResolver,
    retryPolicy = retryPolicy,
    retryOperation = retryOperation,
    configuration = configuration,
)
