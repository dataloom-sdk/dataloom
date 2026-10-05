package io.dataloom.runtime.facade

import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
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
import io.dataloom.api.scheduling.SchedulingDelay
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
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.runtime.retry.AssetCircuitOperation
import io.dataloom.runtime.retry.AssetCircuitScopes
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Proves gate `#97`'s two real gaps: an [AssetProvider] registered like any
 * other provider is lifecycle-managed by [DataLoomBuilder]
 * (`ProviderType.ASSET` + the existing `ProviderLifecycleCoordinator`, no
 * special-casing), and [DataLoomBuilder.assetProviderProtectionConfiguration]
 * genuinely trips a real circuit breaker for a failing provider call (and,
 * as a negative control, does not when unconfigured).
 */
class DataLoomBuilderAssetProviderLifecycleAndProtectionTest {

    // ------------------------------------------------------------- lifecycle

    @Test
    fun registeringTheAssetProviderGivesItTheSameLifecycleCallThroughAsAnyOtherProvider() = runTest {
        val provider = CountingLifecycleAssetProvider(InMemoryAssetProvider(FnvDigests))
        val dataLoom = builder()
            .provider(provider)
            .assetTransferConfiguration(DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests))
            .build()

        assertEquals(0, provider.initializeCalls)
        assertEquals(0, provider.closeCalls)

        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        assertEquals(1, provider.initializeCalls, "registering the asset provider must initialize it exactly once")

