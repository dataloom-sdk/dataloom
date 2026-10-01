package io.dataloom.assets.file

import io.dataloom.assets.AssetSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [AssetSource] reading an existing real file on disk through random access —
 * the Apple/POSIX counterpart of the JVM's `FileAssetSource`
 * (FR-ASSET-009's "real file-backed `AssetSource`... for iOS").
 *
 * A single POSIX file descriptor is opened lazily on first use and kept open
 * for the life of this source; [close] releases it (idempotent). Reads are
 * serialised through a [Mutex] because a shared file descriptor's current
 * offset is mutable state manipulated by `lseek` before every `read` — the
 * transfer engine itself only ever issues one read at a time, but this keeps
 * the type safe for any caller, exactly like the JVM implementation's
 * `RandomAccessFile` mutex.
 *
 * Apple/iOS only (`iosMain`). Compile-verified only; see the module's
 * `iosTest` sources and the PR description for what could not run locally.
 *
 * @param path the absolute POSIX path of the file to read. Must exist for the
 *   life of this source; [sizeBytes] and [read] throw
 *   [AppleFileAssetIoException] if it does not (the transfer engine's
 *   `attempt` wrapper turns that into [io.dataloom.assets.AssetErrorKind.SOURCE_FAILURE]).
 */
public class AppleFileAssetSource(private val path: String) : AssetSource, AutoCloseable {
    private val mutex = Mutex()
    private var descriptor: Int = -1

    override suspend fun sizeBytes(): Long = AppleFileAssetIo.sizeOfPath(path)

    override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
        mutex.withLock {
            val fd = open()
            val size = AppleFileAssetIo.sizeOfDescriptor(fd)
            if (position >= size) return@withLock -1
            val toRead = minOf(length.toLong(), size - position).toInt()
            AppleFileAssetIo.readFully(fd, position, destination, destinationOffset, toRead)
            toRead
        }

    private fun open(): Int {
        if (descriptor < 0) descriptor = AppleFileAssetIo.openReadOnly(path)
        return descriptor
    }

    /** Closes the underlying file descriptor, if one was ever opened. Idempotent. */
    override fun close() {
        if (descriptor >= 0) {
            AppleFileAssetIo.closeDescriptorQuietly(descriptor)
            descriptor = -1
        }
    }
}
