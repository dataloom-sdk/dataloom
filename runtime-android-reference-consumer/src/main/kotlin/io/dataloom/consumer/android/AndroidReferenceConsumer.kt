package io.dataloom.consumer.android

import android.content.Context
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.SystemDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.connectivity.android.AndroidConnectivityProvider
import io.dataloom.queue.room.DataLoomDatabaseBuilder
import io.dataloom.queue.room.RoomQueueProvider
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.scheduler.workmanager.WorkManagerSchedulerProvider
import io.dataloom.storage.room.DataLoomStorageDatabaseBuilder
import io.dataloom.storage.room.RoomStorageProvider
import java.util.UUID

/**
 * Native Android reference wiring for `#101` (DL-039A) — proves that
 * [AndroidConnectivityProvider], [RoomStorageProvider], [RoomQueueProvider],
 * and [WorkManagerSchedulerProvider] actually compose into one buildable
 * [DataLoom] instance through [DataLoomBuilder]'s public API.
 *
 * ## Scope
 *
 * This is a **compile-only fixture**, the same documented boundary
 * `runtime-external-consumer` already established for the JVM-only public
 * runtime surface. [buildReferenceDataLoom] is real, correct wiring code —
 * it is not a fake or a stub — but nothing in this module calls
 * [DataLoom.initialize] or [DataLoom.synchronize] against a real Android
 * `Context`, `Room` database, or `WorkManager` instance. Proving that
 * requires either a real device/emulator instrumented test or a Robolectric
 * unit test; neither exists in this repository yet. That remains a
 * separate, larger follow-up — not silently claimed as covered here.
 *
 * ## What this proves that nothing else in the repository did before it
 *
 * Each of the four Android provider modules
 * (`dataloom-connectivity-android`, `dataloom-storage-room`,
 * `dataloom-queue-room`, `dataloom-scheduler-workmanager`) has its own
 * isolated unit tests, but no module or test previously wired all four
 * together against a real [DataLoomBuilder] assembly. This function is that
 * proof, at compile time: if any provider's constructor signature, required
 * capability, or [DataLoomBuilder] binding shape ever drifts out of sync
 * with what a real native Android application would need, this module fails
 * to compile.
 *
 * ## Transport
 *
 * DataLoom does not ship a default transport — endpoint selection,
 * authentication, and payload serialization are always application-owned.
 * A real application would use `dataloom-transport-ktor`,
 * `dataloom-transport-retrofit`, `dataloom-transport-graphql`,
 * `dataloom-transport-grpc`, or its own [TransportProvider]. This fixture
 * uses [ReferenceTransportProvider], a minimal illustrative stub, to keep
 * this module scoped to proving Android *provider* composition rather than
 * re-proving an already-covered transport module's own HTTP integration.
 *
 * @param context an Android `Context`. Only [Context.getApplicationContext]
 *   is ever read from it, matching every Android provider's own documented
 *   contract.
 */
public fun buildReferenceDataLoom(context: Context): DataLoom {
    val applicationContext = context.applicationContext ?: context

    val connectivityProvider = AndroidConnectivityProvider(applicationContext)
    val storageProvider = RoomStorageProvider(
        DataLoomStorageDatabaseBuilder.build(applicationContext),
    )
    val queueProvider = RoomQueueProvider(
        DataLoomDatabaseBuilder.build(applicationContext),
    )
    val schedulerProvider = WorkManagerSchedulerProvider(applicationContext)
    val transportProvider = ReferenceTransportProvider()

    val bindings = SynchronizationProviderBindings(
        storageProviderId = storageProvider.descriptor.id,
        transportProviderId = transportProvider.descriptor.id,
        schedulerProviderId = schedulerProvider.descriptor.id,
        connectivityProviderId = connectivityProvider.descriptor.id,
        queueProviderId = queueProvider.descriptor.id,
    )
    val strategyBindings = StrategyProviderBindings(
        storageProviderId = storageProvider.descriptor.id,
        transportProviderId = transportProvider.descriptor.id,
        schedulerProviderId = schedulerProvider.descriptor.id,
        connectivityProviderId = connectivityProvider.descriptor.id,
        queueProviderId = queueProvider.descriptor.id,
    )

    return DataLoomBuilder()
        .runtimeDependencies(referenceRuntimeDependencies())
        .provider(connectivityProvider)
        .provider(storageProvider)
        .provider(queueProvider)
        .provider(schedulerProvider)
        .provider(transportProvider)
        .defaultProviderBindings(bindings)
        .defaultStrategyProviderBindings(strategyBindings)
        .build()
}

/**
 * Reference [RuntimeDependencies] using [SystemDataLoomClock] (real wall
 * clock) and `java.util.UUID`-backed identifier generators.
 *
 * A production application should replace the identifier generators with
 * whatever scheme fits its own durability/observability requirements
 * (UUIDs are a reasonable, dependency-free default, not a DataLoom
 * requirement).
 */
private fun referenceRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
    clock = SystemDataLoomClock(),
    identifiers = RuntimeIdentifierGenerators(
        synchronizationEventIds = uuidGenerator(::SynchronizationEventId),
        queueEntryIds = uuidGenerator(::QueueEntryId),
        queueLeaseIds = uuidGenerator(::QueueLeaseId),
        conflictIds = uuidGenerator(::ConflictId),
    ),
)

private fun <T> uuidGenerator(construct: (String) -> T): IdentifierGenerator<T> =
    object : IdentifierGenerator<T> {
        override fun generate(): T = construct(UUID.randomUUID().toString())
    }

/**
 * Minimal illustrative [TransportProvider] — always reports no remote
 * changes and fails any push attempt. Real applications must supply their
 * own transport; see this file's top-level KDoc "Transport" section.
 */
private class ReferenceTransportProvider : TransportProvider {
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.reference-transport"),
        name = ProviderName("Reference Transport (illustrative only)"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> =
        ProviderOperationResult.Failure(ReferenceTransportError())

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        ProviderOperationResult.Success(PullChangesResult.NoChanges())
}

private class ReferenceTransportError(
    override val code: io.dataloom.api.error.ErrorCode =
        io.dataloom.api.error.ErrorCode("REFERENCE_TRANSPORT_NOT_IMPLEMENTED"),
    override val category: io.dataloom.api.error.ErrorCategory = io.dataloom.api.error.ErrorCategory.NETWORK,
    override val severity: io.dataloom.api.error.ErrorSeverity = io.dataloom.api.error.ErrorSeverity.ERROR,
    override val recoverability: io.dataloom.api.error.Recoverability =
        io.dataloom.api.error.Recoverability.NON_RECOVERABLE,
    override val message: String =
        "ReferenceTransportProvider is illustrative only and never pushes changes for real.",
    override val cause: Throwable? = null,
) : io.dataloom.api.error.DataLoomError
