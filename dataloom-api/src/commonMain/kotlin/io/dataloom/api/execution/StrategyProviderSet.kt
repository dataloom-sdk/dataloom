package io.dataloom.api.execution

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.transport.TransportProvider

/**
 * Read-only provider set resolved from an evaluated strategy plan.
 *
 * Every role is optional because the plan, rather than a universal binding
 * assumption, determines which capabilities are required. Callers must use
 * the plan's required-capability set before accessing a role.
 */
public interface StrategyProviderSet {
    public val storageProvider: StorageProvider?
    public val transportProvider: TransportProvider?
    public val schedulerProvider: SchedulerProvider?
    public val connectivityProvider: ConnectivityProvider?
    public val queueProvider: QueueProvider?
}
