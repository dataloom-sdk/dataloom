package io.dataloom.assets.policy

import io.dataloom.api.identifier.AssetId
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetContentPolicyContext
import io.dataloom.assets.AssetContentPolicyDecision
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferEngine
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.assets.octetStream
import io.dataloom.assets.patternBytes
import io.dataloom.assets.platformDigests
import io.dataloom.assets.sessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HashDenyListAssetContentPolicy]: the reference deny-list decides
 * correctly on its own (unit), and -- wired into the real
 * [AssetTransferEngine], not a mock of it -- genuinely blocks a known-bad
 * chunk and lets everything else through end to end, with a configured
 * [DurableAssetContentQuarantineLog] genuinely recording a quarantine match.
 */
class HashDenyListAssetContentPolicyTest {

    private val digests = platformDigests()

    private fun context(chunkIndex: Int = 0) = AssetContentPolicyContext(
        sessionId = AssetTransferSessionId("s"),
        assetId = AssetId("doc"),
        version = 1,
        mediaType = octetStream,
        direction = AssetTransferDirection.UPLOAD,
        chunkIndex = chunkIndex,
    )

    // --------------------------------------------------------- unit: policy alone

    @Test
    fun `a chunk whose digest is not on the deny list is allowed`() = runTest {
        val badDigest = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, byteArrayOf(9, 9, 9)).toHex()
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry(badDigest, AssetContentMatchAction.DENY, "bad")),
        )

        val decision = policy.evaluate(context(), byteArrayOf(1, 2, 3))

        assertEquals(AssetContentPolicyDecision.Allow, decision)
    }

    @Test
    fun `a chunk matching a DENY entry is denied with its reason code`() = runTest {
        val chunk = byteArrayOf(1, 2, 3, 4, 5)
        val digestHex = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, chunk).toHex()
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry(digestHex, AssetContentMatchAction.DENY, "known-malware-sample")),
        )

        val decision = policy.evaluate(context(), chunk)

        val deny = assertIs<AssetContentPolicyDecision.Deny>(decision)
        assertEquals("known-malware-sample", deny.reasonCode)
    }

    @Test
    fun `a chunk matching a QUARANTINE entry is quarantined and recorded durably`() = runTest {
        val chunk = byteArrayOf(5, 4, 3, 2, 1)
        val digestHex = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, chunk).toHex()
        val backing = EncodingQuarantineStateStore()
        val quarantineLog = DurableAssetContentQuarantineLog(backing)
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry(digestHex, AssetContentMatchAction.QUARANTINE, "needs-review")),
            quarantineLog = quarantineLog,
            clock = FixedClock(DataLoomInstant(42_000L)),
        )
        val ctx = context(chunkIndex = 7)

        val decision = policy.evaluate(ctx, chunk)

        val quarantine = assertIs<AssetContentPolicyDecision.Quarantine>(decision)
        assertEquals("needs-review", quarantine.reasonCode)
        assertIs<AssetContentQuarantineRecordOutcome.Recorded>(policy.lastQuarantineRecordOutcome)
        val stored = (quarantineLog.current(ctx.sessionId) as io.dataloom.api.provider.ProviderOperationResult.Success).value
        requireNotNull(stored)
        assertEquals(ctx.sessionId, stored.sessionId)
        assertEquals(ctx.assetId, stored.assetId)
        assertEquals(7, stored.chunkIndex)
        assertEquals(digestHex, stored.matchedDigestHex)
        assertEquals(42_000L, stored.quarantinedAt.epochMilliseconds)
        assertEquals(AssetContentQuarantineStatus.HELD, stored.status)
        // Genuine persistence proof, not just the in-memory object: the real
        // encoded payload is in the backing store's row.
        assertTrue(backing.rows.getValue(ctx.sessionId.value).payload.contains(digestHex))
    }

    @Test
    fun `a DENY match never touches the quarantine log`() = runTest {
        val chunk = byteArrayOf(7, 7, 7)
        val digestHex = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, chunk).toHex()
        val backing = EncodingQuarantineStateStore()
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry(digestHex, AssetContentMatchAction.DENY, "bad")),
            quarantineLog = DurableAssetContentQuarantineLog(backing),
            clock = FixedClock(DataLoomInstant(1L)),
        )

        policy.evaluate(context(), chunk)

        assertEquals(0, backing.rows.size)
        assertNull(policy.lastQuarantineRecordOutcome)
    }

    @Test
    fun `without a configured clock a QUARANTINE policy requires no quarantineLog`() = runTest {
        // Documents that a policy with no durable store configured still decides correctly --
        // the engine still fails the transfer -- it just records nothing durably.
        val chunk = byteArrayOf(1)
        val digestHex = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, chunk).toHex()
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry(digestHex, AssetContentMatchAction.QUARANTINE, "needs-review")),
        )

        val decision = policy.evaluate(context(), chunk)

        assertIs<AssetContentPolicyDecision.Quarantine>(decision)
        assertNull(policy.lastQuarantineRecordOutcome)
    }

    // --------------------------------------------------- integration: real engine

    @Test
    fun `wired into the real engine a deny-listed chunk fails the whole upload closed`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val chunkSize = 1_000
        val bytes = patternBytes(chunkSize * 6, seed = 3)
        val deniedChunk = bytes.copyOfRange(chunkSize * 2, chunkSize * 3)
        val digestHex = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, deniedChunk).toHex()
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry(digestHex, AssetContentMatchAction.DENY, "known-bad")),
        )
        val engine = AssetTransferEngine(
            provider,
            InMemoryAssetTransferSessionStore(),
            digests,
            chunkSizeBytes = chunkSize,
            contentPolicy = policy,
        )

        val outcome = engine.upload(sessionId("deny-e2e"), AssetId("doc-e2e"), 1, octetStream, InMemoryAssetSource(bytes))

        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_DENIED, failed.session.failure)
    }

    @Test
    fun `wired into the real engine good content completes exactly as without a policy`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val chunkSize = 1_000
        val bytes = patternBytes(chunkSize * 6, seed = 4)
        // Deny list configured, but nothing in this asset matches it.
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry("f".repeat(64), AssetContentMatchAction.DENY, "unused")),
        )
        val engine = AssetTransferEngine(
            provider,
            InMemoryAssetTransferSessionStore(),
            digests,
            chunkSizeBytes = chunkSize,
            contentPolicy = policy,
        )

        val up = engine.upload(sessionId("allow-e2e"), AssetId("doc-allow"), 1, octetStream, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(up)

        val sink = InMemoryAssetSink()
        val down = AssetTransferEngine(provider, InMemoryAssetTransferSessionStore(), digests, chunkSizeBytes = chunkSize, contentPolicy = policy)
            .download(sessionId("allow-e2e-down"), AssetId("doc-allow"), null, sink)
        assertIs<AssetTransferOutcome.Completed>(down)
        assertContentEquals(bytes, sink.snapshot())
    }

    @Test
    fun `revert and observe -- without contentPolicy wired in the same deny-listed content completes instead of failing`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val chunkSize = 1_000
        val bytes = patternBytes(chunkSize * 6, seed = 3)
        val deniedChunk = bytes.copyOfRange(chunkSize * 2, chunkSize * 3)
        val digestHex = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, deniedChunk).toHex()
        // Same deny-list fact as the earlier test, but never wired into the engine.
        check(
            HashDenyListAssetContentPolicy(
                digests,
                denyList = listOf(AssetContentDenyListEntry(digestHex, AssetContentMatchAction.DENY, "known-bad")),
            ).evaluate(context(), deniedChunk) is AssetContentPolicyDecision.Deny,
        )
        val engine = AssetTransferEngine(provider, InMemoryAssetTransferSessionStore(), digests, chunkSizeBytes = chunkSize)

        val outcome = engine.upload(sessionId("no-policy-baseline"), AssetId("doc-e2e-baseline"), 1, octetStream, InMemoryAssetSource(bytes))

        // This is the exact behavior difference proving the policy -- not something else --
        // is what blocks the transfer in the test above: identical bytes, no contentPolicy, completes.
        assertIs<AssetTransferOutcome.Completed>(outcome)
    }

    @Test
    fun `wired into the real engine a quarantine-listed chunk fails closed and the quarantine log records it`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val chunkSize = 1_000
        val bytes = patternBytes(chunkSize * 5, seed = 9)
        val heldChunk = bytes.copyOfRange(0, chunkSize)
        val digestHex = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, heldChunk).toHex()
        val backing = EncodingQuarantineStateStore()
        val quarantineLog = DurableAssetContentQuarantineLog(backing)
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry(digestHex, AssetContentMatchAction.QUARANTINE, "needs-review")),
            quarantineLog = quarantineLog,
            clock = FixedClock(DataLoomInstant(7_777L)),
        )
        val sid = sessionId("quarantine-e2e")
        val engine = AssetTransferEngine(provider, InMemoryAssetTransferSessionStore(), digests, chunkSizeBytes = chunkSize, contentPolicy = policy)

        val outcome = engine.upload(sid, AssetId("doc-quarantine"), 1, octetStream, InMemoryAssetSource(bytes))

        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_QUARANTINED, failed.session.failure)
        val stored = (quarantineLog.current(sid) as io.dataloom.api.provider.ProviderOperationResult.Success).value
        requireNotNull(stored)
        assertEquals(AssetId("doc-quarantine"), stored.assetId)
        assertEquals(0, stored.chunkIndex)
        assertEquals(digestHex, stored.matchedDigestHex)
        // A host can later release it, recorded as its own durable evidence.
        val release = quarantineLog.release(sid, AssetContentQuarantineRelease("reviewer", "verified safe", DataLoomInstant(8_000L)))
        assertIs<AssetContentQuarantineReleaseOutcome.Released>(release)
    }
}
