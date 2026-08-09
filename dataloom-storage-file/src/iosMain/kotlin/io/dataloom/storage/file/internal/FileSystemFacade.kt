@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.storage.file.internal

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IRWXU
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.access
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fsync
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.rename
import platform.posix.unlink
import platform.posix.write
import platform.posix.close as posixClose

/**
 * iOS/Kotlin-Native (POSIX) actual implementation of [FileSystemFacade].
 *
 * Uses POSIX `open/read/write/rename/unlink/mkdir/opendir/readdir` for all
 * file operations. All writes use write-to-temp-then-rename for crash
 * durability. No raw errno values or system paths are surfaced in public
 * error messages.
 */
internal actual class FileSystemFacade actual constructor(actual val baseDir: String) {

    actual fun ensureInitialized() {
        ensureSubdir(baseDir)
        ensureSubdir("$baseDir/outbound")
        ensureSubdir("$baseDir/rejected")
        ensureSubdir("$baseDir/inbound")
        ensureSubdir("$baseDir/checkpoint")
    }

    actual fun baseDirExists(): Boolean {
        val dir = opendir(baseDir) ?: return false
        closedir(dir)
        return true
    }

    actual fun readTextFile(relativePath: String): String? {
        val fullPath = "$baseDir/$relativePath"
        val fd = open(fullPath, O_RDONLY)
        if (fd < 0) {
            return if (errno == ENOENT) null
            else throw FileStorageIoException("Could not open file for reading.")
        }
        return try {
            readAllUtf8(fd)
        } finally {
            posixClose(fd)
        }
    }

    actual fun writeTextFileAtomically(relativePath: String, content: String) {
        val target = "$baseDir/$relativePath"
        val tmp = "$target.tmp"
        val bytes = content.encodeToByteArray()

        if (unlink(tmp) != 0 && errno != ENOENT) {
            throw FileStorageIoException("Could not remove stale temp file.")
        }

        val fd = open(tmp, O_WRONLY or O_CREAT or O_EXCL or O_TRUNC, S_IRUSR or S_IWUSR)
        if (fd < 0) throw FileStorageIoException("Could not create temp file for write.")

        var fdOpen = true
        var renamed = false
        try {
            writeAllBytes(fd, bytes)
            if (fsync(fd) != 0) throw FileStorageIoException("fsync failed.")
            val closeResult = posixClose(fd)
            fdOpen = false
            if (closeResult != 0) throw FileStorageIoException("close failed after write.")
            if (rename(tmp, target) != 0) throw FileStorageIoException("Atomic rename failed.")
            renamed = true
            // Best-effort parent directory fsync.
            val parentPath = target.substringBeforeLast('/')
            val parentFd = open(parentPath, O_RDONLY)
            if (parentFd >= 0) {
                fsync(parentFd)
                posixClose(parentFd)
            }
        } finally {
            if (fdOpen) posixClose(fd)
            if (!renamed) unlink(tmp)
        }
    }

    actual fun deleteFile(relativePath: String) {
        val fullPath = "$baseDir/$relativePath"
        if (unlink(fullPath) != 0 && errno != ENOENT) {
            throw FileStorageIoException("Delete failed.")
        }
    }

    actual fun moveFileAtomically(fromRelative: String, toRelative: String) {
        val src = "$baseDir/$fromRelative"
        val dst = "$baseDir/$toRelative"
        if (rename(src, dst) != 0) {
            // Only treat ENOENT as a no-op when the *source* is absent.
            // If the destination parent directory is missing (also ENOENT) we must
            // still throw so the caller does not incorrectly assume the move succeeded.
            if (errno == ENOENT && access(src, F_OK) != 0) return
            throw FileStorageIoException("Move failed.")
        }
    }

    actual fun listFileNames(subdirectory: String): List<String> {
        val fullPath = "$baseDir/$subdirectory"
        val dir = opendir(fullPath) ?: return emptyList()
        val names = mutableListOf<String>()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") {
                    names += name
                }
            }
        } finally {
            closedir(dir)
        }
        return names
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun ensureSubdir(path: String) {
        if (mkdir(path, S_IRWXU.convert()) != 0 && errno != EEXIST) {
            throw FileStorageIoException("Could not create directory.")
        }
    }

    private fun readAllUtf8(fd: Int): String {
        val buffer = ByteArray(FILE_IO_BUFFER_BYTES)
        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val count = buffer.usePinned { pinned ->
                read(fd, pinned.addressOf(0), buffer.size.convert())
            }
            when {
                count == 0L -> break
                count < 0L && errno == EINTR -> continue
                count < 0L -> throw FileStorageIoException("Read error.")
                else -> chunks += buffer.copyOf(count.toInt())
            }
        }
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, destinationOffset = offset)
            offset += chunk.size
        }
        return out.decodeToString()
    }

    private fun writeAllBytes(fd: Int, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = bytes.usePinned { pinned ->
                write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
            }
            when {
                count < 0L && errno == EINTR -> continue
                count <= 0L -> throw FileStorageIoException("Write error.")
                else -> offset += count.toInt()
            }
        }
    }

    private companion object {
        private const val FILE_IO_BUFFER_BYTES = 4096
    }
}