        assertIs<ProviderLifecycleResult.ShutdownSuccess>(dataLoom.shutdown())
        assertEquals(1, provider.closeCalls, "registering the asset provider must close it exactly once")
    }

    @Test
    fun anAssetProviderSuppliedOnlyToTheSpecIsNotLifecycleManaged() = runTest {
        val provider = CountingLifecycleAssetProvider(InMemoryAssetProvider(FnvDigests))
        val dataLoom = builder()
            // Deliberately NOT registered via .provider()/.providers() -- only
            // handed to the asset-transfer spec, exactly as every caller before
            // this conformance existed had to do.
            .assetTransferConfiguration(DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests))
            .build()

        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        assertEquals(0, provider.initializeCalls, "an unregistered asset provider must not be touched")
    }

    // --------------------------------------------------------------- circuit

    @Test
    fun aFailingAssetProviderCallTripsTheCircuitPerPolicyWhenProtectionIsConfigured() = runTest {
        val provider = FailingOpenUploadAssetProvider(InMemoryAssetProvider(FnvDigests))
        val store = RecordingCircuitStore()
        val dataLoom = builder()
            .assetTransferConfiguration(DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests))
            .assetProviderProtectionConfiguration(
                DataLoomAssetProviderProtectionSpec(
                    circuitBreakerConfiguration = CircuitBreakerConfiguration(
                        failureThreshold = 2,
                        failureWindow = SchedulingDelay(60_000L),
                        openDuration = SchedulingDelay(60_000L),
                    ),
                    circuitBreakerStateStore = store,
                    scopes = scopesFor(provider.descriptor.id),
                ),
            )
            .build()
        val engine = dataLoom.assetTransfer!!

        // Two attempts reach the provider and trip the circuit at the configured threshold.
        repeat(2) { attempt ->
            val outcome = engine.upload(
                AssetTransferSessionId("tripping"), AssetId("a"), 1,
                AssetMediaType("application/octet-stream"), InMemoryAssetSource(ByteArray(10)),
            )
            assertIs<AssetTransferOutcome.Interrupted>(outcome, "attempt $attempt should be interrupted by the provider failure")
        }
        assertEquals(2, provider.openUploadCalls, "both attempts before the threshold must reach the provider")

        // A third attempt must be rejected by the now-open circuit, never reaching the provider.
        val rejected = engine.upload(
            AssetTransferSessionId("tripping"), AssetId("a"), 1,
            AssetMediaType("application/octet-stream"), InMemoryAssetSource(ByteArray(10)),
        )
        assertIs<AssetTransferOutcome.Interrupted>(rejected)
        assertEquals(2, provider.openUploadCalls, "a tripped circuit must reject the call before it reaches the provider")
    }

    @Test
    fun aFailingAssetProviderCallDoesNotTripAnythingWhenProtectionIsNotConfigured() = runTest {
        val provider = FailingOpenUploadAssetProvider(InMemoryAssetProvider(FnvDigests))
        val dataLoom = builder()
            .assetTransferConfiguration(DataLoomAssetTransferSpec(provider, InMemoryAssetTransferSessionStore(), FnvDigests))
            // No assetProviderProtectionConfiguration: this is the negative control.
            .build()
        val engine = dataLoom.assetTransfer!!

        repeat(3) { attempt ->
            val outcome = engine.upload(
                AssetTransferSessionId("unprotected"), AssetId("a"), 1,
                AssetMediaType("application/octet-stream"), InMemoryAssetSource(ByteArray(10)),
            )
            assertIs<AssetTransferOutcome.Interrupted>(outcome, "attempt $attempt should be interrupted by the raw provider failure")
        }
        assertEquals(3, provider.openUploadCalls, "without protection every attempt must reach the provider directly")
    }

    // ---------------------------------------------------------------- fixtures

    private fun scopesFor(providerId: ProviderId): AssetCircuitScopes = AssetCircuitScopes(
        initialization = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.INITIALIZE.retryOperation),
        health = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.HEALTH.retryOperation),
        close = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.CLOSE.retryOperation),
        openUpload = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.OPEN_UPLOAD.retryOperation),
        uploadChunk = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.UPLOAD_CHUNK.retryOperation),
        completeUpload = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.COMPLETE_UPLOAD.retryOperation),
        abortUpload = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.ABORT_UPLOAD.retryOperation),
        readManifest = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.READ_MANIFEST.retryOperation),
        readChunk = CircuitBreakerScope.providerOperation(providerId, AssetCircuitOperation.READ_CHUNK.retryOperation),
    )

    /** Counts lifecycle calls while delegating every asset-transfer operation unchanged. */
    private class CountingLifecycleAssetProvider(
        private val delegate: AssetProvider,
    ) : AssetProvider by delegate {
        var initializeCalls: Int = 0
        var closeCalls: Int = 0

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCalls++
            return delegate.initialize(context)
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCalls++
            return delegate.close()
        }
    }

    /** Always fails [openUpload] with a circuit-eligible, recoverable error; counts attempts. */
    private class FailingOpenUploadAssetProvider(
        private val delegate: AssetProvider,
    ) : AssetProvider by delegate {
        var openUploadCalls: Int = 0

        override suspend fun openUpload(request: AssetUploadRequest): ProviderOperationResult<io.dataloom.assets.AssetUploadStatus> {
            openUploadCalls++
            return ProviderOperationResult.Failure(
                AssetTransferError(AssetErrorKind.PROVIDER_UNAVAILABLE, "Simulated provider outage."),
            )
        }
    }

    /** Real in-memory circuit-breaker state store with optimistic-concurrency compare-and-set. */
    private class RecordingCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> = ProviderOperationResult.Success(
            records[scope]?.let(CircuitBreakerLoadResult::Found) ?: CircuitBreakerLoadResult.Missing,
        )

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(CircuitBreakerCompareAndSetResult.Conflict(current))
            }
            val updated = CircuitBreakerStateRecord(state = request.nextState, version = (current?.version ?: -1L) + 1L)
            records[request.scope] = updated
            return ProviderOperationResult.Success(CircuitBreakerCompareAndSetResult.Updated(updated))
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
            synchronizationEventIds = generator { SynchronizationEventId("asset-protection-event") },
            queueEntryIds = generator { QueueEntryId("asset-protection-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("asset-protection-queue-lease") },
            conflictIds = generator { ConflictId("asset-protection-conflict") },
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
            id = ProviderId("asset-protection-transport"),
            name = ProviderName("Asset Protection Transport"),
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
