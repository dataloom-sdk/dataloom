package io.dataloom.runtime.facade

import io.dataloom.api.identifier.AssetId
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
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DataLoomDigestAccumulator
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomAssetTransferSpec]/`assetTransferConfiguration` wires the
 * `dataloom-assets` engine into [DataLoom] as an opt-in capability: absent
 * when not configured (and then inert), present when configured, performing
 * no I/O during build, and a working engine when used.
 */
class DataLoomBuilderAssetTransferTest {

    @Test
    fun assetTransferIsNullWhenNotConfigured() {
        assertNull(builder().build().assetTransfer)
    }

    @Test
    fun omittingAssetTransferConfigurationDoesNotChangeProviderLifecycleBehavior() = runTest {
        val without = builder().build()
        val with = builder().assetTransferConfiguration(spec(CountingAssetProvider(), RecordingStore())).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(without.initialize())
        assertIs<ProviderLifecycleResult.InitializeSuccess>(with.initialize())
        assertNull(without.queueWorker)
        assertNull(with.queueWorker)
        assertNull(without.assetTransfer)
        assertNotNull(with.assetTransfer)
    }

    @Test
    fun buildingWithTheSpecPerformsNoProviderOrStoreIo() {
        val provider = CountingAssetProvider()
        val store = RecordingStore()
        val dataLoom = builder().assetTransferConfiguration(spec(provider, store)).build()
        assertNotNull(dataLoom.assetTransfer)
        assertNotNull(dataLoom.assetTransfer)
        assertTrue(provider.calls == 0, "build and property access must not call the asset provider")
        assertTrue(store.calls == 0, "build and property access must not touch the session store")
    }

    @Test
    fun theConfiguredEngineUploadsAndDownloadsThroughTheSuppliedCollaborators() = runTest {
        val provider = InMemoryAssetProvider(FnvDigests)
        val store = InMemoryAssetTransferSessionStore()
        val dataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(provider, store, FnvDigests, chunkSizeBytes = 1_024, verifyBufferBytes = 256),
            )
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)
        val bytes = ByteArray(5_000) { (it * 7).toByte() }

        val up = engine.upload(
            AssetTransferSessionId("up"), AssetId("a"), 1, AssetMediaType("application/octet-stream"), InMemoryAssetSource(bytes),
        )
        assertIs<AssetTransferOutcome.Completed>(up)
        assertTrue(up.session.manifest.chunkLayout.chunkCount == 5, "the spec's chunk size was honoured")
        val sink = InMemoryAssetSink()
        assertIs<AssetTransferOutcome.Completed>(engine.download(AssetTransferSessionId("down"), AssetId("a"), null, sink))
        assertContentEquals(bytes, sink.snapshot())
    }

    @Test
    fun theSpecRejectsNonPositiveSizes() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomAssetTransferSpec(CountingAssetProvider(), RecordingStore(), FnvDigests, chunkSizeBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DataLoomAssetTransferSpec(CountingAssetProvider(), RecordingStore(), FnvDigests, verifyBufferBytes = 0)
        }
    }

    @Test
    fun theSpecRendersWithoutCollaboratorState() {
        val text = spec(CountingAssetProvider(), RecordingStore()).toString()
        assertTrue(text.startsWith("DataLoomAssetTransferSpec(chunkSizeBytes="))
    }

    @Test
    fun theSameProviderInstanceIsUsedNotACopy() {
        val provider = CountingAssetProvider()
        val spec = spec(provider, RecordingStore())
        assertSame(provider, spec.provider)
    }

    // ---------------------------------------------------------------- fixtures

    private fun spec(provider: AssetProvider, store: io.dataloom.assets.AssetTransferSessionStore) =
        DataLoomAssetTransferSpec(provider, store, FnvDigests)

    /** A provider that fails the test if the builder ever touches it. */
    private class CountingAssetProvider : AssetProvider {
        var calls = 0
        override val chunkSizeBounds = io.dataloom.assets.AssetChunkSizeBounds(1, 1_024 * 1_024)
        override suspend fun openUpload(request: io.dataloom.assets.AssetUploadRequest) = fail<io.dataloom.assets.AssetUploadStatus>()
        override suspend fun uploadChunk(request: io.dataloom.assets.AssetChunkUpload) = fail<io.dataloom.assets.AssetUploadStatus>()
        override suspend fun completeUpload(sessionId: AssetTransferSessionId) = fail<io.dataloom.api.asset.AssetManifest>()
        override suspend fun abortUpload(sessionId: AssetTransferSessionId) = fail<Unit>()
        override suspend fun readManifest(assetId: AssetId, version: Long?) = fail<io.dataloom.api.asset.AssetManifest>()
        override suspend fun readChunk(assetId: AssetId, version: Long, index: Int) = fail<ByteArray>()

        private fun <T> fail(): ProviderOperationResult<T> {
            calls++
            return ProviderOperationResult.Failure(
                io.dataloom.assets.AssetTransferError(io.dataloom.assets.AssetErrorKind.PROVIDER_REJECTED, "unexpected call"),
            )
        }
    }

    private class RecordingStore : io.dataloom.assets.AssetTransferSessionStore {
        var calls = 0
        override suspend fun load(sessionId: AssetTransferSessionId): io.dataloom.assets.AssetTransferSession? {
            calls++
            return null
        }

        override suspend fun save(updated: io.dataloom.assets.AssetTransferSession, expectedRevision: Long?): Boolean {
            calls++
            return true
        }
    }

    /**
     * A deterministic non-cryptographic incremental calculator (FNV-1a based) for one end-to-end check without
     * platform source sets: a byte-sum-and-position hash is deterministic and
     * order sensitive, which is all the engine and in-memory provider need.
     */
    private object FnvDigests : DataLoomIncrementalDigestCalculator {
        override fun digest(algorithm: DigestAlgorithm, input: ByteArray): DataLoomDigest =
            newAccumulator(algorithm).also { it.update(input) }.finish()

        override fun newAccumulator(algorithm: DigestAlgorithm): DataLoomDigestAccumulator {
            val requested = algorithm
            return object : DataLoomDigestAccumulator {
                override val algorithm: DigestAlgorithm = requested
                private var state = 1_469_598_103_934_665_603UL
                private var closed = false
                override fun update(input: ByteArray, offset: Int, length: Int) {
                    check(!closed)
                    for (i in offset until offset + length) {
                        state = (state xor input[i].toUByte().toULong()) * 1_099_511_628_211UL
                    }
                }

                override fun finish(): DataLoomDigest {
                    check(!closed)
                    closed = true
                    val size = if (algorithm == DigestAlgorithm.SHA_256) 32 else 64
                    var h = state
                    val bytes = ByteArray(size)
                    for (i in bytes.indices) {
                        bytes[i] = ((h shr ((i % 8) * 8)) and 0xFFUL).toByte()
                        h *= 1_099_511_628_211UL
                    }
                    return DataLoomDigest(algorithm, bytes)
                }

                override fun close() {
                    closed = true
                }
            }
        }
    }

    private fun builder(): DataLoomBuilder {
        val transport = StubTransportProvider()
        return DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(StrategyProviderBindings(transportProviderId = transport.descriptor.id))
    }

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("asset-event") },
            queueEntryIds = generator { QueueEntryId("asset-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("asset-queue-lease") },
            conflictIds = generator { ConflictId("asset-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class StubTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("asset-transport"),
            name = ProviderName("Asset Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            error("unused")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            error("unused")
    }
}
