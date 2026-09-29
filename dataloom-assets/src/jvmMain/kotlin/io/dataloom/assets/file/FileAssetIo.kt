package io.dataloom.assets.file

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.Comparator

/**
 * Low-level filesystem helpers shared by the file-backed [io.dataloom.assets.AssetSource],
 * [io.dataloom.assets.AssetSink] and [io.dataloom.assets.AssetProvider]
 * implementations (FR-ASSET-009): secure temporary files and atomic
 * promotion into a final location, mirroring the write-to-temp-then-rename
 * idiom already used by `dataloom-storage-file`'s internal `FileSystemFacade`
 * (JVM) and, on Apple, `AppleQueueFileIo`'s `appleQueueWriteUtf8FileAtomically`.
 *
 * JVM/Android only: this object lives in `jvmMain`, which is also the
 * `dataloom-assets` source set Android consumes (the module has no separate
 * Android target). There is no Apple equivalent in this slice; see
 * `docs/adr/ADR-0016-file-backed-asset-transfer-storage.md` for why.
 */
internal object FileAssetIo {

    /**
     * Creates an empty file inside [directory] (created if missing) with a
     * unique name starting with [prefix] and ending with [suffix], restricted
     * to the owner when the filesystem supports POSIX permissions. On a
     * filesystem with no POSIX support (for example NTFS) the platform's
     * default ACL applies instead — `java.nio.file` has no portable stronger
     * guarantee.
     */
    fun createSecureTempFile(directory: Path, prefix: String, suffix: String): Path {
        Files.createDirectories(directory)
        return if (isPosix(directory)) {
            val attribute = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            Files.createTempFile(directory, prefix, suffix, attribute)
        } else {
            Files.createTempFile(directory, prefix, suffix)
        }
    }

    /**
     * Moves [temporary] onto [destination] with one atomic filesystem rename,
     * creating [destination]'s parent directory first if needed. Requires
     * [temporary] and [destination] to share a filesystem (true whenever
     * [temporary] was created under the same base directory as
     * [destination], which every caller in this package does). [destination]
     * is never observable in a partially-written state: either the rename has
     * not happened (the previous content, or nothing, is at [destination]) or
     * it has completed in full.
     *
     * On failure [temporary] is deleted (best-effort) and the exception is
     * rethrown; [destination] is left untouched.
     */
    fun promoteAtomically(temporary: Path, destination: Path) {
        destination.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            deleteQuietly(temporary)
            throw e
        }
    }

    /** Deletes [path] if it exists; swallows any [IOException] (best-effort). */
    fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // Best-effort: a leftover temp file is cleaned up later by a sweep, if one runs.
        }
    }

    /** Recursively deletes [directory] if it exists; best-effort, never throws. */
    fun deleteDirectoryQuietly(directory: Path) {
        if (!Files.exists(directory)) return
        try {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { deleteQuietly(it) }
            }
        } catch (_: IOException) {
            // Best-effort.
        }
    }

    /**
     * A filesystem-safe, collision-free encoding of an arbitrary identifier
     * string (a session or asset id may contain characters a path segment
     * cannot), so it can be used as one path component.
     */
    fun safeName(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())

    fun touch(path: Path, time: FileTime = FileTime.fromMillis(System.currentTimeMillis())) {
        Files.setLastModifiedTime(path, time)
    }

    private fun isPosix(path: Path): Boolean =
        try {
            path.fileSystem.supportedFileAttributeViews().contains("posix")
        } catch (_: Exception) {
            false
        }
}
