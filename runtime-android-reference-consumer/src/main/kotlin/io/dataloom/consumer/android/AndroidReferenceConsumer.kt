package io.dataloom.consumer.android

import android.content.Context
import io.dataloom.android.androidDataLoomProviders
import io.dataloom.android.installAndroidProviders
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
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.SystemDataLoomDigestCalculator
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.SystemDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferSession
import io.dataloom.assets.AssetTransferSessionCodec
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.DurableAssetTransferSessionStore
import io.dataloom.assets.file.FileAssetProvider
import io.dataloom.connectivity.android.AndroidConnectivityProvider
import io.dataloom.queue.room.DataLoomDatabaseBuilder
import io.dataloom.queue.room.RoomDurableStateStore
import io.dataloom.queue.room.RoomQueueProvider
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomAssetTransferSpec
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.scheduler.workmanager.WorkManagerSchedulerProvider
import io.dataloom.storage.room.DataLoomStorageDatabaseBuilder
import io.dataloom.storage.room.RoomStorageProvider
import java.io.File
import java.util.UUID

/**
 * Native Android reference wiring for `#101` (DL-039A) — proves that
 * `dataloom-android`'s public [installAndroidProviders] helper (which wires
 * [AndroidConnectivityProvider], [RoomStorageProvider], [RoomQueueProvider],
 * and [WorkManagerSchedulerProvider]) actually composes into one buildable
 * [DataLoom] instance through [DataLoomBuilder]'s public API.
 *
 * This module used to hand-wire all four providers directly; it now
 * dogfoods `dataloom-android`'s own real, production helper instead —
 * proving that helper's public API is genuinely usable end to end, not just
 * self-declared usable in its own module's KDoc.
 *
 * ## Scope
 *
 * [buildReferenceDataLoom] is real, correct wiring code — it is not a fake
 * or a stub. `AndroidReferenceConsumerRobolectricTest` proves it against a
 * real (Robolectric-simulated) Android runtime, including a full
 * [DataLoom.synchronize] pass, and `AndroidReferenceConsumerInstrumentedTest`
 * (`src/androidTest`) proves the same against a real Gradle Managed Device
 * emulator — see each test's own KDoc for the exact boundary of what is and
 * is not covered.
 *
 * ## Transport
 *
 * DataLoom does not ship a default transport — endpoint selection,
 * authentication, and payload serialization are always application-owned.
 * A real application would use `dataloom-transport-ktor`,
 * `dataloom-transport-retrofit`, `dataloom-transport-graphql`,
 * `dataloom-transport-grpc`, or its own [TransportProvider]. This fixture
 * defaults to [ReferenceTransportProvider], a minimal illustrative stub, to
 * keep this module scoped to proving Android *provider* composition rather
 * than re-proving an already-covered transport module's own HTTP
 * integration. [transportProvider] is overridable so a caller (for example
 * a test proving a full synchronization pass) can supply a transport that
 * actually returns data, without hand-wiring the other three providers
 * again.
 *
 * @param context an Android `Context`. Only [Context.getApplicationContext]
 *   is ever read from it, matching every Android provider's own documented
 *   contract.
 * @param transportProvider the [TransportProvider] to register. Defaults to
 *   [ReferenceTransportProvider].
 * @param storageDatabaseName passed straight through to
 *   [androidDataLoomProviders]. Override only when isolation from a
 *   previous run's on-disk database is required — for example, an
 *   instrumented test asserting on a fresh instance's own behavior rather
 *   than a resumed one's.
 * @param queueDatabaseName passed straight through to
 *   [androidDataLoomProviders]. Same override rationale as
 *   [storageDatabaseName].
 * @param assetTransfer opt-in asset-transfer wiring from
 *   [buildReferenceAssetTransfer]. When non-null it is passed to
 *   [DataLoomBuilder.assetTransferConfiguration] and
 *   [DataLoom.assetTransfer] is non-null; when null the capability is
 *   absent, exactly as before.
 */
