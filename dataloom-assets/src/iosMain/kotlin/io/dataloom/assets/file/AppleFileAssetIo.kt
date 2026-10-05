@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.assets.file

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IRWXU
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.lseek
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.mkstemps
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.rename
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink
import platform.posix.utimes
import platform.posix.write

/**
 * Low-level POSIX filesystem helpers shared by the Apple file-backed
 * [io.dataloom.assets.AssetSource], [io.dataloom.assets.AssetSink] and
 * [io.dataloom.assets.AssetProvider] implementations (FR-ASSET-009): secure
 * temporary files and atomic promotion into a final location.
 *
 * This is the Apple/POSIX counterpart of the JVM's `FileAssetIo`, mirroring
 * the write-to-temp-then-rename idiom this repository already uses on Apple
 * for a different durable domain — `dataloom-runtime`'s `AppleQueueFileIo`
 * (`open`/`write`/`fsync`/`rename`, `errno`-based error handling, `EINTR`
 * retry loops) — rather than inventing a new one. One deliberate,
 * disclosed strengthening beyond the JVM implementation: [promoteAtomically]
 * `fsync`s the temp file before renaming and the destination directory after,
 * exactly as `AppleQueueFileIo.appleQueueWriteUtf8FileAtomically` does for
 * durability. `java.nio.file.Files.move` has no portable `fsync` hook, so the
 * JVM implementation does not do this; it does not change the observable
 * atomic-visibility contract (a caller never sees a partially written file
 * either way), only the durability of a promotion that already happened by
 * the time the OS reports success.
 *
 * Apple/iOS only (`iosMain`, shared by `iosArm64`, `iosSimulatorArm64` and
 * `iosX64`). Compile-verified only; POSIX calls cannot run on a Windows host,
 * see the module's `iosTest` sources and the PR description for exactly what
 * was and was not verified.
 */
internal object AppleFileAssetIo {

    /** Creates [path] and every missing parent directory (like `mkdir -p`), owner-only. */
    fun ensureDirectory(path: String) {
        var current = ""
        for (component in path.split('/')) {
            if (component.isEmpty()) continue
            current += "/$component"
            if (mkdir(current, S_IRWXU.convert()) != 0 && errno != EEXIST) {
                throw AppleFileAssetIoException("mkdir failed for $current (errno=$errno)")
            }
        }
    }

    /**
     * Creates an empty file inside [directory] (created first if missing) with
     * a unique, collision-free name starting with [prefix] and ending with
     * [suffix], owner-only permissions (mode 0600, `mkstemps`'s POSIX
     * guarantee) — the Apple counterpart of the JVM's
     * `FileAssetIo.createSecureTempFile`. Returns the new file's absolute path;
     * the descriptor `mkstemps` opened is closed immediately, matching the JVM
     * helper's contract of handing back a path a caller opens itself.
     */
    fun createSecureTempFile(directory: String, prefix: String, suffix: String): String {
        ensureDirectory(directory)
        val templatePath = "${directory.trimEnd('/')}/$prefix" + "XXXXXX" + suffix
        val templateBytes = (templatePath + "\u0000").encodeToByteArray()
        val descriptor = templateBytes.usePinned { pinned ->
            mkstemps(pinned.addressOf(0), suffix.length)
        }
        if (descriptor < 0) {
            throw AppleFileAssetIoException("mkstemps failed for $templatePath (errno=$errno)")
        }
        closeDescriptorQuietly(descriptor)
        // mkstemps only rewrites the "XXXXXX" region in place; prefix, suffix and length are unchanged.
        return templateBytes.copyOfRange(0, templatePath.length).decodeToString()
    }

    /**
     * Moves [temporary] onto [destination] with one atomic filesystem rename,
     * creating [destination]'s parent directory first if needed, `fsync`ing
     * the temp file before the rename and the destination directory after
     * (see the class doc for why this is stronger than the JVM helper it
     * mirrors). [destination] is never observable in a partially-written
     * state. On failure [temporary] is deleted (best-effort) and the
     * exception is rethrown; [destination] is left untouched.
     */
    fun promoteAtomically(temporary: String, destination: String) {
        val parent = parentOf(destination)
        if (parent.isNotEmpty()) ensureDirectory(parent)
        val descriptor = open(temporary, O_WRONLY)
        if (descriptor < 0) {
            deleteQuietly(temporary)
            throw AppleFileAssetIoException("open (for fsync) failed for $temporary (errno=$errno)")
        }
        try {
            if (fsync(descriptor) != 0) {
                throw AppleFileAssetIoException("fsync failed for $temporary (errno=$errno)")
            }
        } catch (e: Exception) {
            closeDescriptorQuietly(descriptor)
            deleteQuietly(temporary)
            throw e
        }
        closeDescriptorQuietly(descriptor)
        if (rename(temporary, destination) != 0) {
            val renameErrno = errno
            deleteQuietly(temporary)
            throw AppleFileAssetIoException("rename failed from $temporary to $destination (errno=$renameErrno)")
        }
        fsyncDirectoryQuietly(parent.ifEmpty { "/" })
    }

