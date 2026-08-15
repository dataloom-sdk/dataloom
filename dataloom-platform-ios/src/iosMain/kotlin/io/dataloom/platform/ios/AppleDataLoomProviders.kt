package io.dataloom.platform.ios

import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.transport.TransportProvider
import io.dataloom.platform.ios.connectivity.AppleConnectivityProvider
import io.dataloom.platform.ios.scheduling.AppleSchedulerProvider
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.queue.AppleFileQueueProvider
import io.dataloom.storage.sqldelight.SqlDelightStorageProvider
import io.dataloom.storage.sqldelight.createIosSqlDelightStorageDatabase

/**
 * The four iOS-native providers this module wires together:
 * [AppleConnectivityProvider], [SqlDelightStorageProvider],
 * [AppleFileQueueProvider], and [AppleSchedulerProvider].
 *
 * Constructing this class performs real I/O -- [createIosSqlDelightStorageDatabase]
 * opens (and, on first run, creates) a real SQLite database via SQLDelight's
 * native driver, and [AppleFileQueueProvider] resolves its lock/data file
 * paths under the supplied directory. Hold the result as an
 * application-process singleton, the same requirement each underlying
 * provider already documents for its own database or directory parameter.
 */
public class AppleDataLoomProviders internal constructor(
    public val connectivity: AppleConnectivityProvider,
    public val storage: SqlDelightStorageProvider,
    public val queue: AppleFileQueueProvider,
    public val scheduler: AppleSchedulerProvider,
)

/**
 * Builds the four core iOS providers.
 *
 * @param preRegisteredIdentifiers the exact set of `BGTaskScheduler` task
 *   identifiers the host application has already listed in its `Info.plist`
 *   `BGTaskSchedulerPermittedIdentifiers` array and registered via
 *   `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`
 *   at app-launch time. Passed straight through to
 *   [AppleSchedulerProvider]'s own constructor parameter of the same name --
 *   see `docs/apple/scheduler-provider.md` for the full explanation. DataLoom
 *   does not invent a default set of identifiers; the host application must
 *   supply its own.
 * @param directoryPath an absolute, application-private directory (normally
 *   a dedicated child of Application Support) the host application has
 *   already resolved. Passed straight through to [AppleFileQueueProvider]'s
 *   own `directoryPath` constructor parameter. DataLoom does not resolve
 *   platform paths itself, matching that provider's existing precedent.
 * @param storageDatabaseName passed through to
 *   [createIosSqlDelightStorageDatabase]. Override only if the host
 *   application needs a non-default database file name (for example, to run
 *   multiple isolated DataLoom instances in one process).
 * @param queueFileName passed through to [AppleFileQueueProvider]. Same
 *   override rationale as [storageDatabaseName].
 */
public fun appleDataLoomProviders(
    preRegisteredIdentifiers: Set<String>,
    directoryPath: String,
    storageDatabaseName: String = "dataloom-storage.db",
    queueFileName: String = AppleFileQueueProvider.DEFAULT_FILE_NAME,
): AppleDataLoomProviders = AppleDataLoomProviders(
    connectivity = AppleConnectivityProvider(),
    storage = SqlDelightStorageProvider(
        createIosSqlDelightStorageDatabase(databaseName = storageDatabaseName),
    ),
    queue = AppleFileQueueProvider(
        directoryPath = directoryPath,
        fileName = queueFileName,
    ),
    scheduler = AppleSchedulerProvider(preRegisteredIdentifiers = preRegisteredIdentifiers),
)

/**
 * Registers [providers] and [transport] on this builder and configures both
 * the direct-synchronization and strategy-evaluation default bindings to
 * resolve them.
 *
 * DataLoom never ships a default transport -- endpoint selection,
 * authentication, and payload serialization are always application-owned.
 * Supply `dataloom-transport-ktor`, `dataloom-transport-retrofit`,
 * `dataloom-transport-graphql`, `dataloom-transport-grpc`, or a
 * hand-written [TransportProvider].
 *
 * This call does not configure a queue-worker, queue-submission, or
 * provider-protection capability -- those remain explicit, separate
 * [DataLoomBuilder] calls the host application opts into individually, since
 * they require application-specific policy decisions (retry policy, work
 * resolution, circuit scopes) this module cannot make on the host's behalf.
 */
public fun DataLoomBuilder.installAppleProviders(
    providers: AppleDataLoomProviders,
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
