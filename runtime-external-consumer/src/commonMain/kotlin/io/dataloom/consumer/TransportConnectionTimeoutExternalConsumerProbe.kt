package io.dataloom.consumer

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.TransportConnectionTimeoutBoundary
import io.dataloom.runtime.retry.TransportConnectionTimeoutRuntime

/** External-consumer probe for the explicit transport connection boundary. */
public fun createTransportConnectionTimeoutBoundary(
    clock: DataLoomClock,
    connectionTimeout: SchedulingDelay,
    workflowTimeout: SchedulingDelay? = null,
): TransportConnectionTimeoutBoundary = TransportConnectionTimeoutRuntime.create(
    clock = clock,
    connectionTimeout = connectionTimeout,
    workflowTimeout = workflowTimeout,
)