    /** Deletes [path] if it exists; swallows any failure (best-effort). */
    fun deleteQuietly(path: String) {
        if (unlink(path) != 0 && errno != ENOENT) {
            // Best-effort: a leftover temp file is cleaned up later by a sweep, if one runs.
        }
    }

    /** Recursively deletes [directory] if it exists; best-effort, never throws. */
    fun deleteDirectoryQuietly(directory: String) {
        val handle = opendir(directory) ?: return
        try {
            while (true) {
                val entry = readdir(handle) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val childPath = "$directory/$name"
                if (isDirectoryNoFollow(childPath)) {
                    deleteDirectoryQuietly(childPath)
                } else {
                    deleteQuietly(childPath)
                }
            }
        } finally {
            closedir(handle)
        }
        rmdir(directory)
    }

    /** `true` if [path] exists (any type), `false` otherwise (including on any stat error). */
    fun exists(path: String): Boolean = memScoped {
        val info = alloc<stat>()
        stat(path, info.ptr) == 0
    }

    /** `true` if [path] exists and is a directory (following symlinks). */
    fun isDirectory(path: String): Boolean = memScoped {
        val info = alloc<stat>()
        stat(path, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    private fun isDirectoryNoFollow(path: String): Boolean = memScoped {
        val info = alloc<stat>()
        lstat(path, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    /** Size in bytes of the file at [path]. Throws if it does not exist or is not accessible. */
    fun sizeOfPath(path: String): Long = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) {
            throw AppleFileAssetIoException("stat failed for $path (errno=$errno)")
        }
        info.st_size.convert()
    }

    /**
     * The whole content of the file at [path]. Intended for small, bounded
     * files (the caller checks [sizeOfPath] against its own limit first); it
     * allocates the file's full size.
     */
    fun readAllBytes(path: String): ByteArray {
        val size = sizeOfPath(path)
        val bytes = ByteArray(size.toInt())
        val descriptor = openReadOnly(path)
        try {
            readFully(descriptor, 0L, bytes, 0, bytes.size)
        } finally {
            closeDescriptorQuietly(descriptor)
        }
        return bytes
    }

    /** Size in bytes of the already-open file [descriptor]. */
    fun sizeOfDescriptor(descriptor: Int): Long = memScoped {
        val info = alloc<stat>()
        if (fstat(descriptor, info.ptr) != 0) {
            throw AppleFileAssetIoException("fstat failed (errno=$errno)")
        }
        info.st_size.convert()
    }

    /**
     * The epoch-millisecond last-modified time of [path], from its inode's
     * `mtime`. Used by `sweepAbandonedUploads`, exactly as the JVM
     * implementation uses `Files.getLastModifiedTime`.
     */
    fun lastModifiedEpochMillis(path: String): Long = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) {
            throw AppleFileAssetIoException("stat failed for $path (errno=$errno)")
        }
        info.st_mtimespec.tv_sec.convert<Long>() * 1000L + info.st_mtimespec.tv_nsec.convert<Long>() / 1_000_000L
    }

    /** Sets [path]'s access and modification times to now, like the JVM helper's `touch`. */
    fun touch(path: String) {
        if (utimes(path, null) != 0) {
            throw AppleFileAssetIoException("utimes failed for $path (errno=$errno)")
        }
    }

    /**
     * Absolute paths of the immediate children of [directory] (files and
     * subdirectories, not recursive), or an empty list if [directory] does
     * not exist. The Apple counterpart of `Files.newDirectoryStream`.
     */
    fun listImmediateChildren(directory: String): List<String> {
        val handle = opendir(directory) ?: return emptyList()
        val children = mutableListOf<String>()
        try {
            while (true) {
                val entry = readdir(handle) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                children += "$directory/$name"
            }
        } finally {
            closedir(handle)
        }
        return children
    }

