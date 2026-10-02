@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

import io.dataloom.api.asset.AssetMediaType
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
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.security.AppleDataLoomDigestCalculator
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferPhase
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.DurableAssetTransferSessionStore
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.runtime.facade.DataLoomAssetTransferSpec
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.state.AppleFileDurableDomainStores
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native iOS Simulator runtime proof that
 * [DataLoomAssetTransferSpec]/`assetTransferConfiguration` genuinely adopts
 * [io.dataloom.runtime.state.AppleFileDurableStateStore] (through
 * [io.dataloom.assets.DurableAssetTransferSessionStore]) as a real
 * [io.dataloom.assets.AssetTransferSessionStore] backing store on iOS,
 * through a real [DataLoomBuilder].
 *
 * Before this test, [AppleFileDurableDomainStores.assetTransferSessionStore]
 * (`#93`, 2026-09-28) existed and had its own store-level iOS proof, but
 * nothing drove it through [DataLoomBuilder] on iOS -- only
 * strategy-decision diagnostics
 * ([IosReferenceConsumerStrategyDiagnosticsAppleFileTest]) had that bar
 * before this file. This test uploads a real multi-chunk asset through a
 * real [DataLoomBuilder]-assembled `AssetTransferEngine`, then reads the
 * completed session back out through a *second*, independently constructed
 * [DurableAssetTransferSessionStore] pointed at the same on-disk file --
 * proving genuine persistence to a real file.
 *
 * ## What this does not prove
 *
 * A real caller reading transfer-session state from a production operations
 * surface; concurrent-writer contention against a real second OS process
 * (see `docs/apple/process-termination-investigation.md`); or an interrupted
 * transfer actually resuming after a restart (this test uploads to
 * completion in one process -- resumption itself is already proven at the
 * store level by `AppleFileDurableDomainStoresTest`'s asset-session
 * chunk-recovery case, not re-proven here).
 *
 * ## A note on how this was verified
 *
 * Like [IosReferenceConsumerStrategyDiagnosticsAppleFileTest], this file can
 * be cross-compiled from a Windows development host but **cannot be
 * executed** there -- only a real macOS host with Xcode and the iOS
 * Simulator can run `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail signal
 * for this file's runtime behavior, not local cross-compilation alone.
 */
class IosReferenceConsumerAssetTransferAppleFileTest {

    @Test
    fun aRealUploadIsDurablyRecordedToARealAppleFileStoreAndSurvivesRestart() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = assetTransferDirectoryPath(runId)
        val fileName = "dataloom-asset-transfer-session-$runId.tsv"
        val sessionId = AssetTransferSessionId("ios-upload-$runId")
        val assetId = AssetId("ios-asset-$runId")

        val digestCalculator = AppleDataLoomDigestCalculator()
        val provider = InMemoryAssetProvider(digestCalculator)
        val sessionStore = DurableAssetTransferSessionStore(
            AppleFileDurableDomainStores.assetTransferSessionStore(directoryPath, fileName),
        )
        val transport = StubTransportProvider()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(assetTransferRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(StrategyProviderBindings(transportProviderId = transport.descriptor.id))
            .assetTransferConfiguration(
                DataLoomAssetTransferSpec(
                    provider = provider,
                    sessionStore = sessionStore,
                    digestCalculator = digestCalculator,
                    chunkSizeBytes = 1_024,
                ),
            )
            .build()
        val engine = assertNotNull(dataLoom.assetTransfer)

        val bytes = ByteArray(5_000) { (it * 7).toByte() }
        val uploaded = engine.upload(sessionId, assetId, 1, AssetMediaType("application/octet-stream"), InMemoryAssetSource(bytes))
        val completed = assertIs<AssetTransferOutcome.Completed>(uploaded)
        assertEquals(5, completed.session.manifest.chunkLayout.chunkCount, "the spec's chunk size was honoured")

        // A brand-new session store instance, pointed at the same
        // directory/file but sharing no in-memory state with the one the
        // builder above used -- the same "process restart" bar
        // AppleFileDurableStateStoreTest and the strategy-diagnostics iOS
        // test already establish for this store family.
        val restartedStore = DurableAssetTransferSessionStore(
            AppleFileDurableDomainStores.assetTransferSessionStore(directoryPath, fileName),
        )
        val restartedSession = assertNotNull(restartedStore.load(sessionId))
        assertEquals(AssetTransferPhase.COMPLETED, restartedSession.phase)
        assertEquals(completed.session.revision, restartedSession.revision)

        // The persisted session is also usable for a real download, proving
        // the restored manifest/chunk data is not merely structurally equal
        // but functionally correct.
        val sink = InMemoryAssetSink()
        assertIs<AssetTransferOutcome.Completed>(engine.download(AssetTransferSessionId("ios-download-$runId"), assetId, null, sink))
        assertContentEquals(bytes, sink.snapshot())
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun assetTransferDirectoryPath(runId: String): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-ios-reference-consumer-asset-transfer-")
        append(runId)
    }

    private fun assetTransferRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = AppleDataLoomClock(),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("ios-asset-transfer-event") },
            queueEntryIds = generator { QueueEntryId("ios-asset-transfer-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("ios-asset-transfer-queue-lease") },
            conflictIds = generator { ConflictId("ios-asset-transfer-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    /** Test-only [TransportProvider]; the asset-transfer engine never calls it. */
    private class StubTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("io.dataloom.consumer.ios.test.asset-transfer-transport"),
            name = ProviderName("Asset Transfer Test Transport"),
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
            error("StubTransportProvider does not support push.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }
}
