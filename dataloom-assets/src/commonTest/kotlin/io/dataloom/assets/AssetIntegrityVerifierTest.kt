package io.dataloom.assets

import io.dataloom.api.asset.AssetChunkDescriptor
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetIntegrityVerifierTest {

    private val digests = platformDigests()
    private val verifier = AssetIntegrityVerifier(digests, readBufferBytes = 100)

    @Test
    fun `prepared manifest chunk digests equal the one-shot digest of each chunk`() = runTest {
        val asset = testAsset(size = 2_500, chunkSize = 1_024)
        val layout = asset.manifest.chunkLayout
        assertEquals(3, layout.chunkCount)
        for (i in 0 until 3) {
            assertEquals(digests.digest(DigestAlgorithm.SHA_256, asset.chunk(i)), layout.chunks[i].checksum)
        }
    }

    @Test
    fun `prepared manifest whole-object digest equals the one-shot digest of the entire object`() = runTest {
        val asset = testAsset(size = 300_000, chunkSize = 4_096)
        assertEquals(digests.digest(DigestAlgorithm.SHA_256, asset.bytes), asset.manifest.checksum)
        assertEquals(asset.bytes.size.toLong(), asset.manifest.sizeBytes)
    }

    @Test
    fun `the whole-object digest is a digest of the bytes and not of the chunk digests`() = runTest {
        val asset = testAsset(size = 5_000, chunkSize = 1_000)
        val concatenatedChunkDigests = asset.manifest.chunkLayout.chunks
            .fold(ByteArray(0)) { acc, chunk -> acc + chunk.checksum.copyBytes() }
        assertFalse(asset.manifest.checksum == digests.digest(DigestAlgorithm.SHA_256, concatenatedChunkDigests))
    }

    @Test
    fun `preparation honours the requested digest algorithm`() = runTest {
        val bytes = patternBytes(3_000)
        val manifest = verifier.prepareManifest(
            io.dataloom.api.identifier.AssetId("sha512"), 1, octetStream, InMemoryAssetSource(bytes),
            AssetChunkPlan(3_000, 1_000), DigestAlgorithm.SHA_512,
        )
        assertEquals(DigestAlgorithm.SHA_512, manifest.checksum.algorithm)
        assertTrue(manifest.chunkLayout.chunks.all { it.checksum.algorithm == DigestAlgorithm.SHA_512 })
        assertEquals(digests.digest(DigestAlgorithm.SHA_512, bytes), manifest.checksum)
    }

    @Test
    fun `preparation never reads more than one chunk at a time`() = runTest {
        val source = RecordingSource(InMemoryAssetSource(patternBytes(50_000)))
        verifier.prepareManifest(
            io.dataloom.api.identifier.AssetId("bounded"), 1, octetStream, source,
            AssetChunkPlan(50_000, 2_048), DigestAlgorithm.SHA_256,
        )
        assertTrue(source.maxReadLength <= 2_048, "read ${source.maxReadLength} bytes at once")
    }

    @Test
    fun `preparation fails when the source ends before its declared size`() = runTest {
        val short = InMemoryAssetSource(patternBytes(1_500))
        assertFailsWith<AssetSourceChangedException> {
            verifier.prepareManifest(
                io.dataloom.api.identifier.AssetId("short"), 1, octetStream, short,
                AssetChunkPlan(3_000, 1_000), DigestAlgorithm.SHA_256,
            )
        }
    }

    @Test
    fun `verifyChunk accepts exact bytes and rejects any difference`() = runTest {
        val asset = testAsset(size = 2_500, chunkSize = 1_024)
        val descriptor = asset.manifest.chunkLayout.chunks[1]
        assertTrue(verifier.verifyChunk(descriptor, asset.chunk(1)))
        val flipped = asset.chunk(1).also { it[10] = (it[10] + 1).toByte() }
        assertFalse(verifier.verifyChunk(descriptor, flipped))
        assertFalse(verifier.verifyChunk(descriptor, asset.chunk(0)))
    }

    @Test
    fun `verifyChunk rejects a wrong length even when the prefix hashes correctly`() = runTest {
        val asset = testAsset(size = 2_500, chunkSize = 1_024)
        val descriptor = asset.manifest.chunkLayout.chunks[0]
        assertFalse(verifier.verifyChunk(descriptor, asset.chunk(0) + byteArrayOf(0)))
        assertFalse(verifier.verifyChunk(descriptor, asset.chunk(0).copyOf(1_023)))
    }

    @Test
    fun `verifyChunk uses the descriptor's own digest algorithm`() = runTest {
        val bytes = patternBytes(100)
        val descriptor = AssetChunkDescriptor(0, 0, 100, digests.digest(DigestAlgorithm.SHA_512, bytes))
        assertTrue(verifier.verifyChunk(descriptor, bytes))
    }

    @Test
    fun `verifyObject accepts a correct object streamed in bounded pieces`() = runTest {
        val asset = testAsset(size = 20_000, chunkSize = 4_096)
        val sink = RecordingSink(InMemoryAssetSink())
        sink.write(0, asset.bytes, 0, asset.bytes.size)
        assertTrue(verifier.verifyObject(asset.manifest, sink))
        assertTrue(sink.maxReadLength <= 100, "verification read ${sink.maxReadLength} bytes at once")
    }

    @Test
    fun `verifyObject rejects a single flipped bit anywhere`() = runTest {
        val asset = testAsset(size = 5_000, chunkSize = 1_024)
        for (position in listOf(0, 1_023, 1_024, 4_999)) {
            val tampered = asset.bytes.copyOf().also { it[position] = (it[position] + 1).toByte() }
            assertFalse(verifier.verifyObject(asset.manifest, InMemoryAssetSource(tampered)), "position $position")
        }
    }

    @Test
    fun `verifyObject rejects an object that is too short`() = runTest {
        val asset = testAsset(size = 5_000, chunkSize = 1_024)
        assertFalse(verifier.verifyObject(asset.manifest, InMemoryAssetSource(asset.bytes.copyOf(4_999))))
    }

    @Test
    fun `verifyObject ignores bytes past the manifest size`() = runTest {
        val asset = testAsset(size = 5_000, chunkSize = 1_024)
        assertTrue(verifier.verifyObject(asset.manifest, InMemoryAssetSource(asset.bytes + byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `the read buffer must be positive`() {
        assertFailsWith<IllegalArgumentException> { AssetIntegrityVerifier(digests, 0) }
    }
}
