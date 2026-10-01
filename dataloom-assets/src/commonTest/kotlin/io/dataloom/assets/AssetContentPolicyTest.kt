package io.dataloom.assets

import io.dataloom.api.identifier.AssetId
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [AssetContentPolicy] wired into [AssetTransferEngine] (FR-ASSET-012):
 * allow lets a transfer complete exactly as if no policy were configured,
 * and deny/quarantine fail it closed, abort the provider-side upload or
 * discard the download sink, and never commit or expose the refused
 * content.
 */
class AssetContentPolicyTest {

    private val digests = platformDigests()
    private val chunkSize = 1_024

    private fun engine(
        provider: AssetProvider,
        store: AssetTransferSessionStore,
        contentPolicy: AssetContentPolicy? = null,
    ) = AssetTransferEngine(
        provider,
        store,
        digests,
        chunkSizeBytes = chunkSize,
        contentPolicy = contentPolicy,
    )

    /** Allows everything but records every context it was asked about, in order. */
    private class RecordingPolicy(
        private val decide: (AssetContentPolicyContext) -> AssetContentPolicyDecision = { AssetContentPolicyDecision.Allow },
    ) : AssetContentPolicy {
        val seen = mutableListOf<AssetContentPolicyContext>()

        override suspend fun evaluate(context: AssetContentPolicyContext, chunk: ByteArray): AssetContentPolicyDecision {
            seen += context
            return decide(context)
        }
    }

    // ------------------------------------------------------------------ allow

    @Test
    fun `an allow-all policy lets upload then download complete exactly as without one`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val bytes = patternBytes(10_000)
        val policy = RecordingPolicy()

        val up = engine(provider, InMemoryAssetTransferSessionStore(), policy)
            .upload(sessionId("up"), AssetId("doc"), 1, octetStream, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(up)
        assertEquals(10, policy.seen.count { it.direction == AssetTransferDirection.UPLOAD })

        val sink = InMemoryAssetSink()
        val down = engine(provider, InMemoryAssetTransferSessionStore(), policy)
            .download(sessionId("down"), AssetId("doc"), null, sink)
        assertIs<AssetTransferOutcome.Completed>(down)
        assertContentEquals(bytes, sink.snapshot())
        assertEquals(10, policy.seen.count { it.direction == AssetTransferDirection.DOWNLOAD })
    }

    @Test
    fun `the policy is given the chunk's logical bytes and correct context`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val asset = testAsset(id = "ctx", size = 3_000, chunkSize = chunkSize)
        val policy = RecordingPolicy()

        val up = engine(provider, InMemoryAssetTransferSessionStore(), policy)
            .upload(sessionId("up"), asset.assetId, asset.version, octetStream, InMemoryAssetSource(asset.bytes))
        assertIs<AssetTransferOutcome.Completed>(up)

        assertEquals((0 until asset.manifest.chunkLayout.chunkCount).toList(), policy.seen.map { it.chunkIndex })
        for (ctx in policy.seen) {
            assertEquals(asset.assetId, ctx.assetId)
            assertEquals(asset.version, ctx.version)
            assertEquals(octetStream, ctx.mediaType)
            assertEquals(AssetTransferDirection.UPLOAD, ctx.direction)
        }
    }

    // ------------------------------------------------------------------- deny

    @Test
    fun `denying an upload chunk fails closed and aborts the provider session`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val bytes = patternBytes(10_000)
        val policy = RecordingPolicy { ctx -> if (ctx.chunkIndex == 4) AssetContentPolicyDecision.Deny("malware") else AssetContentPolicyDecision.Allow }

        val outcome = engine(provider, InMemoryAssetTransferSessionStore(), policy)
            .upload(sessionId("u"), AssetId("a"), 1, octetStream, InMemoryAssetSource(bytes))

        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_DENIED, failed.session.failure)
        assertEquals(AssetErrorKind.CONTENT_POLICY_DENIED, failed.error.kind)
        assertEquals(1, provider.abortCalls, "a denied upload must abort the provider-side session")
        assertTrue(4 !in provider.uploadsAccepted, "the denied chunk itself is never sent to the provider")
        assertTrue(5 !in provider.uploadAttempts, "no chunk after the denial is even attempted")
    }

    @Test
    fun `quarantining an upload chunk fails closed with its own typed outcome`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val bytes = patternBytes(5_000)
        val policy = RecordingPolicy { ctx -> if (ctx.chunkIndex == 1) AssetContentPolicyDecision.Quarantine("needs-review") else AssetContentPolicyDecision.Allow }

        val outcome = engine(provider, InMemoryAssetTransferSessionStore(), policy)
            .upload(sessionId("u"), AssetId("a"), 1, octetStream, InMemoryAssetSource(bytes))

        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_QUARANTINED, failed.session.failure)
        assertEquals(1, provider.abortCalls, "a quarantined upload must abort the provider-side session exactly like a deny")
    }

    @Test
    fun `denying a download chunk fails closed and discards the sink`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val bytes = patternBytes(8_000)
        val upOutcome = engine(provider, InMemoryAssetTransferSessionStore())
            .upload(sessionId("seed"), AssetId("a"), 1, octetStream, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(upOutcome)

        val sink = RecordingSink(InMemoryAssetSink())
        val policy = RecordingPolicy { ctx -> if (ctx.chunkIndex == 3) AssetContentPolicyDecision.Deny("blocked") else AssetContentPolicyDecision.Allow }
        val outcome = engine(provider, InMemoryAssetTransferSessionStore(), policy)
            .download(sessionId("d"), AssetId("a"), 1, sink)

        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_DENIED, failed.session.failure)
        assertEquals(1, sink.discards, "a denied download must discard its sink")
        assertEquals(3, sink.writePositions.size, "only the chunks before the denied one (0,1,2) were ever written")
    }

    @Test
    fun `an upload denied on resume still aborts even though earlier chunks were already committed`() = runTest {
        // First attempt: no policy, three chunks committed, then a transient provider outage.
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(10_000)
        provider.interceptUploadChunk = { if (it.index == 3) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        val first = engine(provider, store).upload(sessionId("u"), AssetId("a"), 1, octetStream, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(setOf(0, 1, 2), first.session.committedChunks)

        // Resume with a policy that denies the very next chunk.
        provider.interceptUploadChunk = { null }
        val policy = RecordingPolicy { ctx -> if (ctx.chunkIndex == 3) AssetContentPolicyDecision.Deny("late-deny") else AssetContentPolicyDecision.Allow }
        val second = engine(provider, store, policy).upload(sessionId("u"), AssetId("a"), 1, octetStream, InMemoryAssetSource(bytes))

        assertIs<AssetTransferOutcome.Failed>(second)
        // abortUpload removes every chunk the provider ever committed for this session, not just the newly denied one.
        assertEquals(1, provider.abortCalls)
    }
}
