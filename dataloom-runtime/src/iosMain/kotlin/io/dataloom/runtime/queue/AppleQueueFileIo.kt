@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.queue

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.mkdir
import platform.posix.open
import platform.posix.read
import platform.posix.rename
import platform.posix.unlink
import platform.posix.write

internal fun appleQueueValidateDirectoryPath(path: String): String {
    require(path.isNotBlank()) { "Apple queue directoryPath must not be blank." }
    require(path.startsWith('/')) { "Apple queue directoryPath must be absolute." }
    require('\u0000' !in path) { "Apple queue directoryPath must not contain NUL." }
    val normalized = path.trimEnd('/')
    require(normalized.isNotEmpty()) { "Apple queue directoryPath must not be root." }
    require(normalized.split('/').none { it == "." || it == ".." }) {
        "Apple queue directoryPath must not contain dot traversal segments."
    }
    return normalized
}

internal fun appleQueueValidateFileName(fileName: String): String {
    require(fileName.isNotBlank()) { "Apple queue fileName must not be blank." }
    require('/' !in fileName && '\u0000' !in fileName) {
        "Apple queue fileName must be one safe path component."
    }
    require(fileName != "." && fileName != "..") {
        "Apple queue fileName must not be a dot segment."
    }
    return fileName
}

internal fun appleQueueEnsurePrivateDirectory(path: String) {
    var current = ""
    path.split('/').filter { it.isNotEmpty() }.forEach { component ->
        current += "/$component"
        if (mkdir(current, S_IRWXU.convert()) != 0 && errno != EEXIST) {
            throw AppleQueueFileException()
        }
    }
}

internal fun appleQueueOpenOwnerOnly(path: String, flags: Int): Int {
    val descriptor = open(path, flags, S_IRUSR or S_IWUSR)
    if (descriptor < 0) throw AppleQueueFileException()
    return descriptor
}

internal fun appleQueueReadUtf8FileOrNull(path: String): String? {
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) {
        if (errno == ENOENT) return null
        throw AppleQueueFileException()
    }
    return try {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        val buffer = ByteArray(APPLE_QUEUE_FILE_IO_BUFFER_BYTES)
        while (true) {
            val count = buffer.usePinned { pinned ->
                read(descriptor, pinned.addressOf(0), buffer.size.convert())
            }
            when {
                count == 0L -> break
                count < 0L && errno == EINTR -> continue
                count < 0L -> throw AppleQueueFileException()
                else -> {
                    totalBytes += count.toInt()
                    if (totalBytes > APPLE_QUEUE_MAX_STATE_FILE_BYTES) {
                        throw AppleQueueFileLimitException()
                    }
                    chunks += buffer.copyOf(count.toInt())
                }
            }
        }
        val bytes = ByteArray(totalBytes)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, destinationOffset = offset)
            offset += chunk.size
        }
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (invalid: Exception) {
            throw AppleQueueMalformedStateException(invalid)
        }
    } finally {
        close(descriptor)
    }
}

internal fun appleQueueWriteUtf8FileAtomically(
    temporaryPath: String,
    destinationPath: String,
    content: String,
) {
    val bytes = content.encodeToByteArray()
    if (bytes.size > APPLE_QUEUE_MAX_STATE_FILE_BYTES) {
        throw AppleQueueFileLimitException()
    }
    if (unlink(temporaryPath) != 0 && errno != ENOENT) {
        throw AppleQueueFileException()
    }
    val descriptor = appleQueueOpenOwnerOnly(
        temporaryPath,
        O_WRONLY or O_CREAT or O_EXCL or O_TRUNC,
    )
    var descriptorOpen = true
    var renamed = false
    try {
        var offset = 0
        while (offset < bytes.size) {
            val count = bytes.usePinned { pinned ->
                write(
                    descriptor,
                    pinned.addressOf(offset),
                    (bytes.size - offset).convert(),
                )
            }
            when {
                count < 0L && errno == EINTR -> continue
                count <= 0L -> throw AppleQueueFileException()
                else -> offset += count.toInt()
            }
        }
        if (fsync(descriptor) != 0) throw AppleQueueFileException()
        val closeResult = close(descriptor)
        descriptorOpen = false
        if (closeResult != 0) throw AppleQueueFileException()
        if (rename(temporaryPath, destinationPath) != 0) {
            throw AppleQueueFileException()
        }
        renamed = true
        appleQueueFsyncDirectory(destinationPath.substringBeforeLast('/'))
    } finally {
        if (descriptorOpen) {
            close(descriptor)
        }
        if (!renamed) {
            unlink(temporaryPath)
        }
    }
}

private fun appleQueueFsyncDirectory(path: String) {
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) throw AppleQueueFileException()
    var descriptorOpen = true
    try {
        if (fsync(descriptor) != 0) throw AppleQueueFileException()
        val closeResult = close(descriptor)
        descriptorOpen = false
        if (closeResult != 0) throw AppleQueueFileException()
    } finally {
        if (descriptorOpen) {
            close(descriptor)
        }
    }
}

internal class AppleQueueFileException : Exception()
internal class AppleQueueFileLimitException : Exception()
internal class AppleQueueEntryLimitException : Exception()
internal class AppleQueueMalformedStateException(cause: Throwable) : Exception(cause)

internal const val APPLE_QUEUE_NULL_MARKER: String = "-"
internal const val APPLE_QUEUE_HEX_DIGITS: String = "0123456789abcdef"
internal const val APPLE_QUEUE_MAX_STATE_FILE_BYTES: Int = 32 * 1024 * 1024
internal const val APPLE_QUEUE_MAX_ENTRY_COUNT: Int = 10_000
internal const val APPLE_QUEUE_FILE_IO_BUFFER_BYTES: Int = 16 * 1024
internal const val APPLE_QUEUE_LOCK_RETRY_DELAY_MILLISECONDS: Long = 5L
