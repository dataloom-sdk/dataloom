package io.dataloom.runtime.facade

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
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
import io.dataloom.assets.AssetTransferSession
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetTransferSessionStore
import io.dataloom.assets.AssetTransferSessionStoreException
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.runtime.observation.health.AssetTransferHealthTracker
import io.dataloom.runtime.observation.health.AssetTransferOutcomeKind
import io.dataloom.runtime.observation.health.DataLoomHealthFindingCode
import io.dataloom.runtime.observation.health.DataLoomHealthSeverity
import io.dataloom.runtime.observation.health.dataLoomHealthSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

/**
 * Proves `DataLoomBuilder.assetTransferHealthTracker` is a real, reachable
 * caller: a real [io.dataloom.assets.AssetTransferEngine] built by a real
 * [DataLoomBuilder], driven to a genuine
 * [AssetTransferOutcome.SessionStoreFailure] by a session store that actually
 * throws, reports through to [AssetTransferHealthTracker.snapshot] and from
 * there into [dataLoomHealthSnapshot]'s severity -- and that a healthy run,
 * and a run with no tracker configured at all, do not.
 *
 * ## Revert-and-observe
 *
 * During development, the wiring line in [DataLoomBuilder.kt]'s build() --
 * `assetTransferHealthTracker?.let { assetTransferEventRecorderObserver
 * .withAssetTransferHealthTracking(it) } ?: assetTransferEventRecorderObserver`
 * -- was temporarily reverted to always return
 * `assetTransferEventRecorderObserver` (dropping the tracker entirely).
 * [sessionStoreFailure_incrementsTheTrackerAndDegradesTheSnapshot] then failed
 * exactly as expected (`tracker.snapshot()` stayed at its initial, all-zero
 * state despite a real upload call), confirming the wiring -- not a
 * coincidence -- produces the result below. The wiring was restored before
 * this file was committed.
 */
class DataLoomBuilderAssetTransferHealthTrackerTest {

    @Test
    fun aHealthyUpload_leavesTheTrackerAtZeroFailuresAndTheSnapshotHealthy() = runTest {
        val tracker = AssetTransferHealthTracker(FixedDataLoomClock(DataLoomInstant(1_000L)))
        val provider = InMemoryAssetProvider(FnvDigests)
        val dataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            .assetTransferHealthTracker(tracker)
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)

        val outcome = engine.upload(
            AssetTransferSessionId("healthy-up"), AssetId("a"), 1, AssetMediaType("application/octet-stream"),
            InMemoryAssetSource(ByteArray(2_000) { it.toByte() }),
        )

        assertIs<AssetTransferOutcome.Completed>(outcome)
        val observed = tracker.snapshot()
        assertEquals(AssetTransferOutcomeKind.COMPLETED, observed.lastOutcome)
        assertEquals(0, observed.consecutiveSessionStoreFailures)

