package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.TransportProvider

/** Production assembly for a storage provider protected by one provider timeout. */
public object StorageProviderTimeoutRuntime {

    /**
     * Wraps [storageProvider] with cooperative provider-timeout enforcement.
     *
     * Construction performs no provider call, clock read, timeout execution, or
     * coroutine launch.
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

/** Production assembly for a transport provider protected by one provider timeout. */
public object TransportProviderTimeoutRuntime {

    /**
     * Wraps [transportProvider] with cooperative provider-timeout enforcement.
     *
     * Construction performs no provider call, clock read, timeout execution, or
     * coroutine launch.
     */
    public fun create(
        transportProvider: TransportProvider,
        clock: DataLoomClock,
        providerTimeout: SchedulingDelay,
    ): TransportProvider = TimeoutEnforcingTransportProvider(
        delegate = transportProvider,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                providerTimeout = providerTimeout,
            ),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )
}
