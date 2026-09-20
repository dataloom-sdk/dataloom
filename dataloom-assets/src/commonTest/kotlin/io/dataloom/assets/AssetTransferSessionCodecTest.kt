package io.dataloom.assets

import io.dataloom.api.asset.AssetCompressionAlgorithm
import io.dataloom.api.asset.AssetCompressionMetadata
import io.dataloom.api.asset.AssetEncryptionAlgorithm
import io.dataloom.api.asset.AssetEncryptionMetadata
import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.security.KeyReference
import io.dataloom.assets.AssetTransferPhase.CANCELLED
import io.dataloom.assets.AssetTransferPhase.COMPLETED
import io.dataloom.assets.AssetTransferPhase.CREATED
import io.dataloom.assets.AssetTransferPhase.FAILED
import io.dataloom.assets.AssetTransferPhase.TRANSFERRING
import io.dataloom.assets.AssetTransferPhase.VERIFYING
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetTransferSessionCodecTest {

    private val codec = AssetTransferSessionCodec()

    /** Five chunks: 4 x 1024 + 904. */
    private suspend fun manifest(id: String = "doc-1"): AssetManifest = testAsset(id = id, size = 5_000).manifest

    private fun session(
        manifest: AssetManifest,
        phase: AssetTransferPhase,
        committed: Set<Int> = emptySet(),
        failure: AssetErrorKind? = null,
        revision: Long = 3,
        direction: AssetTransferDirection = AssetTransferDirection.UPLOAD,
        id: String = "s-1",
    ) = AssetTransferSession(AssetTransferSessionId(id), direction, manifest, phase, committed, failure, revision)

    private fun assertRoundTrips(session: AssetTransferSession) {
        val payload = codec.encode(session)
        assertEquals(session, codec.decode(payload))
        assertEquals(payload, codec.encode(codec.decode(payload)), "encoding must be deterministic")
    }

    @Test
    fun `every phase round trips`() = runTest {
        val m = manifest()
        val all = setOf(0, 1, 2, 3, 4)
        assertRoundTrips(session(m, CREATED, revision = 0))
        assertRoundTrips(session(m, TRANSFERRING, setOf(0, 2)))
        assertRoundTrips(session(m, TRANSFERRING, all))
        assertRoundTrips(session(m, VERIFYING, all))
        assertRoundTrips(session(m, COMPLETED, all))
        assertRoundTrips(session(m, FAILED, setOf(1), AssetErrorKind.QUOTA_EXCEEDED))
        assertRoundTrips(session(m, CANCELLED, setOf(0)))
    }

    @Test
    fun `every failure kind and both directions round trip`() = runTest {
        val m = manifest()
        for (kind in AssetErrorKind.entries) assertRoundTrips(session(m, FAILED, failure = kind))
        assertRoundTrips(session(m, TRANSFERRING, setOf(4), direction = AssetTransferDirection.DOWNLOAD))
    }

    @Test
    fun `a large revision and awkward session ids round trip`() = runTest {
        val m = manifest()
        assertRoundTrips(session(m, TRANSFERRING, setOf(0), revision = Long.MAX_VALUE))
        for (id in listOf("a|b", "line\nbreak", "tab\tid", "emoji-😀", "colon:semi;comma,", " padded ")) {
            assertRoundTrips(session(m, TRANSFERRING, setOf(0), id = id))
        }
    }

    @Test
    fun `manifest compression and encryption metadata survive the round trip`() = runTest {
        val m = manifest().copy(
            compression = AssetCompressionMetadata(AssetCompressionAlgorithm("deflate"), 5_000),
            encryption = AssetEncryptionMetadata(AssetEncryptionAlgorithm("AES-256-GCM"), KeyReference("key-ref-1"), byteArrayOf(1, 2, 3)),
        )
        assertRoundTrips(session(m, TRANSFERRING, setOf(1, 3)))
    }

    @Test
    fun `a many-chunk session round trips within the bound`() = runTest {
        val big = testAsset(id = "many", size = 4_000, chunkSize = 1).manifest
        assertEquals(4_000, big.chunkLayout.chunkCount)
        val committed = (0 until 4_000 step 2).toSet()
        assertRoundTrips(session(big, TRANSFERRING, committed))
    }

    @Test
    fun `the payload never contains asset bytes`() = runTest {
        val marker = "SECRET-PAYLOAD-MARKER".encodeToByteArray()
        val bytes = ByteArray(3_000) { marker[it % marker.size] }
        val m = AssetIntegrityVerifier(platformDigests()).prepareManifest(
            io.dataloom.api.identifier.AssetId("secret"), 1, octetStream,
            io.dataloom.assets.memory.InMemoryAssetSource(bytes), AssetChunkPlan(3_000, 1_000),
            io.dataloom.api.security.DigestAlgorithm.SHA_256,
        )
        val payload = codec.encode(session(m, TRANSFERRING, setOf(0)))
        val hex = marker.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertFalse("SECRET-PAYLOAD-MARKER" in payload)
        assertFalse(hex in payload)
        // A digest of the content is present (that is the point of a manifest); the content is not.
        assertTrue(m.checksum.toHex() in payload)
    }

    @Test
    fun `the payload size depends on the chunk count and not on the asset size`() = runTest {
        val small = codec.encode(session(testAsset(id = "a", size = 5_000, chunkSize = 1_000).manifest, TRANSFERRING, setOf(0)))
        val huge = codec.encode(session(testAsset(id = "a", size = 500_000, chunkSize = 100_000).manifest, TRANSFERRING, setOf(0)))
        assertTrue(kotlin.math.abs(small.length - huge.length) < 200, "sizes ${small.length} vs ${huge.length}")
    }

    // ------------------------------------------------------- corrupt payloads

    private suspend fun validPayload(): String = codec.encode(session(manifest(), TRANSFERRING, setOf(0, 2)))

    private fun assertRejected(payload: String, why: String) {
        assertFailsWith<IllegalArgumentException>(why) { codec.decode(payload) }
    }

    private fun withField(payload: String, index: Int, value: String): String {
        val lines = payload.split('\n').toMutableList()
        val fields = lines[1].split('|').toMutableList()
        fields[index] = value
        lines[1] = fields.joinToString("|")
        return lines.joinToString("\n")
    }

    @Test
    fun `out-of-order committed indices are rejected`() = runTest {
        assertRejected(withField(validPayload(), 5, "2,0"), "descending")
    }

    @Test
    fun `duplicate committed indices are rejected`() = runTest {
        assertRejected(withField(validPayload(), 5, "0,0,2"), "duplicate")
    }

    @Test
    fun `committed indices outside the manifest layout are rejected`() = runTest {
        assertRejected(withField(validPayload(), 5, "0,5"), "past the end")
        assertRejected(withField(validPayload(), 5, "-1,0"), "negative")
    }

    @Test
    fun `an unparseable committed list is rejected`() = runTest {
        assertRejected(withField(validPayload(), 5, "0,,2"), "empty element")
        assertRejected(withField(validPayload(), 5, "a,b"), "not numbers")
        assertRejected(withField(validPayload(), 5, "0, 2"), "space")
    }

    @Test
    fun `phase and failure inconsistencies are rejected`() = runTest {
        val p = validPayload()
        assertRejected(withField(p, 2, "VERIFYING"), "VERIFYING without every chunk")
        assertRejected(withField(p, 2, "COMPLETED"), "COMPLETED without every chunk")
        assertRejected(withField(p, 2, "FAILED"), "FAILED without a failure kind")
        assertRejected(withField(p, 4, "QUOTA_EXCEEDED"), "failure kind on a TRANSFERRING session")
        assertRejected(withField(p, 4, "NOT_A_KIND"), "unknown failure kind")
        assertRejected(withField(p, 2, "EXPLODED"), "unknown phase")
        assertRejected(withField(p, 1, "SIDEWAYS"), "unknown direction")
    }

    @Test
    fun `bad revision and session id fields are rejected`() = runTest {
        val p = validPayload()
        assertRejected(withField(p, 3, "-1"), "negative revision")
        assertRejected(withField(p, 3, "three"), "non-numeric revision")
        assertRejected(withField(p, 0, ""), "blank session id")
        assertRejected(withField(p, 0, "zz"), "non-hex session id")
        assertRejected(withField(p, 0, "abc"), "odd-length hex")
        assertRejected(withField(p, 0, "ff"), "invalid UTF-8")
    }

    @Test
    fun `header and format version are checked`() = runTest {
        val p = validPayload()
        assertRejected(p.replace("DATALOOM_ASSET_TRANSFER_SESSION", "DATALOOM_SOMETHING_ELSE"), "wrong header")
        assertRejected(p.replaceFirst("\t1\n", "\t2\n"), "future format version")
        assertRejected(p.substringAfter('\n'), "missing header line")
        assertRejected("", "empty")
        assertRejected("garbage", "garbage")
    }

    @Test
    fun `truncated payloads are rejected at every cut point`() = runTest {
        val p = validPayload()
        for (cut in listOf(1, 20, 40, p.length / 3, p.length / 2, p.length - 30, p.length - 1)) {
            assertRejected(p.substring(0, cut), "cut at $cut of ${p.length}")
        }
    }

    @Test
    fun `wrong field counts and an extra or missing manifest are rejected`() = runTest {
        val p = validPayload()
        val lines = p.split('\n')
        assertRejected((listOf(lines[0], lines[1] + "|extra") + lines.drop(2)).joinToString("\n"), "extra field")
        assertRejected((listOf(lines[0], lines[1].substringBeforeLast('|')) + lines.drop(2)).joinToString("\n"), "missing field")
        assertRejected(lines.take(2).joinToString("\n"), "no manifest at all")
        assertRejected((lines + lines.last()).joinToString("\n"), "two manifests")
    }

    @Test
    fun `a tampered embedded manifest is rejected by the manifest's own invariants`() = runTest {
        val p = validPayload()
        val lines = p.split('\n').toMutableList()
        val manifestLine = lines.last()
        val fields = manifestLine.split('|').toMutableList()
        // Field 2 is sizeBytes: no longer equal to the chunk layout's total.
        fields[2] = (fields[2].toLong() + 1).toString()
        lines[lines.size - 1] = fields.joinToString("|")
        assertRejected(lines.joinToString("\n"), "size not matching layout")

        // Digest with the wrong length for its algorithm.
        val lines2 = p.split('\n').toMutableList()
        val f2 = lines2.last().split('|').toMutableList()
        f2[4] = "SHA_256:abcd"
        lines2[lines2.size - 1] = f2.joinToString("|")
        assertRejected(lines2.joinToString("\n"), "short digest")
    }

    @Test
    fun `an oversized payload is rejected up front`() {
        assertFailsWith<IllegalArgumentException> { codec.decode("x".repeat(AssetTransferSessionCodec.MAX_ENCODED_LENGTH + 1)) }
    }

    @Test
    fun `the scope key encoder is the session id`() {
        assertEquals("s-1", DurableAssetTransferSessionStore.KeyEncoder.encode(AssetTransferSessionId("s-1")))
    }
}
