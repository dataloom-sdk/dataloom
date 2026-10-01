package io.dataloom.runtime.facade

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
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
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DataLoomDigestAccumulator
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomAssetTransferOperationalEventOutboxSpec]/
 * `assetTransferOperationalEventOutboxConfiguration` is a real, reachable
 * caller of [io.dataloom.api.operational.DurableOperationalEventOutbox] for a
 * real asset transfer produced by a real [DataLoomBuilder]-built
 * [io.dataloom.assets.AssetTransferEngine] -- and that a durable-recording
 * failure never changes the real [AssetTransferOutcome] the caller receives,
 * and that nothing is bridged unless both
 * [DataLoomBuilder.assetTransferConfiguration] and this spec are configured.
 */
class DataLoomBuilderAssetTransferOperationalEventOutboxTest {

    @Test
    fun successfulUpload_appendsARealEntry_whenBothAssetTransferAndOutboxAreConfigured() = runTest {
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-asset-transfer-events")
        val provider = InMemoryAssetProvider(FnvDigests)
        val dataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            .assetTransferOperationalEventOutboxConfiguration(
                DataLoomAssetTransferOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)

        val outcome = engine.upload(
            AssetTransferSessionId("up"), AssetId("a"), 1, AssetMediaType("application/octet-stream"),
            InMemoryAssetSource(ByteArray(5_000) { it.toByte() }),
        )

        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(1, outboxStore.recordedEntryCount(scope))
        val envelope = outboxStore.envelopes(scope).single()
        assertEquals("dataloom.asset.transfer.upload.completed", envelope.type.value)
    }

    @Test
    fun nothingIsBridged_whenOutboxIsConfiguredButAssetTransferIsNot() = runTest {
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-no-engine-events")
        val dataLoom = builder()
            // Note: assetTransferConfiguration is never called, so there is no
            // AssetTransferEngine and no outcome to ever bridge.
            .assetTransferOperationalEventOutboxConfiguration(
                DataLoomAssetTransferOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()

        assertNull(dataLoom.assetTransfer)
        assertEquals(0, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun nothingIsBridged_whenAssetTransferIsConfiguredButOutboxIsNot() = runTest {
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-no-outbox-events")
        val provider = InMemoryAssetProvider(FnvDigests)
        val dataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            // Note: assetTransferOperationalEventOutboxConfiguration is never called.
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)

        val outcome = engine.upload(
            AssetTransferSessionId("up"), AssetId("a"), 1, AssetMediaType("application/octet-stream"),
            InMemoryAssetSource(ByteArray(5_000) { it.toByte() }),
        )

        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(0, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun appendFailureDoesNotChangeTheRealUploadOutcome() = runTest {
        val scope = OperationalEventOutboxScope("integration-failing-events")
        val provider = InMemoryAssetProvider(FnvDigests)
        val dataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            .assetTransferOperationalEventOutboxConfiguration(
                DataLoomAssetTransferOperationalEventOutboxSpec(store = AlwaysFailingOperationalEventOutboxStore(), scope = scope),
            )
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)
        val bytes = ByteArray(5_000) { it.toByte() }

        val outcome = engine.upload(
            AssetTransferSessionId("up"), AssetId("a"), 1, AssetMediaType("application/octet-stream"), InMemoryAssetSource(bytes),
        )

        // The real transfer outcome is unchanged despite the outbox append failure.
        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertEquals(5_000L, outcome.session.manifest.sizeBytes)
    }

    // ---------------------------------------------------------------- fixtures

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
            synchronizationEventIds = generator { SynchronizationEventId("asset-outbox-event") },
            queueEntryIds = generator { QueueEntryId("asset-outbox-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("asset-outbox-queue-lease") },
            conflictIds = generator { ConflictId("asset-outbox-conflict") },
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
            id = ProviderId("asset-outbox-transport"),
            name = ProviderName("Asset Outbox Transport"),
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

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        suspend fun recordedEntryCount(scope: OperationalEventOutboxScope): Int = records[scope]?.state?.entries?.size ?: 0

        fun envelopes(scope: OperationalEventOutboxScope): List<io.dataloom.api.operational.OperationalEventEnvelope> =
            records[scope]?.state?.entries?.map { it.envelope } ?: emptyList()

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    /** Always fails the underlying durable store -- proves append failures never propagate. */
    private class AlwaysFailingOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(FakeError())
    }

    /**
     * A deterministic non-cryptographic incremental calculator (FNV-1a based),
     * the same fixture [DataLoomBuilderAssetTransferTest] uses, so this test
     * needs no platform source sets.
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
}
