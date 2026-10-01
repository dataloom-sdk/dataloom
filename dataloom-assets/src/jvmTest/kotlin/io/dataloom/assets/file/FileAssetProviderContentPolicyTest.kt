package io.dataloom.assets.file

import io.dataloom.api.identifier.AssetId
import io.dataloom.assets.AssetContentPolicy
import io.dataloom.assets.AssetContentPolicyContext
import io.dataloom.assets.AssetContentPolicyDecision
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetTransferEngine
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.octetStream
import io.dataloom.assets.patternBytes
import io.dataloom.assets.platformDigests
import io.dataloom.assets.sessionId
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [AssetContentPolicy] (FR-ASSET-012) checked against the real filesystem,
 * not just the in-memory reference provider/sink -- the same discipline
 * [FileAssetProviderCleanupTest] uses for every other terminal-failure
 * cleanup path this engine has: a deny/quarantine must leave nothing on disk,
 * verified by listing the actual files, not only by reading the returned
 * [AssetTransferOutcome].
 */
class FileAssetProviderContentPolicyTest {

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix)

    private fun uploadDir(base: java.nio.file.Path, sessionValue: String) =
        base.resolve("uploads").resolve(FileAssetIo.safeName(sessionValue))

    private fun committedAsset(base: java.nio.file.Path, assetId: String, version: Long) =
        base.resolve("committed").resolve(FileAssetIo.safeName(assetId)).resolve(version.toString()).resolve("asset.bin")

    private class DenyingPolicy(private val denyIndex: Int) : AssetContentPolicy {
        override suspend fun evaluate(context: AssetContentPolicyContext, chunk: ByteArray): AssetContentPolicyDecision =
            if (context.chunkIndex == denyIndex) AssetContentPolicyDecision.Deny("test-denied") else AssetContentPolicyDecision.Allow
    }

    @Test
    fun `a denied upload leaves no chunk files and no committed object on the real filesystem`() = runTest {
        val base = tempDir("file-provider-content-policy-upload")
        val provider = FileAssetProvider(base, platformDigests())
        val chunkSize = 200
        val bytes = patternBytes(chunkSize * 10, seed = 11)
        val session = sessionId("s-denied-upload")

        val engine = AssetTransferEngine(
            provider,
            InMemoryAssetTransferSessionStore(),
            platformDigests(),
            chunkSizeBytes = chunkSize,
            contentPolicy = DenyingPolicy(denyIndex = 4),
        )
        val outcome = engine.upload(session, AssetId("denied-doc"), 1, octetStream, InMemoryAssetSource(bytes))

        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_DENIED, failed.session.failure)
        assertFalse(Files.exists(uploadDir(base, session.value)), "a denied upload's temp chunk files must be cleaned up")
        assertFalse(Files.exists(committedAsset(base, "denied-doc", 1)), "a denied upload must never become visible at its committed path")
    }

    @Test
    fun `a quarantined upload leaves no chunk files on the real filesystem`() = runTest {
        val base = tempDir("file-provider-content-policy-quarantine")
        val provider = FileAssetProvider(base, platformDigests())
        val chunkSize = 200
        val bytes = patternBytes(chunkSize * 6, seed = 13)
        val session = sessionId("s-quarantined-upload")

        val engine = AssetTransferEngine(
            provider,
            InMemoryAssetTransferSessionStore(),
            platformDigests(),
            chunkSizeBytes = chunkSize,
            contentPolicy = object : AssetContentPolicy {
                override suspend fun evaluate(context: AssetContentPolicyContext, chunk: ByteArray): AssetContentPolicyDecision =
                    if (context.chunkIndex == 0) AssetContentPolicyDecision.Quarantine("test-quarantined") else AssetContentPolicyDecision.Allow
            },
        )
        val outcome = engine.upload(session, AssetId("quarantined-doc"), 1, octetStream, InMemoryAssetSource(bytes))

        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_QUARANTINED, failed.session.failure)
        assertFalse(Files.exists(uploadDir(base, session.value)), "a quarantined upload's temp chunk files must be cleaned up")
        assertFalse(Files.exists(committedAsset(base, "quarantined-doc", 1)))
    }

    @Test
    fun `a denied download leaves no staged temp file and no file at the final path`() = runTest {
        val base = tempDir("file-provider-content-policy-download")
        val provider = FileAssetProvider(base, platformDigests())
        val chunkSize = 200
        val bytes = patternBytes(chunkSize * 8, seed = 17)

        // Seed a real committed asset on the provider with no policy configured.
        val seedEngine = AssetTransferEngine(provider, InMemoryAssetTransferSessionStore(), platformDigests(), chunkSizeBytes = chunkSize)
        val seeded = seedEngine.upload(sessionId("seed"), AssetId("dl-doc"), 1, octetStream, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(seeded)

        val finalPath = base.resolve("downloaded.bin")
        val sink = FileAssetSink(finalPath)
        val engine = AssetTransferEngine(
            provider,
            InMemoryAssetTransferSessionStore(),
            platformDigests(),
            chunkSizeBytes = chunkSize,
            contentPolicy = DenyingPolicy(denyIndex = 3),
        )
        val outcome = engine.download(sessionId("down-denied"), AssetId("dl-doc"), 1, sink)

        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_DENIED, failed.session.failure)
        assertFalse(Files.exists(finalPath), "a denied download must never promote to its final path")
        assertFalse(Files.exists(sink.temporaryPathForTest()), "a denied download's staged temp file must be deleted (sink.discard())")
    }

    @Test
    fun `without a content policy the same denying scenario would have completed (revert baseline)`() = runTest {
        // Documents the baseline this slice changes: identical setup, no contentPolicy wired,
        // so there is nothing to deny and the transfer completes normally. The real
        // revert-and-observe proof (removing AssetTransferEngine's enforceContentPolicy calls
        // and confirming AssetContentPolicyTest/this class's deny/quarantine cases fail) was run
        // manually against the working tree before this file was committed -- see the PR
        // description -- rather than kept as a permanently-reverted test here.
        val base = tempDir("file-provider-content-policy-baseline")
        val provider = FileAssetProvider(base, platformDigests())
        val chunkSize = 200
        val bytes = patternBytes(chunkSize * 10, seed = 11)
        val engine = AssetTransferEngine(provider, InMemoryAssetTransferSessionStore(), platformDigests(), chunkSizeBytes = chunkSize)

        val outcome = engine.upload(sessionId("s-no-policy"), AssetId("no-policy-doc"), 1, octetStream, InMemoryAssetSource(bytes))

        assertIs<AssetTransferOutcome.Completed>(outcome)
        assertTrue(Files.exists(committedAsset(base, "no-policy-doc", 1)))
    }
}
