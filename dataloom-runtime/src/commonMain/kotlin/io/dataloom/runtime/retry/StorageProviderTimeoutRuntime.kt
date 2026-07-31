package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.time.DataLoomClock

/** Production assembly for a storage provider-level cooperative timeout. */
public object StorageProviderTimeoutRuntime {

    /**
     * Wraps [storageProvider] with one independent provider timeout.
     * Construction performs no provider call, clock read, or coroutine launch.
     */
    public fun create(
        storageProvider: StorageProvider,
        clock: DataLoomClock,
        providerTimeout: SchedulingDelay,
    ): StorageProvider = TimeoutEnforcingStorageProvider(
        delegate = storageProvider,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                providerTimeout = providerTimeout,
            ),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )
}
