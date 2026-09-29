package io.dataloom.assets.file

import io.dataloom.assets.AssetSink
import io.dataloom.assets.AssetSinkFullException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/**
 * [AssetSink] that stages a download's bytes in a secure temporary file and
 * only [promote]s them to their final location once the caller has verified
 * the transfer completed (FR-ASSET-009).
 *
 * ## Atomic promotion
 *
 * Bytes are written to and read back from a temp file (next to [finalPath] by
 * default, so the eventual rename stays on one filesystem) for the whole
 * life of a download; [finalPath] never exists in a partially-written state,
 * because it is created by exactly one filesystem rename
 * ([FileAssetIo.promoteAtomically]) performed by [promote]. The caller is
 * responsible for calling [promote] only after
 * `AssetTransferEngine.download` has returned
 * `AssetTransferOutcome.Completed` for the session writing to this sink —
 * [AssetSink] itself has no promotion hook (it predates a concrete
 * file-backed implementation), so this is additional API on the concrete
 * class, not an override, and this class does not re-verify digests itself.
 *
 * ## Read semantics for unwritten ranges
 *
 * A real file is naturally sparse: reading a byte range no [write] ever
 * covered does not fail, it silently returns zero bytes. To preserve
 * [AssetSink.read]'s "`-1` once past the available bytes" contract the same
 * way [io.dataloom.assets.memory.InMemoryAssetSink] does for an in-memory
 * buffer — without paying for a per-byte bitmap, which would cost roughly one
 * bookkeeping byte per asset byte and defeat the bounded-memory point of a
 * file-backed sink for a multi-gigabyte asset — this class tracks the
 * written byte *ranges* themselves (merged, non-overlapping), bounded by the
 * number of non-contiguous writes (in practice the chunk count), never by
 * the asset size.
 *
 * ## Cleanup
 *
 * [discard] deletes the temp file and is idempotent;
 * [io.dataloom.assets.AssetTransferEngine] already calls it when a download
 * fails or is cancelled. A sink abandoned without either [promote] or
 * [discard] ever being called (the process died) leaves its temp file on
 * disk; a caller with many such sinks in flight is responsible for its own
 * sweep of [tempDirectory] the way [FileAssetProvider.sweepAbandonedUploads]
 * does for the provider side — this class only guarantees that the temp
 * file, whatever becomes of it, is never mistaken for [finalPath].
 *
 * JVM/Android only (Android consumes this `jvmMain` source set; there is no
 * separate Android target). No Apple implementation in this slice.
 *
 * @param finalPath where a fully verified download is promoted to.
 * @param tempDirectory directory the temporary staging file is created in;
 *   defaults to [finalPath]'s parent so the final rename stays on one
 *   filesystem, which [java.nio.file.StandardCopyOption.ATOMIC_MOVE] requires.
 * @param maxBytes largest total size this sink accepts, mirroring
 *   [io.dataloom.assets.memory.InMemoryAssetSink]; `null` (the default) means
 *   unbounded.
 */
public class FileAssetSink(
    private val finalPath: Path,
    tempDirectory: Path = finalPath.toAbsolutePath().parent,
    private val maxBytes: Long? = null,
) : AssetSink {
    private val mutex = Mutex()
    private val tempPath: Path = FileAssetIo.createSecureTempFile(tempDirectory, "${finalPath.fileName}-", ".part")
    private var file: RandomAccessFile? = null

    // Merged, non-overlapping written byte ranges: start (inclusive) -> end (exclusive).
    private val writtenRanges = TreeMap<Long, Long>()

    override suspend fun reserve(sizeBytes: Long) {
        if (maxBytes != null && sizeBytes > maxBytes) {
            throw AssetSinkFullException("FileAssetSink capacity is smaller than the requested reservation.")
        }
    }

    override suspend fun write(position: Long, source: ByteArray, sourceOffset: Int, length: Int): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (maxBytes != null && position + length > maxBytes) {
                    throw AssetSinkFullException("FileAssetSink capacity exceeded.")
                }
                val handle = open()
                handle.seek(position)
                handle.write(source, sourceOffset, length)
                markWritten(position, position + length)
            }
        }

    override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val available = readableLength(position, length)
                if (available < 0) return@withLock -1
                val handle = open()
                handle.seek(position)
                handle.readFully(destination, destinationOffset, available)
                available
            }
        }

    override suspend fun discard(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeQuietly()
            FileAssetIo.deleteQuietly(tempPath)
            writtenRanges.clear()
        }
    }

    /**
     * Atomically renames the staged temp file onto [finalPath]. Call only
     * once the caller has independently confirmed the transfer completed
     * (this class does not re-verify digests). Safe to call more than once:
     * the first call performs the rename, later calls find the temp file
     * already gone and do nothing.
     */
    public suspend fun promote(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeQuietly()
            if (Files.exists(tempPath)) {
                FileAssetIo.promoteAtomically(tempPath, finalPath)
            }
        }
    }

    /** `true` once [promote] has moved the staged bytes onto [finalPath]. */
    public fun isPromoted(): Boolean = !Files.exists(tempPath) && Files.exists(finalPath)

    /** The temporary staging path, exposed only so a test can assert on filesystem state directly. */
    internal fun temporaryPathForTest(): Path = tempPath

    private fun open(): RandomAccessFile = file ?: RandomAccessFile(tempPath.toFile(), "rw").also { file = it }

    private fun closeQuietly() {
        try {
            file?.close()
        } catch (_: Exception) {
            // Best-effort: promotion/discard must not fail merely because closing the handle did.
        }
        file = null
    }

    private fun markWritten(start: Long, end: Long) {
        var s = start
        var e = end
        val floor = writtenRanges.floorEntry(s)
        if (floor != null && floor.value >= s) {
            s = minOf(s, floor.key)
            e = maxOf(e, floor.value)
            writtenRanges.remove(floor.key)
        }
        val iterator = writtenRanges.tailMap(s).entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key <= e) {
                e = maxOf(e, entry.value)
                iterator.remove()
            } else {
                break
            }
        }
        writtenRanges[s] = e
    }

    /** Bytes readable starting at [position] up to [maxLength], or `-1` if [position] was never written. */
    private fun readableLength(position: Long, maxLength: Int): Int {
        val floor = writtenRanges.floorEntry(position) ?: return -1
        if (floor.value <= position) return -1
        return minOf(maxLength.toLong(), floor.value - position).toInt()
    }
}
