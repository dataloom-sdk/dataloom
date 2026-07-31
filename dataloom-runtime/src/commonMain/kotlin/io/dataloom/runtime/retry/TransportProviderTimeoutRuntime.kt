package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.TransportProvider

/** Production assembly for a transport provider-level cooperative timeout. */
public object TransportProviderTimeoutRuntime {

    /**
     * Wraps [transportProvider] with one independent provider timeout.
     * Construction performs no provider call, clock read, or coroutine launch.
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
