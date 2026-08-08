package io.dataloom.storage.file.internal

/**
 * Platform-specific facade for file-system operations used by
 * [io.dataloom.storage.file.FileStorageProvider].
 *
 * All paths passed to methods of this class are relative to the [baseDir]
 * supplied at construction. Implementations must:
 * - create required directories on first use;
 * - perform writes atomically (write-to-temp, rename);
 * - not expose raw file-system exception messages through their results.
 *
 * Construction performs no I/O. Actual directory setup is deferred to the
 * first operation via [ensureInitialized].
 */
internal expect class FileSystemFacade(baseDir: String) {

    val baseDir: String

    /**
     * Creates [baseDir] and required subdirectories if they do not exist.
     *
     * @throws FileStorageIoException when initialization fails.
     */
    fun ensureInitialized()

    /**
     * Returns `true` when [baseDir] exists and is accessible.
     */
    fun baseDirExists(): Boolean

    /**
     * Returns the text content of the file at [relativePath], or `null` when
     * the file does not exist.
     *
     * @throws FileStorageIoException on unexpected I/O error.
     */
    fun readTextFile(relativePath: String): String?

    /**
     * Writes [content] to [relativePath] atomically (write-temp-then-rename).
     *
     * @throws FileStorageIoException when the write fails.
     */
    fun writeTextFileAtomically(relativePath: String, content: String)

    /**
     * Deletes the file at [relativePath] if it exists. A missing file is
     * treated as a no-op.
     *
     * @throws FileStorageIoException on unexpected I/O error.
     */
    fun deleteFile(relativePath: String)

    /**
     * Moves the file at [fromRelative] to [toRelative] atomically.
     * Parent directory of [toRelative] must already exist.
     *
     * @throws FileStorageIoException when the move fails.
     */
    fun moveFileAtomically(fromRelative: String, toRelative: String)

    /**
     * Returns the file names (not full paths) inside [subdirectory].
     * Returns an empty list when the subdirectory does not exist.
     *
     * @throws FileStorageIoException on unexpected I/O error.
     */
    fun listFileNames(subdirectory: String): List<String>
}

/** Internal exception raised by [FileSystemFacade] on unrecoverable I/O errors. */
internal class FileStorageIoException(message: String) : Exception(message)