public fun buildReferenceDataLoom(
    context: Context,
    transportProvider: TransportProvider = ReferenceTransportProvider(),
    storageDatabaseName: String = DataLoomStorageDatabaseBuilder.DEFAULT_NAME,
    queueDatabaseName: String = DataLoomDatabaseBuilder.DEFAULT_NAME,
    assetTransfer: ReferenceAssetTransfer? = null,
): DataLoom {
    val providers = androidDataLoomProviders(
        context = context,
        storageDatabaseName = storageDatabaseName,
        queueDatabaseName = queueDatabaseName,
    )

    val builder = DataLoomBuilder()
        .runtimeDependencies(referenceRuntimeDependencies())
        .installAndroidProviders(providers, transportProvider)
    if (assetTransfer != null) {
        builder.assetTransferConfiguration(assetTransfer.spec)
    }
    return builder.build()
}

/**
 * Android reference wiring for the opt-in asset-transfer capability
 * (`#97`, FR-ASSET-005 on Android): a real filesystem [FileAssetProvider]
 * as the asset side, and a [DurableAssetTransferSessionStore] over a real
 * Room-backed [RoomDurableStateStore] as the session store, so an
 * interrupted or completed transfer's session survives a process restart.
 *
 * [FileAssetProvider] is the same JVM reference provider
 * `dataloom-assets` ships (it has no Android source set; Android consumes
 * its JVM variant), and plays the *remote* side here: this is an
 * in-process reference, not a transport to a server. See
 * `docs/android/reference-consumer.md` for the exact boundary.
 *
 * The caller owns [close], which closes the session database.
 */
public class ReferenceAssetTransfer internal constructor(
    public val spec: DataLoomAssetTransferSpec,
    private val database: DataLoomRoomDatabase,
) {
    /** Closes the Room database backing the session store. */
    public fun close() {
        database.close()
    }
}

/**
 * Builds a [ReferenceAssetTransfer] over [provider] with sessions persisted
 * in the Room database named [sessionDatabaseName]. Calling it again with
 * the same database name reopens the same durable session state, which is
 * how a client restart is simulated.
 *
 * [provider] is passed in rather than built here because it plays the
 * *remote* side and must outlive a client restart: [FileAssetProvider]
 * keeps its committed-asset index in memory (the files persist, the index
 * is not rebuilt from them), so a restarted client must talk to the same
 * provider instance, as a real client would talk to the same server.
 */
public fun buildReferenceAssetTransfer(
    context: Context,
    provider: AssetProvider,
    digests: DataLoomIncrementalDigestCalculator,
    sessionDatabaseName: String,
    chunkSizeBytes: Int,
): ReferenceAssetTransfer {
    val database = DataLoomDatabaseBuilder.build(context, sessionDatabaseName)
    val sessionStore = DurableAssetTransferSessionStore(
        RoomDurableStateStore<AssetTransferSessionId, AssetTransferSession>(
            database = database,
            namespace = ASSET_TRANSFER_SESSION_NAMESPACE,
            scopeKeyEncoder = DurableAssetTransferSessionStore.KeyEncoder,
            codec = AssetTransferSessionCodec(),
        ),
    )
    return ReferenceAssetTransfer(
        spec = DataLoomAssetTransferSpec(
            provider = provider,
            sessionStore = sessionStore,
            digestCalculator = digests,
            chunkSizeBytes = chunkSizeBytes,
        ),
        database = database,
    )
}

/** A real filesystem [FileAssetProvider] rooted at [assetDirectory]. */
public fun buildReferenceFileAssetProvider(
    assetDirectory: File,
    digests: DataLoomIncrementalDigestCalculator = SystemDataLoomDigestCalculator(),
): FileAssetProvider = FileAssetProvider(assetDirectory.toPath(), digests)

private const val ASSET_TRANSFER_SESSION_NAMESPACE = "asset-transfer-session"

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
