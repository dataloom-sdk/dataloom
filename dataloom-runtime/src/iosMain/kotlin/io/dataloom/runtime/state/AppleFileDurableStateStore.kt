@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.state

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import platform.posix.EAGAIN
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.EWOULDBLOCK
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.flock
import platform.posix.fsync
import platform.posix.mkdir
import platform.posix.open
import platform.posix.read
import platform.posix.rename
import platform.posix.unlink
import platform.posix.write

/**
 * Production Apple file-backed implementation of [DurableStateStore], generic
 * over any [TScope]/[TState] a domain supplies encoding for via
 * [scopeKeyEncoder] and [codec] — the iOS counterpart to
 * [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore],
 * closing `#93`'s "an Apple file-backed `DurableStateStore` implementation
 * (only Room exists so far)" gap.
 *
 * Unlike [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore],
 * which lets multiple domains share one Room database via a `namespace`
 * column, this store follows this repository's existing Apple file-backed
 * convention instead — one dedicated file per domain (the same shape
 * [AppleFileQueueProvider][io.dataloom.runtime.queue.AppleFileQueueProvider]
 * and
 * [AppleFileCircuitBreakerStateStore][io.dataloom.runtime.retry.AppleFileCircuitBreakerStateStore]
 * already use). A domain adopting this store simply gives it its own
 * `fileName`; nothing here needs a namespace concept.
 *
 * The caller supplies an absolute, application-private directory path,
 * normally beneath Application Support. The store creates that directory
 * lazily with owner-only permissions and never stores anything beyond what
 * [codec] itself chooses to encode — it is the domain's responsibility to
 * keep that encoding free of credentials, tokens, or other sensitive values,
 * the same expectation [DurableStateCodec] documents.
 *
 * A process-shared advisory file lock protects every load and compare-and-set.
 * Successful writes are fsynced to a temporary file, atomically renamed over
 * the prior snapshot, and followed by a parent-directory fsync — the exact
 * mechanism
 * [AppleFileCircuitBreakerStateStore][io.dataloom.runtime.retry.AppleFileCircuitBreakerStateStore]
 * already proved reliable, duplicated here rather than shared across domains
 * (this repository's own [DurableStateStore][io.dataloom.api.state.DurableStateStore]
 * KDoc explicitly keeps `CircuitBreakerStateStore` as its own independent
 * implementation rather than retrofitting it, for the same "don't destabilize
 * working, tested persistence" reason this store's independence follows).
 *
 * Lock acquisition is non-blocking and cancellation-aware. Once the bounded
 * file operation begins, POSIX read/write syscalls are synchronous; the
 * on-disk snapshot is capped at 4 MiB, and any single record's encoded
 * payload is capped at 4 MiB, to keep that non-suspending section bounded.
 */
