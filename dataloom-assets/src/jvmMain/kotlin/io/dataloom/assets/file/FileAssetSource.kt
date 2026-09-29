package io.dataloom.assets.file

import io.dataloom.assets.AssetSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * [AssetSource] reading an existing real file on disk through random access
 * (FR-ASSET-009's "real file-backed `AssetSource`... for Android").
 *
 * A single [RandomAccessFile] handle is opened lazily on first use and kept
 * open for the life of this source; [close] releases it (idempotent). Reads
 * are serialised through a [Mutex] because a [RandomAccessFile]'s file
 * pointer is shared mutable state — the transfer engine itself only ever
 * issues one read at a time, but this keeps the type safe for any caller.
 *
 * JVM/Android only (the module has no separate Android target; Android
 * consumes this `jvmMain` source set). There is no Apple implementation in
 * this slice.
 *
 * @param path the file to read. Must exist for the life of this source;
 *   [sizeBytes] and [read] throw the underlying [java.io.IOException] if it
 *   does not (the transfer engine's `attempt` wrapper turns that into
 *   [io.dataloom.assets.AssetErrorKind.SOURCE_FAILURE]).
 */
public class FileAssetSource(private val path: Path) : AssetSource, AutoCloseable {
    private val mutex = Mutex()
    private var file: RandomAccessFile? = null

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { Files.size(path) }

    override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val handle = open()
                val size = handle.length()
                if (position >= size) return@withLock -1
                handle.seek(position)
                val toRead = minOf(length.toLong(), size - position).toInt()
                handle.readFully(destination, destinationOffset, toRead)
                toRead
            }
        }

    private fun open(): RandomAccessFile = file ?: RandomAccessFile(path.toFile(), "r").also { file = it }

    /** Closes the underlying file handle, if one was ever opened. Idempotent. */
    override fun close() {
        file?.close()
        file = null
    }
}