    /**
     * A filesystem-safe, collision-free encoding of an arbitrary identifier
     * string (a session or asset id may contain characters a path segment
     * cannot) as one path component — standard unpadded base64url (RFC 4648
     * section 5), the same alphabet the JVM helper's
     * `Base64.getUrlEncoder().withoutPadding()` uses, so both platforms
     * produce identical names for the same identifier even though neither
     * shares storage with the other.
     */
    fun safeName(value: String): String {
        val bytes = value.encodeToByteArray()
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(BASE64_URL_ALPHABET[(triple shr 18) and 0x3F])
            out.append(BASE64_URL_ALPHABET[(triple shr 12) and 0x3F])
            if (i + 1 < bytes.size) out.append(BASE64_URL_ALPHABET[(triple shr 6) and 0x3F])
            if (i + 2 < bytes.size) out.append(BASE64_URL_ALPHABET[triple and 0x3F])
            i += 3
        }
        return out.toString()
    }

    /** The last path component of [path] (its "file name"). */
    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    /** [path]'s parent directory, or `""` if it has none. */
    fun parentOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

    fun openReadOnly(path: String): Int {
        val descriptor = open(path, O_RDONLY)
        if (descriptor < 0) throw AppleFileAssetIoException("open (read-only) failed for $path (errno=$errno)")
        return descriptor
    }

    fun openReadWrite(path: String): Int {
        val descriptor = open(path, O_RDWR)
        if (descriptor < 0) throw AppleFileAssetIoException("open (read-write) failed for $path (errno=$errno)")
        return descriptor
    }

    /** Closes [descriptor], swallowing any error (best-effort, mirrors [close] usage on failure paths). */
    fun closeDescriptorQuietly(descriptor: Int) {
        close(descriptor)
    }

    /**
     * Reads exactly [length] bytes from [descriptor] at [position] into
     * `destination[destinationOffset until destinationOffset + length]`,
     * looping past short reads and retrying on `EINTR` — the same shape as
     * [platform.posix.read]'s use in `AppleQueueFileIo`. Throws on
     * unexpected end of file (the caller is expected to have already clamped
     * [length] to the file's known size) or any other I/O error.
     */
    fun readFully(descriptor: Int, position: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
        if (length == 0) return
        if (lseek(descriptor, position.convert(), SEEK_SET) < 0) {
            throw AppleFileAssetIoException("lseek failed (errno=$errno)")
        }
        var readSoFar = 0
        while (readSoFar < length) {
            val count = destination.usePinned { pinned ->
                read(descriptor, pinned.addressOf(destinationOffset + readSoFar), (length - readSoFar).convert())
            }
            when {
                count == 0L -> throw AppleFileAssetIoException("unexpected end of file while reading")
                count < 0L && errno == EINTR -> continue
                count < 0L -> throw AppleFileAssetIoException("read failed (errno=$errno)")
                else -> readSoFar += count.toInt()
            }
        }
    }

    /**
     * Writes exactly [length] bytes from `source[sourceOffset until sourceOffset + length]`
     * to [descriptor] at [position], looping past short writes and retrying
     * on `EINTR`.
     */
    fun writeFully(descriptor: Int, position: Long, source: ByteArray, sourceOffset: Int, length: Int) {
        if (length == 0) return
        if (lseek(descriptor, position.convert(), SEEK_SET) < 0) {
            throw AppleFileAssetIoException("lseek failed (errno=$errno)")
        }
        var written = 0
        while (written < length) {
            val count = source.usePinned { pinned ->
                write(descriptor, pinned.addressOf(sourceOffset + written), (length - written).convert())
            }
            when {
                count < 0L && errno == EINTR -> continue
                count <= 0L -> throw AppleFileAssetIoException("write failed (errno=$errno)")
                else -> written += count.toInt()
            }
        }
    }

    private fun fsyncDirectoryQuietly(path: String) {
        val descriptor = open(path, O_RDONLY)
        if (descriptor < 0) return
        try {
            fsync(descriptor)
        } finally {
            closeDescriptorQuietly(descriptor)
        }
    }

    private const val BASE64_URL_ALPHABET: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
}

/** Thrown by [AppleFileAssetIo] on an unexpected POSIX I/O failure. */
internal class AppleFileAssetIoException(message: String) : Exception(message)
