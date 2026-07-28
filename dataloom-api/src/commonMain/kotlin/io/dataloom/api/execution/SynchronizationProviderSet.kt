package io.dataloom.api.execution

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.transport.TransportProvider

/**
 * Stable, read-only view of the provider instances selected for one
 * synchronization execution.
 *
 * Runtime implementation modules may use their own resolver and container,
 * while public pipelines depend only on this API-owned contract.
 */
public interface SynchronizationProviderSet {

    /** Required provider for application-owned synchronized storage. */
    public val storageProvider: StorageProvider

    /** Required provider for remote transport. */
    public val transportProvider: TransportProvider

    /** Optional provider for background scheduling. */
    public val schedulerProvider: SchedulerProvider?

    /** Optional provider for connectivity preflight checks. */
    public val connectivityProvider: ConnectivityProvider?

    /** Optional provider for durable runtime queue persistence. */
    public val queueProvider: QueueProvider?
}
