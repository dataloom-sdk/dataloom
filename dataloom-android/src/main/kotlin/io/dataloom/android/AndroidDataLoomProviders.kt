package io.dataloom.android

import android.content.Context
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.transport.TransportProvider
import io.dataloom.connectivity.android.AndroidConnectivityProvider
import io.dataloom.queue.room.DataLoomDatabaseBuilder
import io.dataloom.queue.room.RoomQueueProvider
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.scheduler.workmanager.WorkManagerSchedulerProvider
import io.dataloom.storage.room.DataLoomStorageDatabaseBuilder
import io.dataloom.storage.room.RoomStorageProvider

/**
 * The four Android-native providers this module wires together:
 * [AndroidConnectivityProvider], [RoomStorageProvider], [RoomQueueProvider],
 * and [WorkManagerSchedulerProvider].
 *
 * Constructing this class performs real I/O — [DataLoomStorageDatabaseBuilder.build]
 * and [DataLoomDatabaseBuilder.build] open (and, on first run, create) real
 * Room databases. Hold the result as an application-process singleton, the
 * same requirement each underlying provider already documents for its own
 * database parameter.
 */
public class AndroidDataLoomProviders internal constructor(
    public val connectivity: AndroidConnectivityProvider,
    public val storage: RoomStorageProvider,
    public val queue: RoomQueueProvider,
    public val scheduler: WorkManagerSchedulerProvider,
)

/**
 * Builds the four core Android providers from application [context].
 *
 * @param storageDatabaseName passed through to [DataLoomStorageDatabaseBuilder.build].
 *   Override only if the host application needs a non-default database file
 *   name (for example, to run multiple isolated DataLoom instances in one
 *   process).
 * @param queueDatabaseName passed through to [DataLoomDatabaseBuilder.build].
 *   Same override rationale as [storageDatabaseName].
 */
public fun androidDataLoomProviders(
    context: Context,
    storageDatabaseName: String = DataLoomStorageDatabaseBuilder.DEFAULT_NAME,
    queueDatabaseName: String = DataLoomDatabaseBuilder.DEFAULT_NAME,
): AndroidDataLoomProviders {
    val applicationContext = context.applicationContext ?: context
    return AndroidDataLoomProviders(
        connectivity = AndroidConnectivityProvider(applicationContext),
        storage = RoomStorageProvider(
            DataLoomStorageDatabaseBuilder.build(applicationContext, storageDatabaseName),
        ),
        queue = RoomQueueProvider(
            DataLoomDatabaseBuilder.build(applicationContext, queueDatabaseName),
        ),
        scheduler = WorkManagerSchedulerProvider(applicationContext),
    )
}

/**
 * Registers [providers] and [transport] on this builder and configures both
 * the direct-synchronization and strategy-evaluation default bindings to
 * resolve them.
 *
 * DataLoom never ships a default transport — endpoint selection,
 * authentication, and payload serialization are always application-owned.
 * Supply `dataloom-transport-ktor`, `dataloom-transport-retrofit`,
 * `dataloom-transport-graphql`, `dataloom-transport-grpc`, or a
 * hand-written [TransportProvider].
 *
 * This call does not configure a queue-worker, queue-submission, or
 * provider-protection capability — those remain explicit, separate
 * [DataLoomBuilder] calls the host application opts into individually, since
 * they require application-specific policy decisions (retry policy, work
 * resolution, circuit scopes) this module cannot make on the host's behalf.
 */
public fun DataLoomBuilder.installAndroidProviders(
    providers: AndroidDataLoomProviders,
    transport: TransportProvider,
): DataLoomBuilder = apply {
    provider(providers.connectivity)
    provider(providers.storage)
    provider(providers.queue)
    provider(providers.scheduler)
    provider(transport)

    val storageId = providers.storage.descriptor.id
    val transportId = transport.descriptor.id
    val schedulerId = providers.scheduler.descriptor.id
    val connectivityId = providers.connectivity.descriptor.id
    val queueId = providers.queue.descriptor.id

    defaultProviderBindings(
        SynchronizationProviderBindings(
            storageProviderId = storageId,
            transportProviderId = transportId,
            schedulerProviderId = schedulerId,
            connectivityProviderId = connectivityId,
            queueProviderId = queueId,
        ),
    )
    defaultStrategyProviderBindings(
        StrategyProviderBindings(
            storageProviderId = storageId,
            transportProviderId = transportId,
            schedulerProviderId = schedulerId,
            connectivityProviderId = connectivityId,
            queueProviderId = queueId,
        ),
    )
}
