@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

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

internal fun appleRetryAdminValidateDirectoryPath(path: String): String {
    require(path.isNotBlank()) {
        "Apple retry-administration directoryPath must not be blank."
    }
    require(path.startsWith('/')) {
        "Apple retry-administration directoryPath must be absolute."
    }
    require('\u0000' !in path) {
        "Apple retry-administration directoryPath must not contain NUL."
    }
    val normalized = path.trimEnd('/')
    require(normalized.isNotEmpty()) {
        "Apple retry-administration directoryPath must not be root."
    }
    require(normalized.split('/').none { it == "." || it == ".." }) {
        "Apple retry-administration directoryPath must not contain dot traversal segments."
    }
    return normalized
}

internal fun appleRetryAdminValidateFileName(fileName: String): String {
    require(fileName.isNotBlank()) {
        "Apple retry-administration fileName must not be blank."
    }
    require('/' !in fileName && '\u0000' !in fileName) {
        "Apple retry-administration fileName must be one safe path component."
    }
    require(fileName != "." && fileName != "..") {
        "Apple retry-administration fileName must not be a dot segment."
    }
    return fileName
}

internal fun appleRetryAdminEnsurePrivateDirectory(path: String) {
    var current = ""
    path.split('/').filter { it.isNotEmpty() }.forEach { component ->
        current += "/$component"
        if (mkdir(current, S_IRWXU.convert()) != 0 && errno != EEXIST) {
            throw AppleRetryAdministrationFileException()
        }
    }
}

internal fun appleRetryAdminOpenOwnerOnly(path: String, flags: Int): Int {
    val descriptor = open(path, flags, S_IRUSR or S_IWUSR)
    if (descriptor < 0) throw AppleRetryAdministrationFileException()
    return descriptor
}

internal fun appleRetryAdminReadUtf8FileOrNull(path: String): String? {
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) {
        if (errno == ENOENT) return null
        throw AppleRetryAdministrationFileException()
    }
    return try {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        val buffer = ByteArray(APPLE_RETRY_ADMIN_FILE_IO_BUFFER_BYTES)
        while (true) {
            val count = buffer.usePinned { pinned ->
                read(descriptor, pinned.addressOf(0), buffer.size.convert())
            }
            when {
                count == 0L -> break
                count < 0L && errno == EINTR -> continue
                count < 0L -> throw AppleRetryAdministrationFileException()
                else -> {
                    totalBytes += count.toInt()
                    if (totalBytes > APPLE_RETRY_ADMIN_MAX_STATE_FILE_BYTES) {
                        throw AppleRetryAdministrationStateLimitException()
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
            throw MalformedAppleRetryAdministrationStateException(invalid)
        }
    } finally {
        close(descriptor)
    }
}

internal fun appleRetryAdminWriteUtf8FileAtomically(
    temporaryPath: String,
    destinationPath: String,
    content: String,
) {
    val bytes = content.encodeToByteArray()
    if (bytes.size > APPLE_RETRY_ADMIN_MAX_STATE_FILE_BYTES) {
        throw AppleRetryAdministrationStateLimitException()
    }
    if (unlink(temporaryPath) != 0 && errno != ENOENT) {
        throw AppleRetryAdministrationFileException()
    }
    val descriptor = appleRetryAdminOpenOwnerOnly(
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
                count <= 0L -> throw AppleRetryAdministrationFileException()
                else -> offset += count.toInt()
            }
        }
        if (fsync(descriptor) != 0) throw AppleRetryAdministrationFileException()
        val closeResult = close(descriptor)
        descriptorOpen = false
        if (closeResult != 0) throw AppleRetryAdministrationFileException()
        if (rename(temporaryPath, destinationPath) != 0) {
            throw AppleRetryAdministrationFileException()
        }
        renamed = true
        appleRetryAdminFsyncDirectory(destinationPath.substringBeforeLast('/'))
    } finally {
        if (descriptorOpen) {
            close(descriptor)
        }
        if (!renamed) {
            unlink(temporaryPath)
        }
    }
}

private fun appleRetryAdminFsyncDirectory(path: String) {
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) throw AppleRetryAdministrationFileException()
    var descriptorOpen = true
    try {
        if (fsync(descriptor) != 0) throw AppleRetryAdministrationFileException()
        val closeResult = close(descriptor)
        descriptorOpen = false
        if (closeResult != 0) throw AppleRetryAdministrationFileException()
    } finally {
        if (descriptorOpen) {
            close(descriptor)
        }
    }
}

internal class AppleRetryAdministrationFileException : Exception()
internal class AppleRetryAdministrationStateLimitException : Exception()
internal class AppleRetryAdministrationRecordLimitException : Exception()
internal class MalformedAppleRetryAdministrationStateException(
    cause: Throwable,
) : Exception(cause)

internal const val APPLE_RETRY_ADMIN_NULL_MARKER: String = "-"
internal const val APPLE_RETRY_ADMIN_HEX_DIGITS: String = "0123456789abcdef"
internal const val APPLE_RETRY_ADMIN_FILE_IO_BUFFER_BYTES: Int = 8 * 1024
internal const val APPLE_RETRY_ADMIN_MAX_STATE_FILE_BYTES: Int = 16 * 1024 * 1024
internal const val APPLE_RETRY_ADMIN_MAX_RECORD_COUNT: Int = 10_000
internal const val APPLE_RETRY_ADMIN_LOCK_RETRY_DELAY_MILLISECONDS: Long = 5L