public class AppleFileDurableStateStore<TScope : Any, TState : Any>(
    directoryPath: String,
    fileName: String = DEFAULT_FILE_NAME,
    private val scopeKeyEncoder: DurableStateScopeKeyEncoder<TScope>,
    private val codec: DurableStateCodec<TState>,
) : DurableStateStore<TScope, TState> {
    private val boundary = AppleDurableStateFileBoundary(directoryPath, fileName)

    override suspend fun load(
        scope: TScope,
    ): ProviderOperationResult<DurableStateLoadResult<TState>> = protect {
        withExclusiveLock {
            val entry = boundary.readSnapshot()[scopeKeyEncoder.encode(scope)]
            if (entry == null) {
                DurableStateLoadResult.Missing
            } else {
                DurableStateLoadResult.Found(entry.toRecord())
            }
        }
    }

    override suspend fun compareAndSet(
        request: DurableStateCompareAndSetRequest<TScope, TState>,
    ): ProviderOperationResult<DurableStateCompareAndSetResult<TState>> {
        if (request.expectedVersion == Long.MAX_VALUE) {
            return ProviderOperationResult.Failure(AppleDurableStateStoreError.versionExhausted())
        }
        return protect {
            // Encoded before the lock is ever acquired: an encode/oversized-payload
            // failure must fail closed without touching the file, matching
            // RoomDurableStateStore's identical "without reaching Room" discipline.
            val payload = encodePayload(request.nextState)
            withExclusiveLock {
                val snapshot = boundary.readSnapshot()
                val key = scopeKeyEncoder.encode(request.scope)
                val current = snapshot[key]
                val matches = when (val expected = request.expectedVersion) {
                    null -> current == null
                    else -> current?.version == expected
                }
                if (!matches) {
                    DurableStateCompareAndSetResult.Conflict(current?.toRecord())
                } else {
                    val nextVersion = request.expectedVersion?.plus(1L) ?: 0L
                    val nextEntry = AppleDurableStateEntry(
                        payload = payload,
                        schemaVersion = request.nextSchemaVersion,
                        version = nextVersion,
                    )
                    val nextSnapshot = snapshot.toMutableMap()
                    nextSnapshot[key] = nextEntry
                    currentCoroutineContext().ensureActive()
                    boundary.writeSnapshot(nextSnapshot)
                    DurableStateCompareAndSetResult.Updated(nextEntry.toRecord())
                }
            }
        }
    }

    private fun encodePayload(state: TState): String {
        val payload = try {
            codec.encode(state)
        } catch (invalid: Exception) {
            throw AppleDurableStateEncodeFailureException(invalid)
        }
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw AppleDurableStatePayloadTooLargeException()
        }
        return payload
    }

    private fun AppleDurableStateEntry.toRecord(): DurableStateRecord<TState> = try {
        DurableStateRecord(state = codec.decode(payload), version = version, schemaVersion = schemaVersion)
    } catch (invalid: Exception) {
        throw MalformedAppleDurableStateException(invalid)
    }

    private suspend fun <T> protect(block: suspend () -> T): ProviderOperationResult<T> = try {
        currentCoroutineContext().ensureActive()
        ProviderOperationResult.Success(block())
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
    } catch (_: AppleDurableStatePayloadTooLargeException) {
        ProviderOperationResult.Failure(AppleDurableStateStoreError.payloadTooLarge())
    } catch (_: AppleDurableStateEncodeFailureException) {
        ProviderOperationResult.Failure(AppleDurableStateStoreError.encodeFailure())
    } catch (_: MalformedAppleDurableStateException) {
        ProviderOperationResult.Failure(AppleDurableStateStoreError.integrityFailure())
    } catch (_: AppleDurableStateFileLimitException) {
        ProviderOperationResult.Failure(AppleDurableStateStoreError.stateLimitExceeded())
    } catch (_: AppleDurableStateFileException) {
        ProviderOperationResult.Failure(AppleDurableStateStoreError.fileFailure())
    } catch (_: Exception) {
        ProviderOperationResult.Failure(AppleDurableStateStoreError.fileFailure())
    }

    private suspend fun <T> withExclusiveLock(block: suspend () -> T): T = boundary.withExclusiveLock(block)

    public companion object {
        public const val DEFAULT_FILE_NAME: String = "dataloom-durable-state-v1.tsv"
    }
}

/** One persisted scope's payload, schema version, and compare-and-set version. */
internal data class AppleDurableStateEntry(
    val payload: String,
    val schemaVersion: Int,
    val version: Long,
)

/** Shared lock and atomic-snapshot boundary for one [AppleFileDurableStateStore] file. */
internal class AppleDurableStateFileBoundary(
    directoryPath: String,
    fileName: String,
) {
    private val normalizedDirectoryPath: String = validateDurableStateDirectoryPath(directoryPath)
    private val validatedFileName: String = validateDurableStateFileName(fileName)
    private val dataFilePath: String = "$normalizedDirectoryPath/$validatedFileName"
    private val lockFilePath: String = "$dataFilePath.lock"
    private val temporaryFilePath: String = "$dataFilePath.tmp"

    suspend fun <T> withExclusiveLock(block: suspend () -> T): T {
        ensurePrivateDurableStateDirectory(normalizedDirectoryPath)
        val descriptor = openDurableStateFileOwnerOnly(lockFilePath, O_RDWR or O_CREAT)
        try {
            acquireExclusiveLock(descriptor)
            return try {
                currentCoroutineContext().ensureActive()
                block()
            } finally {
                flock(descriptor, LOCK_UN)
            }
        } finally {
            close(descriptor)
        }
    }

    fun readSnapshot(): Map<String, AppleDurableStateEntry> {
        val content = readDurableStateUtf8FileOrNull(dataFilePath) ?: return emptyMap()
        return AppleDurableStateFileCodec.decodeSnapshot(content)
    }

    fun writeSnapshot(snapshot: Map<String, AppleDurableStateEntry>) {
        writeDurableStateUtf8FileAtomically(
            temporaryPath = temporaryFilePath,
            destinationPath = dataFilePath,
            content = AppleDurableStateFileCodec.encodeSnapshot(snapshot),
        )
    }

    private suspend fun acquireExclusiveLock(descriptor: Int) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (flock(descriptor, LOCK_EX or LOCK_NB) == 0) return
            when (errno) {
                EINTR -> Unit
                EAGAIN, EWOULDBLOCK -> delay(DURABLE_STATE_LOCK_RETRY_DELAY_MILLISECONDS)
                else -> throw AppleDurableStateFileException()
            }
        }
    }
}

