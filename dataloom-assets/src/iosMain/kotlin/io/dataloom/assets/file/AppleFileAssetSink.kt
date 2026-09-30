package io.dataloom.assets.file

import io.dataloom.assets.AssetSink
import io.dataloom.assets.AssetSinkFullException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [AssetSink] that stages a download's bytes in a secure temporary file and
 * only [promote]s them to their final location once the caller has verified
 * the transfer completed — the Apple/POSIX counterpart of the JVM's
 * `FileAssetSink` (FR-ASSET-009). See that class's KDoc for the full
 * rationale (atomic promotion, sparse-read semantics, cleanup); this class is
 * behaviourally identical, built on POSIX file descriptors
 * ([AppleFileAssetIo]) instead of `java.nio.file`/`RandomAccessFile`.
 *
 * ## Read semantics for unwritten ranges
 *
 * Exactly like the JVM implementation: this class tracks the written byte
 * *ranges* themselves (merged, non-overlapping) rather than a per-byte
 * bitmap, bounded by the number of non-contiguous writes (in practice the
 * chunk count), never by the asset size. There is no `java.util.TreeMap`
 * equivalent in the Kotlin/Native standard library, so the ranges are kept in
 * a small sorted list and merged with one linear pass per write — still
 * bounded by chunk count, not asset size, matching the memory (and, closely
 * enough for this reference implementation, time) bound the JVM version
 * documents.
 *
 * Apple/iOS only (`iosMain`). Compile-verified only; see the module's
 * `iosTest` sources and the PR description for what could not run locally.
 *
 * @param finalPath the absolute POSIX path a fully verified download is
 *   promoted to.
 * @param tempDirectory directory the temporary staging file is created in;
 *   defaults to [finalPath]'s parent so the final rename stays on one
 *   filesystem, which an atomic `rename(2)` requires.
 * @param maxBytes largest total size this sink accepts, mirroring
 *   [io.dataloom.assets.memory.InMemoryAssetSink]; `null` (the default) means
 *   unbounded.
 */
public class AppleFileAssetSink(
    private val finalPath: String,
    tempDirectory: String = AppleFileAssetIo.parentOf(finalPath),
    private val maxBytes: Long? = null,
) : AssetSink {
    private val mutex = Mutex()
    private val tempPath: String = AppleFileAssetIo.createSecureTempFile(
        tempDirectory,
        "${AppleFileAssetIo.fileNameOf(finalPath)}-",
        ".part",
    )
    private var descriptor: Int = -1

    // Merged, non-overlapping written byte ranges, sorted by start: {start, end (exclusive)}.
    private val writtenRanges = mutableListOf<LongArray>()

    override suspend fun reserve(sizeBytes: Long) {
        if (maxBytes != null && sizeBytes > maxBytes) {
            throw AssetSinkFullException("AppleFileAssetSink capacity is smaller than the requested reservation.")
        }
    }

    override suspend fun write(position: Long, source: ByteArray, sourceOffset: Int, length: Int): Unit =
        mutex.withLock {
            if (maxBytes != null && position + length > maxBytes) {
                throw AssetSinkFullException("AppleFileAssetSink capacity exceeded.")
            }
            val fd = open()
            AppleFileAssetIo.writeFully(fd, position, source, sourceOffset, length)
            markWritten(position, position + length)
        }

    override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
        mutex.withLock {
            val available = readableLength(position, length)
            if (available < 0) return@withLock -1
            val fd = open()
            AppleFileAssetIo.readFully(fd, position, destination, destinationOffset, available)
            available
        }

    override suspend fun discard(): Unit = mutex.withLock {
        closeQuietly()
        AppleFileAssetIo.deleteQuietly(tempPath)
        writtenRanges.clear()
    }

    /**
     * Atomically renames the staged temp file onto [finalPath]. Call only
     * once the caller has independently confirmed the transfer completed
     * (this class does not re-verify digests). Safe to call more than once:
     * the first call performs the rename, later calls find the temp file
     * already gone and do nothing.
     */
    public suspend fun promote(): Unit = mutex.withLock {
        closeQuietly()
        if (AppleFileAssetIo.exists(tempPath)) {
            AppleFileAssetIo.promoteAtomically(tempPath, finalPath)
        }
    }

    /** `true` once [promote] has moved the staged bytes onto [finalPath]. */
    public fun isPromoted(): Boolean = !AppleFileAssetIo.exists(tempPath) && AppleFileAssetIo.exists(finalPath)

    /** The temporary staging path, exposed only so a test can assert on filesystem state directly. */
    internal fun temporaryPathForTest(): String = tempPath

    private fun open(): Int {
        if (descriptor < 0) descriptor = AppleFileAssetIo.openReadWrite(tempPath)
        return descriptor
    }

    private fun closeQuietly() {
        if (descriptor >= 0) {
            AppleFileAssetIo.closeDescriptorQuietly(descriptor)
            descriptor = -1
        }
    }

    private fun markWritten(start: Long, end: Long) {
        val merged = mutableListOf<LongArray>()
        var curStart = start
        var curEnd = end
        var inserted = false
        for (range in writtenRanges) {
            when {
                range[1] < curStart -> merged.add(range)
                range[0] > curEnd -> {
                    if (!inserted) {
                        merged.add(longArrayOf(curStart, curEnd))
                        inserted = true
                    }
                    merged.add(range)
                }
                else -> {
                    curStart = minOf(curStart, range[0])
                    curEnd = maxOf(curEnd, range[1])
                }
            }
        }
        if (!inserted) merged.add(longArrayOf(curStart, curEnd))
        writtenRanges.clear()
        writtenRanges.addAll(merged)
    }

    /** Bytes readable starting at [position] up to [maxLength], or `-1` if [position] was never written. */
    private fun readableLength(position: Long, maxLength: Int): Int {
        for (range in writtenRanges) {
            if (position >= range[0] && position < range[1]) {
                return minOf(maxLength.toLong(), range[1] - position).toInt()
            }
        }
        return -1
    }
}
