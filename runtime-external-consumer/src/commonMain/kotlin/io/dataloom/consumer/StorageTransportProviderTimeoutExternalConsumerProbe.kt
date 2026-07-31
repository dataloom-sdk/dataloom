package io.dataloom.consumer

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.retry.StorageProviderTimeoutRuntime
import io.dataloom.runtime.retry.TransportProviderTimeoutRuntime

/** External-consumer compilation probe for storage and transport timeout assembly. */
public object StorageTransportProviderTimeoutExternalConsumerProbe {

    public fun protectStorage(
        provider: StorageProvider,
        clock: DataLoomClock,
        timeout: SchedulingDelay,
    ): StorageProvider = StorageProviderTimeoutRuntime.create(
        storageProvider = provider,
        clock = clock,
        providerTimeout = timeout,
    )

    public fun protectTransport(
        provider: TransportProvider,
        clock: DataLoomClock,
        timeout: SchedulingDelay,
    ): TransportProvider = TransportProviderTimeoutRuntime.create(
        transportProvider = provider,
        clock = clock,
        providerTimeout = timeout,
    )
}
