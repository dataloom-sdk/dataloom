package io.dataloom.assets.file

import io.dataloom.api.identifier.AssetId
import io.dataloom.assets.AssetTransferEngine
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.assets.octetStream
import io.dataloom.assets.patternBytes
import io.dataloom.assets.platformDigests
import io.dataloom.assets.sessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * Apple/POSIX counterpart of `FileAssetProviderParallelTransferTest`: proves
 * [AssetTransferEngine]'s parallel chunk-transfer mode (FR-ASSET-006) works
 * end to end against the real [AppleFileAssetProvider], not just the JVM
 * file-backed provider or the in-memory reference provider the deeper
 * concurrency-limit, fairness and fail-fast proofs in
 * `ParallelAssetTransferEngineTest` use. That engine-level machinery is
 * provider-agnostic (it only ever talks to [io.dataloom.assets.AssetProvider]
 * through its public interface), so this is an integration-level round trip,
 * not a repeat of those proofs.
 *
 * Compile-verified only for `iosArm64`/`iosSimulatorArm64`/`iosX64`: this
 * suite cannot be executed on a Windows host, and no macOS/Xcode runner was
 * available in this session either — same caveat as this module's other
 * `iosTest` sources.
 */
class AppleFileAssetProviderParallelTransferTest {

    @Test
    fun `a parallel upload then download round trips exactly through the apple file-backed provider`() = runTest {
        val base = uniqueTestDirectory("apple-file-asset-provider-parallel")
        val provider = AppleFileAssetProvider(base, platformDigests())
        val chunkSize = 200
        val bytes = patternBytes(chunkSize * 11 + 37, seed = 9)

        val engine = AssetTransferEngine(
            provider,
            InMemoryAssetTransferSessionStore(),
            platformDigests(),
            chunkSizeBytes = chunkSize,
            maxConcurrency = 4,
        )
        val up = engine.upload(sessionId("up"), AssetId("parallel-file"), 1, octetStream, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(up)

        val sink = InMemoryAssetSink()
        val down = AssetTransferEngine(
            provider,
            InMemoryAssetTransferSessionStore(),
            platformDigests(),
            chunkSizeBytes = chunkSize,
            maxConcurrency = 4,
        ).download(sessionId("down"), AssetId("parallel-file"), null, sink)
        assertIs<AssetTransferOutcome.Completed>(down)
        assertContentEquals(bytes, sink.snapshot())
    }
}