internal object AppleDurableStateFileCodec {
    private const val HEADER = "DATALOOM_DURABLE_STATE\t1"
    private const val FIELD_COUNT = 4

    fun encodeSnapshot(snapshot: Map<String, AppleDurableStateEntry>): String {
        val content = buildString {
            append(HEADER)
            append('\n')
            snapshot.entries.sortedBy { it.key }.forEach { (key, entry) ->
                append(hexEncodeDurableState(key))
                append('\t')
                append(entry.schemaVersion.toString())
                append('\t')
                append(entry.version.toString())
                append('\t')
                append(hexEncodeDurableState(entry.payload))
                append('\n')
            }
        }
        if (content.encodeToByteArray().size > MAX_STATE_FILE_BYTES) {
            throw AppleDurableStateFileLimitException()
        }
        return content
    }

    fun decodeSnapshot(content: String): Map<String, AppleDurableStateEntry> {
        if (content.encodeToByteArray().size > MAX_STATE_FILE_BYTES) {
            throw AppleDurableStateFileLimitException()
        }
        return try {
            val lines = content.split('\n')
            require(lines.isNotEmpty())
            require(lines.first() == HEADER) { "Unsupported Apple durable-state format." }
            val snapshot = linkedMapOf<String, AppleDurableStateEntry>()
            for (index in 1 until lines.size) {
                val line = lines[index]
                if (line.isEmpty()) {
                    require(index == lines.lastIndex)
                    continue
                }
                val fields = line.split('\t')
                require(fields.size == FIELD_COUNT)
                val key = hexDecodeDurableState(fields[0])
                val entry = AppleDurableStateEntry(
                    schemaVersion = fields[1].toIntStrictDurableState(),
                    version = fields[2].toLongStrictDurableState(),
                    payload = hexDecodeDurableState(fields[3]),
                )
                require(snapshot.put(key, entry) == null)
            }
            snapshot
        } catch (invalid: AppleDurableStateFileLimitException) {
            throw invalid
        } catch (invalid: Exception) {
            throw MalformedAppleDurableStateException(invalid)
        }
    }

    private fun String.toIntStrictDurableState(): Int {
        require(isNotEmpty())
        return toIntOrNull() ?: error("Invalid integer value.")
    }

    private fun String.toLongStrictDurableState(): Long {
        require(isNotEmpty())
        return toLongOrNull() ?: error("Invalid long value.")
    }
}

private fun hexEncodeDurableState(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        append(DURABLE_STATE_HEX_DIGITS[unsigned ushr 4])
        append(DURABLE_STATE_HEX_DIGITS[unsigned and 0x0f])
    }
}

