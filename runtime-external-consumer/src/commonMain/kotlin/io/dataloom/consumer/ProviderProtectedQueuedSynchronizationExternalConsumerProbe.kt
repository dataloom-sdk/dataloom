package io.dataloom.consumer

import io.dataloom.api.queue.QueueEntry
import io.dataloom.runtime.queue.ProviderProtectedQueuedSynchronizationResult
import io.dataloom.runtime.queue.ProviderProtectedQueuedSynchronizationRuntime

/** External-consumer probe for protected queued synchronization invocation. */
public suspend fun runProviderProtectedQueuedSynchronizationProbe(
    runtime: ProviderProtectedQueuedSynchronizationRuntime,
    entry: QueueEntry,
): ProviderProtectedQueuedSynchronizationResult = runtime.execute(entry)
