package io.dataloom.consumer

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.TransportIdleTimeoutBoundary
import io.dataloom.runtime.retry.TransportIdleTimeoutRuntime

/** External-consumer probe for the explicit transport idle-progress boundary. */
public fun createTransportIdleTimeoutBoundary(
    clock: DataLoomClock,
    idleTimeout: SchedulingDelay,
    workflowTimeout: SchedulingDelay? = null,
): TransportIdleTimeoutBoundary = TransportIdleTimeoutRuntime.create(
    clock = clock,
    idleTimeout = idleTimeout,
    workflowTimeout = workflowTimeout,
)