private fun hexDecodeDurableState(value: String): String {
    require(value.length % 2 == 0)
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        val offset = index * 2
        val high = hexValueDurableState(value[offset])
        val low = hexValueDurableState(value[offset + 1])
        require(high >= 0 && low >= 0)
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

private fun hexValueDurableState(value: Char): Int = when (value) {
    in '0'..'9' -> value - '0'
    in 'a'..'f' -> value - 'a' + 10
    in 'A'..'F' -> value - 'A' + 10
    else -> -1
}

private fun validateDurableStateDirectoryPath(path: String): String {
    require(path.isNotBlank()) { "Apple durable-state directoryPath must not be blank." }
    require(path.startsWith('/')) { "Apple durable-state directoryPath must be absolute." }
    require(DURABLE_STATE_NUL_CHARACTER !in path) { "Apple durable-state directoryPath must not contain NUL." }
    val normalized = path.trimEnd('/')
    require(normalized.isNotEmpty()) { "Apple durable-state directoryPath must not be root." }
    require(normalized.split('/').none { it == "." || it == ".." }) {
        "Apple durable-state directoryPath must not contain dot traversal segments."
    }
    return normalized
}

private fun validateDurableStateFileName(fileName: String): String {
    require(fileName.isNotBlank()) { "Apple durable-state fileName must not be blank." }
    require('/' !in fileName && DURABLE_STATE_NUL_CHARACTER !in fileName) {
        "Apple durable-state fileName must be one safe path component."
    }
    require(fileName != "." && fileName != "..") {
        "Apple durable-state fileName must not be a dot segment."
    }
    return fileName
}

private fun ensurePrivateDurableStateDirectory(path: String) {
    var current = ""
    path.split('/').filter { it.isNotEmpty() }.forEach { component ->
        current += "/$component"
        if (mkdir(current, S_IRWXU.convert()) != 0 && errno != EEXIST) {
            throw AppleDurableStateFileException()
        }
    }
}

private fun openDurableStateFileOwnerOnly(path: String, flags: Int): Int {
    val descriptor = open(path, flags, S_IRUSR or S_IWUSR)
    if (descriptor < 0) throw AppleDurableStateFileException()
    return descriptor
}

internal fun readDurableStateUtf8FileOrNull(path: String): String? {
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) {
        if (errno == ENOENT) return null
        throw AppleDurableStateFileException()
    }
    return try {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        val buffer = ByteArray(DURABLE_STATE_FILE_IO_BUFFER_BYTES)
        while (true) {
            val count = buffer.usePinned { pinned ->
                read(descriptor, pinned.addressOf(0), buffer.size.convert())
            }
            when {
                count == 0L -> break
                count < 0L && errno == EINTR -> continue
                count < 0L -> throw AppleDurableStateFileException()
                else -> {
                    totalBytes += count.toInt()
                    if (totalBytes > MAX_STATE_FILE_BYTES) {
                        throw AppleDurableStateFileLimitException()
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
            throw MalformedAppleDurableStateException(invalid)
        }
    } finally {
        close(descriptor)
    }
}

internal fun writeDurableStateUtf8FileAtomically(
    temporaryPath: String,
    destinationPath: String,
    content: String,
) {
    val bytes = content.encodeToByteArray()
    if (bytes.size > MAX_STATE_FILE_BYTES) throw AppleDurableStateFileLimitException()
    if (unlink(temporaryPath) != 0 && errno != ENOENT) {
        throw AppleDurableStateFileException()
    }
    val descriptor = openDurableStateFileOwnerOnly(
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
                count <= 0L -> throw AppleDurableStateFileException()
                else -> offset += count.toInt()
            }
        }
        if (fsync(descriptor) != 0) throw AppleDurableStateFileException()
        val closeResult = close(descriptor)
        descriptorOpen = false
        if (closeResult != 0) throw AppleDurableStateFileException()
        if (rename(temporaryPath, destinationPath) != 0) {
            throw AppleDurableStateFileException()
        }
        renamed = true
        fsyncDurableStateDirectory(destinationPath.substringBeforeLast('/'))
    } finally {
        if (descriptorOpen) {
            close(descriptor)
        }
        if (!renamed) {
            unlink(temporaryPath)
        }
    }
}

private fun fsyncDurableStateDirectory(path: String) {
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) throw AppleDurableStateFileException()
    var descriptorOpen = true
    try {
        if (fsync(descriptor) != 0) throw AppleDurableStateFileException()
        val closeResult = close(descriptor)
        descriptorOpen = false
        if (closeResult != 0) throw AppleDurableStateFileException()
    } finally {
        if (descriptorOpen) {
            close(descriptor)
        }
    }
}

private object AppleDurableStateStoreError {
    fun fileFailure(): DataLoomError = error(
        code = "DURABLE_STATE_APPLE_FILE_IO_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "An Apple durable-state file operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "DURABLE_STATE_APPLE_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple durable state failed integrity validation.",
    )

    fun encodeFailure(): DataLoomError = error(
        code = "DURABLE_STATE_APPLE_ENCODE_FAILURE",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The next durable-state value could not be encoded.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "DURABLE_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The durable-state record version is exhausted.",
    )

    fun payloadTooLarge(): DataLoomError = error(
        code = "DURABLE_STATE_APPLE_PAYLOAD_TOO_LARGE",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Encoded durable-state payload exceeds the bounded limit.",
    )

    fun stateLimitExceeded(): DataLoomError = error(
        code = "DURABLE_STATE_APPLE_FILE_LIMIT_EXCEEDED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple durable state exceeds the bounded file limit.",
    )

    private fun error(
        code: String,
        category: ErrorCategory,
        recoverability: Recoverability,
        message: String,
    ): DataLoomError = Error(
        code = ErrorCode(code),
        category = category,
        severity = ErrorSeverity.ERROR,
        recoverability = recoverability,
        message = message,
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}

internal class AppleDurableStateFileException : Exception()
internal class AppleDurableStateFileLimitException : Exception()
internal class AppleDurableStateEncodeFailureException(cause: Throwable) : Exception(cause)
internal class AppleDurableStatePayloadTooLargeException : Exception()
internal class MalformedAppleDurableStateException(cause: Throwable? = null) : Exception(cause)

private val DURABLE_STATE_NUL_CHARACTER: Char = Char(0)
private const val DURABLE_STATE_HEX_DIGITS = "0123456789abcdef"
private const val DURABLE_STATE_FILE_IO_BUFFER_BYTES = 4 * 1024
private const val MAX_STATE_FILE_BYTES = 4 * 1024 * 1024
private const val MAX_PAYLOAD_LENGTH = 4 * 1024 * 1024
private const val DURABLE_STATE_LOCK_RETRY_DELAY_MILLISECONDS = 5L
