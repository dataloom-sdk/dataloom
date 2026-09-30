package io.dataloom.assets.file

import io.dataloom.api.identifier.AssetId
import io.dataloom.assets.AssetTransferEngine
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.octetStream
import io.dataloom.assets.patternBytes
import io.dataloom.assets.platformDigests
import io.dataloom.assets.sessionId
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FileAssetSource] and [FileAssetSink] exercised through the real
 * filesystem: round trips at chunk boundaries, atomic promotion (nothing
 * visible at the final path until [FileAssetSink.promote] is called, and the
 * bytes are complete once it is), sparse-read semantics, and discard.
 */
class FileAssetSourceSinkTest {

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix)

    @Test
    fun `round trip through real files at and around a chunk boundary`() = runTest {
        val dir = tempDir("file-asset-rt")
        val chunkSize = 64
        val size = chunkSize * 3 + chunkSize / 2 + 1 // a partial last chunk: an actual boundary case
        val bytes = patternBytes(size, seed = 7)
        val sourcePath = dir.resolve("source.bin")
        Files.write(sourcePath, bytes)

        val engine = AssetTransferEngine(
            provider = InMemoryAssetProvider(platformDigests()),
            sessions = InMemoryAssetTransferSessionStore(),
            digests = platformDigests(),
            chunkSizeBytes = chunkSize,
        )

        val source = FileAssetSource(sourcePath)
        val uploadOutcome = engine.upload(sessionId("up-rt"), AssetId("rt-asset"), 1L, octetStream, source)
        assertTrue(uploadOutcome is AssetTransferOutcome.Completed, "upload: $uploadOutcome")
        source.close()

        val finalPath = dir.resolve("downloaded.bin")
        val sink = FileAssetSink(finalPath)
        val downloadOutcome = engine.download(sessionId("down-rt"), AssetId("rt-asset"), 1L, sink)
        assertTrue(downloadOutcome is AssetTransferOutcome.Completed, "download: $downloadOutcome")

        assertFalse(Files.exists(finalPath), "the final path must not exist before promote() is called")
        assertFalse(sink.isPromoted())
        sink.promote()
        assertTrue(sink.isPromoted())
        assertTrue(Files.exists(finalPath))
        assertTrue(bytes.contentEquals(Files.readAllBytes(finalPath)), "downloaded bytes must match the source exactly")
    }

    @Test
    fun `a completed but not yet promoted download leaves nothing visible at the final path`() = runTest {
        val dir = tempDir("file-asset-crash")
        val chunkSize = 32
        val size = chunkSize * 5 // an exact multiple of the chunk size: the other boundary case
        val bytes = patternBytes(size, seed = 3)
        val sourcePath = dir.resolve("source.bin")
        Files.write(sourcePath, bytes)

        val engine = AssetTransferEngine(
            provider = InMemoryAssetProvider(platformDigests()),
            sessions = InMemoryAssetTransferSessionStore(),
            digests = platformDigests(),
            chunkSizeBytes = chunkSize,
        )
        val source = FileAssetSource(sourcePath)
        val uploadOutcome = engine.upload(sessionId("up-crash"), AssetId("crash-asset"), 1L, octetStream, source)
        assertTrue(uploadOutcome is AssetTransferOutcome.Completed)
        source.close()

        val finalPath = dir.resolve("final.bin")
        val sink = FileAssetSink(finalPath)
        val downloadOutcome = engine.download(sessionId("down-crash"), AssetId("crash-asset"), 1L, sink)
        assertTrue(downloadOutcome is AssetTransferOutcome.Completed)

        // Simulate the process dying right after a verified-complete download but before the
        // caller got around to calling promote(): checked directly on the filesystem, the bytes
        // are staged in the temp file, but the final path does not exist yet.
        assertFalse(Files.exists(finalPath), "final path must not exist before promotion")
        assertTrue(Files.exists(sink.temporaryPathForTest()), "staged bytes must survive until promoted")

        // A later call (as a resumed process would make) finishes the promotion atomically.
        sink.promote()
        assertTrue(Files.exists(finalPath))
        assertFalse(Files.exists(sink.temporaryPathForTest()))
        assertTrue(bytes.contentEquals(Files.readAllBytes(finalPath)))
    }

    @Test
    fun `discard deletes the temp file and never leaves anything at the final path`() = runTest {
        val dir = tempDir("file-asset-discard")
        val finalPath = dir.resolve("final.bin")
        val sink = FileAssetSink(finalPath)
        sink.write(0, byteArrayOf(1, 2, 3), 0, 3)
        assertTrue(Files.exists(sink.temporaryPathForTest()))

        sink.discard()

        assertFalse(Files.exists(sink.temporaryPathForTest()))
        assertFalse(Files.exists(finalPath))
    }

    @Test
    fun `sink read returns -1 for a never-written range and the real bytes for a written one`() = runTest {
        val dir = tempDir("file-asset-sparse")
        val sink = FileAssetSink(dir.resolve("final.bin"))
        sink.write(10, byteArrayOf(9, 9, 9), 0, 3)

        val destination = ByteArray(5)
        assertEquals(-1, sink.read(0, destination, 0, 5), "an untouched range must read as end of data, like a real hole")
        val read = sink.read(10, destination, 0, 5)
        assertEquals(3, read)
        assertEquals(9, destination[0])
    }

    @Test
    fun `source reads a real file including a short final read and end of data`() = runTest {
        val dir = tempDir("file-asset-source")
        val bytes = patternBytes(100, seed = 2)
        val path = dir.resolve("s.bin")
        Files.write(path, bytes)

        val source = FileAssetSource(path)
        assertEquals(100L, source.sizeBytes())
        val head = ByteArray(64)
        assertEquals(64, source.read(0, head, 0, 64))
        assertTrue(bytes.copyOfRange(0, 64).contentEquals(head))
        val tail = ByteArray(64)
        assertEquals(36, source.read(64, tail, 0, 64), "a read past the remaining bytes must short-read, not fail")
        assertEquals(-1, source.read(100, tail, 0, 1))
        source.close()
        source.close() // idempotent
    }
}
