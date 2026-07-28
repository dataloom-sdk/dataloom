package io.dataloom.core.provider

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.transport.TransportProvider

/** Exact provider instances resolved for one immutable strategy plan. */
public data class ResolvedStrategyProviders(
    override val storageProvider: StorageProvider? = null,
    override val transportProvider: TransportProvider? = null,
    override val schedulerProvider: SchedulerProvider? = null,
    override val connectivityProvider: ConnectivityProvider? = null,
    override val queueProvider: QueueProvider? = null,
) : StrategyProviderSet {
    override fun toString(): String =
        "ResolvedStrategyProviders(" +
            "storage=${storageProvider?.descriptor?.id?.value}, " +
            "transport=${transportProvider?.descriptor?.id?.value}, " +
            "scheduler=${schedulerProvider?.descriptor?.id?.value}, " +
            "connectivity=${connectivityProvider?.descriptor?.id?.value}, " +
            "queue=${queueProvider?.descriptor?.id?.value})"
}
