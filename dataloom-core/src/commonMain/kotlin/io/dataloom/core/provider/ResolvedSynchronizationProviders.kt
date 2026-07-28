package io.dataloom.core.provider

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.execution.SynchronizationProviderSet
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.transport.TransportProvider

/**
 * Immutable container of fully resolved provider instances for all configured
 * synchronization runtime roles.
 *
 * ## Purpose
 *
 * [ResolvedSynchronizationProviders] is produced by [SynchronizationProviderResolver]
 * when all configured [SynchronizationProviderBindings] pass validation. It
 * holds the exact provider instances registered under each configured
 * [ProviderId].
 *
 * ## Required providers
 *
 * [storageProvider] and [transportProvider] are always present.
 *
 * ## Optional providers
 *
 * [schedulerProvider], [connectivityProvider], and [queueProvider] are `null`
 * when the corresponding role was not configured in
 * [SynchronizationProviderBindings].
 *
 * ## Exact instances
 *
 * Every property contains the exact provider object registered in
 * [ProviderRegistry] under the configured [ProviderId]. No new instances are
 * created.
 *
 * ## Construction restrictions
 *
 * Construction invokes no provider lifecycle method, no provider operation,
 * and performs no synchronization work.
 *
 * ## Lifecycle boundary
 *
 * [ResolvedSynchronizationProviders] does not guarantee that providers have
 * been initialized. The future synchronization runtime is responsible for
 * ensuring that [io.dataloom.core.provider.ProviderLifecycleCoordinator] has
 * initialized all required providers before using the resolved instances.
 *
 * ## Security restrictions
 *
 * The diagnostic [toString] representation exposes only provider IDs and
 * types. It does not invoke any provider implementation's `toString()` and
 * does not expose provider internal state, credentials, authorization
 * information, payloads, checkpoint tokens, encryption keys, or personal data.
 *
 * ## Mutable collection restriction
 *
 * This class exposes no mutable collection.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param storageProvider the required resolved [StorageProvider] instance.
 * @param transportProvider the required resolved [TransportProvider] instance.
 * @param schedulerProvider the resolved [SchedulerProvider] instance, or `null`
 *   when not configured.
 * @param connectivityProvider the resolved [ConnectivityProvider] instance, or
 *   `null` when not configured.
 * @param queueProvider the resolved [QueueProvider] instance, or `null` when
 *   not configured.
 */
public class ResolvedSynchronizationProviders(
    /** The resolved [StorageProvider] for the storage runtime role. */
    override val storageProvider: StorageProvider,

    /** The resolved [TransportProvider] for the transport runtime role. */
    override val transportProvider: TransportProvider,

    /** The resolved [SchedulerProvider] for the scheduler runtime role, or `null`. */
    override val schedulerProvider: SchedulerProvider?,

    /** The resolved [ConnectivityProvider] for the connectivity runtime role, or `null`. */
    override val connectivityProvider: ConnectivityProvider?,

    /** The resolved [QueueProvider] for the queue runtime role, or `null`. */
    override val queueProvider: QueueProvider?,
) : SynchronizationProviderSet {
    /**
     * Returns a safe diagnostic representation containing provider IDs and
     * whether optional roles are present.
     *
     * This implementation does not invoke any provider's `toString()` method
     * and does not expose provider internal state, credentials, or payloads.
     */
    override fun toString(): String {
        val storageId = storageProvider.descriptor.id.value
        val storageType = storageProvider.descriptor.type
        val transportId = transportProvider.descriptor.id.value
        val transportType = transportProvider.descriptor.type
        val schedulerId = schedulerProvider?.descriptor?.id?.value
        val connectivityId = connectivityProvider?.descriptor?.id?.value
        val queueId = queueProvider?.descriptor?.id?.value
        return "ResolvedSynchronizationProviders(" +
            "storage=[$storageType:$storageId], " +
            "transport=[$transportType:$transportId], " +
            "scheduler=${if (schedulerId != null) "[${ProviderType.SCHEDULER}:$schedulerId]" else "null"}, " +
            "connectivity=${if (connectivityId != null) "[${ProviderType.CONNECTIVITY}:$connectivityId]" else "null"}, " +
            "queue=${if (queueId != null) "[${ProviderType.QUEUE}:$queueId]" else "null"}" +
            ")"
    }
}
