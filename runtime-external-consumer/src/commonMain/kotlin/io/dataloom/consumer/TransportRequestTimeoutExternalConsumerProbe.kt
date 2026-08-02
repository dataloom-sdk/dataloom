package io.dataloom.consumer

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.retry.TransportRequestTimeoutRuntime

/** External-consumer probe for the independent transport request timeout. */
public fun createRequestTimedTransport(
    provider: TransportProvider,
    clock: DataLoomClock,
    timeout: SchedulingDelay,
): TransportProvider = TransportRequestTimeoutRuntime.create(
    transportProvider = provider,
    clock = clock,
    requestTimeout = timeout,
)