        val snapshot = dataLoomHealthSnapshot(assetTransferObservation = observed)
        assertEquals(DataLoomHealthSeverity.HEALTHY, snapshot.severity)
        assertEquals(emptyList(), snapshot.findings)
        val assetTransferHealth = assertNotNull(snapshot.assetTransferHealth)
        assertEquals(DataLoomHealthSeverity.HEALTHY, assetTransferHealth.severity)
    }

    @Test
    fun sessionStoreFailure_incrementsTheTrackerAndDegradesTheSnapshot() = runTest {
        val tracker = AssetTransferHealthTracker(FixedDataLoomClock(DataLoomInstant(1_000L)))
        val provider = InMemoryAssetProvider(FnvDigests)
        val dataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(provider, AlwaysFailingSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            .assetTransferHealthTracker(tracker)
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)

        val outcome = engine.upload(
            AssetTransferSessionId("failing-up"), AssetId("a"), 1, AssetMediaType("application/octet-stream"),
            InMemoryAssetSource(ByteArray(2_000) { it.toByte() }),
        )

        assertIs<AssetTransferOutcome.SessionStoreFailure>(outcome)
        val observed = tracker.snapshot()
        assertEquals(AssetTransferOutcomeKind.SESSION_STORE_FAILURE, observed.lastOutcome)
        assertEquals(1, observed.consecutiveSessionStoreFailures)

        val snapshot = dataLoomHealthSnapshot(assetTransferObservation = observed)
        assertEquals(DataLoomHealthSeverity.DEGRADED, snapshot.severity)
        assertEquals(
            listOf(DataLoomHealthFindingCode.ASSET_TRANSFER_SESSION_STORE_FAILING),
            snapshot.findings.map { it.code },
        )
    }

    @Test
    fun threeConsecutiveSessionStoreFailures_areUnhealthy_andASuccessResetsTheCounter() = runTest {
        val tracker = AssetTransferHealthTracker(FixedDataLoomClock(DataLoomInstant(1_000L)))
        val failingProvider = InMemoryAssetProvider(FnvDigests)
        val failingDataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(failingProvider, AlwaysFailingSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            .assetTransferHealthTracker(tracker)
            .build()
        val failingEngine = assertNotNull(failingDataLoom.assetTransfer)

        repeat(3) { index ->
            val outcome = failingEngine.upload(
                AssetTransferSessionId("fail-$index"), AssetId("a"), 1, AssetMediaType("application/octet-stream"),
                InMemoryAssetSource(ByteArray(2_000) { it.toByte() }),
            )
            assertIs<AssetTransferOutcome.SessionStoreFailure>(outcome)
        }
        assertEquals(3, tracker.snapshot().consecutiveSessionStoreFailures)
        assertEquals(
            DataLoomHealthSeverity.UNHEALTHY,
            dataLoomHealthSnapshot(assetTransferObservation = tracker.snapshot()).severity,
        )

        // A subsequent healthy call (real store, same tracker) resets the counter.
        val healthyProvider = InMemoryAssetProvider(FnvDigests)
        val healthyDataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(healthyProvider, InMemoryAssetTransferSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            .assetTransferHealthTracker(tracker)
            .build()
        val healthyEngine = assertNotNull(healthyDataLoom.assetTransfer)
        val recovered = healthyEngine.upload(
            AssetTransferSessionId("recovered"), AssetId("a"), 1, AssetMediaType("application/octet-stream"),
            InMemoryAssetSource(ByteArray(2_000) { it.toByte() }),
        )
        assertIs<AssetTransferOutcome.Completed>(recovered)
        assertEquals(0, tracker.snapshot().consecutiveSessionStoreFailures)
    }

    @Test
    fun theTrackerComposesWithAnAlreadyConfiguredOperationalEventRecorder_bothObserveTheSameOutcome() = runTest {
        val tracker = AssetTransferHealthTracker(FixedDataLoomClock(DataLoomInstant(1_000L)))
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("composed-health-and-outbox")
        val provider = InMemoryAssetProvider(FnvDigests)
        val dataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            .assetTransferOperationalEventOutboxConfiguration(
                DataLoomAssetTransferOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .assetTransferHealthTracker(tracker)
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)

        val outcome = engine.upload(
            AssetTransferSessionId("composed-up"), AssetId("a"), 1, AssetMediaType("application/octet-stream"),
            InMemoryAssetSource(ByteArray(2_000) { it.toByte() }),
        )

        assertIs<AssetTransferOutcome.Completed>(outcome)
        // Both observers saw the same real outcome from the same call.
        assertEquals(AssetTransferOutcomeKind.COMPLETED, tracker.snapshot().lastOutcome)
        assertEquals(1, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun notConfiguringTheTracker_leavesAssetTransferWorkingAndNothingToObserve() = runTest {
        val provider = InMemoryAssetProvider(FnvDigests)
        val dataLoom = builder()
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests, chunkSizeBytes = 1_024),
            )
            // Note: assetTransferHealthTracker is never called.
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)

        val outcome = engine.upload(
            AssetTransferSessionId("untracked-up"), AssetId("a"), 1, AssetMediaType("application/octet-stream"),
            InMemoryAssetSource(ByteArray(2_000) { it.toByte() }),
        )

        // The engine behaves exactly as before: a working, tracker-free upload.
        assertIs<AssetTransferOutcome.Completed>(outcome)
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
            synchronizationEventIds = generator { SynchronizationEventId("asset-health-event") },
            queueEntryIds = generator { QueueEntryId("asset-health-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("asset-health-queue-lease") },
            conflictIds = generator { ConflictId("asset-health-conflict") },
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
            id = ProviderId("asset-health-transport"),
            name = ProviderName("Asset Health Transport"),
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
        override val code: ErrorCode = ErrorCode("DL-FAKE-SESSION-STORE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /** Always throws on save -- `guardStore` converts this into a real `SessionStoreFailure` outcome. */
    private class AlwaysFailingSessionStore : AssetTransferSessionStore {
        override suspend fun load(sessionId: AssetTransferSessionId): AssetTransferSession? = null

        override suspend fun save(updated: AssetTransferSession, expectedRevision: Long?): Boolean =
            throw AssetTransferSessionStoreException(FakeError())
    }

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        suspend fun recordedEntryCount(scope: OperationalEventOutboxScope): Int = records[scope]?.state?.entries?.size ?: 0

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
