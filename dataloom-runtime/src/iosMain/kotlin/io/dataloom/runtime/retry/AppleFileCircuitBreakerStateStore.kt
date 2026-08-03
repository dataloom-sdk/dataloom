@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerScopeKind
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.time.DataLoomInstant
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
 * Production Apple file-backed implementation of [CircuitBreakerStateStore].
 *
 * The caller supplies an absolute, application-private directory path, normally
 * beneath Application Support. The store creates that directory lazily with
 * owner-only permissions, serializes only bounded circuit operational state,
 * and never stores payloads, credentials, headers, exception text, or arbitrary
 * metadata.
 *
 * A process-shared advisory file lock protects every load and compare-and-set.
 * Successful writes are fsynced to a temporary file, atomically renamed over the
 * prior snapshot, and followed by a parent-directory fsync. Compare-and-set
 * therefore remains exact across multiple
 * store instances and cooperating app processes that use the same directory and
 * file name.
 *
 * Lock acquisition is non-blocking and cancellation-aware. Once the bounded
 * file operation begins, POSIX read/write syscalls are synchronous; the on-disk
 * snapshot is capped at 4 MiB to keep that non-suspending section bounded.
 */
public class AppleFileCircuitBreakerStateStore(
    directoryPath: String,
    fileName: String = DEFAULT_FILE_NAME,
) : CircuitBreakerStateStore {
    private val boundary = AppleCircuitStateFileBoundary(directoryPath, fileName)

    override suspend fun load(
        scope: CircuitBreakerScope,
    ): ProviderOperationResult<CircuitBreakerLoadResult> = protect {
        withExclusiveLock {
            val record = boundary.readSnapshot().circuitRecords[scopeStorageKey(scope)]
            if (record == null) {
                CircuitBreakerLoadResult.Missing
            } else {
                CircuitBreakerLoadResult.Found(record)
            }
        }
    }

    override suspend fun compareAndSet(
        request: CircuitBreakerCompareAndSetRequest,
    ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
        if (request.expectedVersion == Long.MAX_VALUE) {
            return ProviderOperationResult.Failure(AppleCircuitStoreError.versionExhausted())
        }
        return protect {
            withExclusiveLock {
                val snapshot = boundary.readSnapshot()
                val records = snapshot.circuitRecords
                val key = scopeStorageKey(request.scope)
                val current = records[key]
                val matches = when (val expected = request.expectedVersion) {
                    null -> current == null
                    else -> current?.version == expected
                }
                if (!matches) {
                    CircuitBreakerCompareAndSetResult.Conflict(current)
                } else {
                    val nextVersion = request.expectedVersion?.plus(1L) ?: 0L
                    val nextRecord = CircuitBreakerStateRecord(
                        state = request.nextState,
                        version = nextVersion,
                    )
                    records[key] = nextRecord
                    currentCoroutineContext().ensureActive()
                    boundary.writeSnapshot(snapshot)
                    CircuitBreakerCompareAndSetResult.Updated(nextRecord)
                }
            }
        }
    }

    private suspend fun <T> protect(
        block: suspend () -> T,
    ): ProviderOperationResult<T> = try {
        currentCoroutineContext().ensureActive()
        ProviderOperationResult.Success(block())
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
    } catch (_: AppleCircuitStateLimitException) {
        ProviderOperationResult.Failure(AppleCircuitStoreError.stateLimitExceeded())
    } catch (_: AppleCircuitAdministrationRecordLimitException) {
        ProviderOperationResult.Failure(AppleCircuitStoreError.stateLimitExceeded())
    } catch (_: MalformedAppleCircuitStateException) {
        ProviderOperationResult.Failure(AppleCircuitStoreError.integrityFailure())
    } catch (_: AppleCircuitFileException) {
        ProviderOperationResult.Failure(AppleCircuitStoreError.fileFailure())
    } catch (_: Exception) {
        ProviderOperationResult.Failure(AppleCircuitStoreError.fileFailure())
    }

    private suspend fun <T> withExclusiveLock(
        block: suspend () -> T,
    ): T = boundary.withExclusiveLock(block)

    public companion object {
        public const val DEFAULT_FILE_NAME: String = "dataloom-circuit-state-v1.tsv"
    }
}

/** Shared lock and atomic-snapshot boundary for Apple circuit state and administration. */
internal class AppleCircuitStateFileBoundary(
    directoryPath: String,
    fileName: String,
) {
    private val normalizedDirectoryPath: String = validateDirectoryPath(directoryPath)
    private val validatedFileName: String = validateFileName(fileName)
    private val dataFilePath: String = "$normalizedDirectoryPath/$validatedFileName"
    private val lockFilePath: String = "$dataFilePath.lock"
    private val temporaryFilePath: String = "$dataFilePath.tmp"

    suspend fun <T> withExclusiveLock(block: suspend () -> T): T {
        ensurePrivateDirectory(normalizedDirectoryPath)
        val descriptor = openOwnerOnly(lockFilePath, O_RDWR or O_CREAT)
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

    fun readSnapshot(): AppleCircuitStateSnapshot {
        val content = readUtf8FileOrNull(dataFilePath) ?: return AppleCircuitStateSnapshot()
        return AppleCircuitStateFileCodec.decodeSnapshot(content)
    }

    fun writeSnapshot(snapshot: AppleCircuitStateSnapshot) {
        writeUtf8FileAtomically(
            temporaryPath = temporaryFilePath,
            destinationPath = dataFilePath,
            content = AppleCircuitStateFileCodec.encodeSnapshot(snapshot),
        )
    }

    private suspend fun acquireExclusiveLock(descriptor: Int) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (flock(descriptor, LOCK_EX or LOCK_NB) == 0) return
            when (errno) {
                EINTR -> Unit
                EAGAIN, EWOULDBLOCK -> delay(LOCK_RETRY_DELAY_MILLISECONDS)
                else -> throw AppleCircuitFileException()
            }
        }
    }
}

internal data class AppleCircuitStateSnapshot(
    val circuitRecords: MutableMap<String, CircuitBreakerStateRecord> = linkedMapOf(),
    val administrationRecords: MutableMap<String, io.dataloom.api.circuit.CircuitAdministrationStateRecord> =
        linkedMapOf(),
)

internal object AppleCircuitStateFileCodec {
    private const val HEADER_V1 = "DATALOOM_CIRCUIT_STATE\t1"
    private const val HEADER_V2 = "DATALOOM_CIRCUIT_STATE\t2"
    private const val STATE_TAG = "S\t"
    private const val ADMINISTRATION_TAG = "A\t"
    private const val FIELD_COUNT = 14

    fun encode(records: Map<String, CircuitBreakerStateRecord>): String {
        return encodeSnapshot(
            AppleCircuitStateSnapshot(circuitRecords = records.toMutableMap()),
        )
    }

    fun encodeSnapshot(snapshot: AppleCircuitStateSnapshot): String {
        if (snapshot.administrationRecords.size > APPLE_CIRCUIT_ADMIN_MAX_RECORD_COUNT) {
            throw AppleCircuitAdministrationRecordLimitException()
        }
        val content = buildString {
            append(HEADER_V2)
            append('\n')
            snapshot.circuitRecords.entries.sortedBy { it.key }.forEach { entry ->
                val key = entry.key
                val record = entry.value
                check(key == scopeStorageKey(record.state.scope)) {
                    "Circuit-state map key does not match the record scope."
                }
                append(STATE_TAG)
                append(encodeRecord(record))
                append('\n')
            }
            snapshot.administrationRecords.entries.sortedBy { it.key }.forEach { entry ->
                check(entry.key == entry.value.state.request.commandId.value) {
                    "Circuit-administration map key does not match the command identifier."
                }
                append(ADMINISTRATION_TAG)
                append(AppleCircuitAdministrationStateFileCodec.encodeRecord(entry.value))
                append('\n')
            }
        }
        if (content.encodeToByteArray().size > MAX_STATE_FILE_BYTES) {
            throw AppleCircuitStateLimitException()
        }
        return content
    }

    fun decode(content: String): MutableMap<String, CircuitBreakerStateRecord> {
        return decodeSnapshot(content).circuitRecords
    }

    fun decodeSnapshot(content: String): AppleCircuitStateSnapshot {
        if (content.encodeToByteArray().size > MAX_STATE_FILE_BYTES) {
            throw AppleCircuitStateLimitException()
        }
        return try {
            val lines = content.split('\n')
            require(lines.isNotEmpty())
            val version = when (lines.first()) {
                HEADER_V1 -> 1
                HEADER_V2 -> 2
                else -> error("Unsupported Apple circuit-state format.")
            }
            val snapshot = AppleCircuitStateSnapshot()
            for (index in 1 until lines.size) {
                val line = lines[index]
                if (line.isEmpty()) {
                    require(index == lines.lastIndex)
                    continue
                }
                if (version == 1) {
                    val record = decodeRecord(line)
                    val key = scopeStorageKey(record.state.scope)
                    require(snapshot.circuitRecords.put(key, record) == null)
                } else when {
                    line.startsWith(STATE_TAG) -> {
                        val record = decodeRecord(line.removePrefix(STATE_TAG))
                        val key = scopeStorageKey(record.state.scope)
                        require(snapshot.circuitRecords.put(key, record) == null)
                    }
                    line.startsWith(ADMINISTRATION_TAG) -> {
                        if (
                            snapshot.administrationRecords.size >=
                            APPLE_CIRCUIT_ADMIN_MAX_RECORD_COUNT
                        ) {
                            throw AppleCircuitAdministrationRecordLimitException()
                        }
                        val record = AppleCircuitAdministrationStateFileCodec.decodeRecord(
                            line.removePrefix(ADMINISTRATION_TAG),
                        )
                        val key = record.state.request.commandId.value
                        require(snapshot.administrationRecords.put(key, record) == null)
                    }
                    else -> error("Unknown Apple circuit-state record tag.")
                }
            }
            snapshot
        } catch (invalid: AppleCircuitStateLimitException) {
            throw invalid
        } catch (invalid: AppleCircuitAdministrationRecordLimitException) {
            throw invalid
        } catch (invalid: Exception) {
            throw MalformedAppleCircuitStateException(invalid)
        }
    }

    private fun encodeRecord(record: CircuitBreakerStateRecord): String = listOf(
        record.state.scope.kind.name,
        encodeNullable(record.state.scope.providerId?.value),
        encodeNullable(record.state.scope.operation?.value),
        encodeNullable(record.state.scope.tenantId?.value),
        encodeNullable(record.state.scope.workflowId?.value),
        record.state.phase.name,
        record.state.consecutiveFailures.toString(),
        encodeNullableLong(record.state.failureWindowStartedAt?.epochMilliseconds),
        encodeNullableLong(record.state.openUntil?.epochMilliseconds),
        record.state.probeGeneration.toString(),
        if (record.state.probeInFlight) "1" else "0",
        record.state.updatedAt.epochMilliseconds.toString(),
        encodeNullableLong(record.state.probeLeaseUntil?.epochMilliseconds),
        record.version.toString(),
    ).joinToString("\t")

    private fun decodeRecord(line: String): CircuitBreakerStateRecord {
        val fields = line.split('\t')
        require(fields.size == FIELD_COUNT)
        val scope = CircuitBreakerScope(
            kind = CircuitBreakerScopeKind.valueOf(fields[0]),
            providerId = decodeNullable(fields[1])?.let(::ProviderId),
            operation = decodeNullable(fields[2])?.let(::RetryOperation),
            tenantId = decodeNullable(fields[3])?.let(::TenantId),
            workflowId = decodeNullable(fields[4])?.let(::WorkflowId),
        )
        return CircuitBreakerStateRecord(
            state = CircuitBreakerState(
                scope = scope,
                phase = CircuitBreakerPhase.valueOf(fields[5]),
                consecutiveFailures = fields[6].toIntStrict(),
                failureWindowStartedAt = fields[7].toNullableLong()?.let(::DataLoomInstant),
                openUntil = fields[8].toNullableLong()?.let(::DataLoomInstant),
                probeGeneration = fields[9].toLongStrict(),
                probeInFlight = fields[10].toStrictBoolean(),
                updatedAt = DataLoomInstant(fields[11].toLongStrict()),
                probeLeaseUntil = fields[12].toNullableLong()?.let(::DataLoomInstant),
            ),
            version = fields[13].toLongStrict(),
        )
    }

    private fun encodeNullable(value: String?): String = value?.let(::hexEncode) ?: NULL_MARKER

    private fun decodeNullable(value: String): String? =
        if (value == NULL_MARKER) null else hexDecode(value)

    private fun encodeNullableLong(value: Long?): String = value?.toString() ?: NULL_MARKER

    private fun String.toNullableLong(): Long? =
        if (this == NULL_MARKER) null else toLongStrict()

    private fun String.toLongStrict(): Long {
        require(isNotEmpty())
        return toLongOrNull() ?: error("Invalid long value.")
    }

    private fun String.toIntStrict(): Int {
        require(isNotEmpty())
        return toIntOrNull() ?: error("Invalid integer value.")
    }

    private fun String.toStrictBoolean(): Boolean = when (this) {
        "0" -> false
        "1" -> true
        else -> error("Invalid boolean value.")
    }
}

internal fun scopeStorageKey(scope: CircuitBreakerScope): String = buildString {
    append(scope.kind.name)
    append('|')
    append(encodeKeyPart(scope.providerId?.value))
    append('|')
    append(encodeKeyPart(scope.operation?.value))
    append('|')
    append(encodeKeyPart(scope.tenantId?.value))
    append('|')
    append(encodeKeyPart(scope.workflowId?.value))
}

private fun encodeKeyPart(value: String?): String = value?.let(::hexEncode) ?: NULL_MARKER

private fun hexEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        append(HEX_DIGITS[unsigned ushr 4])
        append(HEX_DIGITS[unsigned and 0x0f])
    }
}

private fun hexDecode(value: String): String {
    require(value.length % 2 == 0)
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        val offset = index * 2
        val high = hexValue(value[offset])
        val low = hexValue(value[offset + 1])
        require(high >= 0 && low >= 0)
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

private fun hexValue(value: Char): Int = when (value) {
    in '0'..'9' -> value - '0'
    in 'a'..'f' -> value - 'a' + 10
    in 'A'..'F' -> value - 'A' + 10
    else -> -1
}

private fun validateDirectoryPath(path: String): String {
    require(path.isNotBlank()) { "Apple circuit-state directoryPath must not be blank." }
    require(path.startsWith('/')) { "Apple circuit-state directoryPath must be absolute." }
    require('\u0000' !in path) { "Apple circuit-state directoryPath must not contain NUL." }
    val normalized = path.trimEnd('/')
    require(normalized.isNotEmpty()) { "Apple circuit-state directoryPath must not be root." }
    require(normalized.split('/').none { it == "." || it == ".." }) {
        "Apple circuit-state directoryPath must not contain dot traversal segments."
    }
    return normalized
}

private fun validateFileName(fileName: String): String {
    require(fileName.isNotBlank()) { "Apple circuit-state fileName must not be blank." }
    require('/' !in fileName && '\u0000' !in fileName) {
        "Apple circuit-state fileName must be one safe path component."
    }
    require(fileName != "." && fileName != "..") {
        "Apple circuit-state fileName must not be a dot segment."
    }
    return fileName
}

private fun ensurePrivateDirectory(path: String) {
    var current = ""
    path.split('/').filter { it.isNotEmpty() }.forEach { component ->
        current += "/$component"
        if (mkdir(current, S_IRWXU.convert()) != 0 && errno != EEXIST) {
            throw AppleCircuitFileException()
        }
    }
}

private fun openOwnerOnly(path: String, flags: Int): Int {
    val descriptor = open(path, flags, S_IRUSR or S_IWUSR)
    if (descriptor < 0) throw AppleCircuitFileException()
    return descriptor
}

internal fun readUtf8FileOrNull(path: String): String? {
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) {
        if (errno == ENOENT) return null
        throw AppleCircuitFileException()
    }
    return try {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        val buffer = ByteArray(FILE_IO_BUFFER_BYTES)
        while (true) {
            val count = buffer.usePinned { pinned ->
                read(descriptor, pinned.addressOf(0), buffer.size.convert())
            }
            when {
                count == 0L -> break
                count < 0L && errno == EINTR -> continue
                count < 0L -> throw AppleCircuitFileException()
                else -> {
                    totalBytes += count.toInt()
                    if (totalBytes > MAX_STATE_FILE_BYTES) {
                        throw AppleCircuitStateLimitException()
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
            throw MalformedAppleCircuitStateException(invalid)
        }
    } finally {
        close(descriptor)
    }
}

internal fun writeUtf8FileAtomically(
    temporaryPath: String,
    destinationPath: String,
    content: String,
) {
    val bytes = content.encodeToByteArray()
    if (bytes.size > MAX_STATE_FILE_BYTES) throw AppleCircuitStateLimitException()
    if (unlink(temporaryPath) != 0 && errno != ENOENT) {
        throw AppleCircuitFileException()
    }
    val descriptor = openOwnerOnly(
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
                count <= 0L -> throw AppleCircuitFileException()
                else -> offset += count.toInt()
            }
        }
        if (fsync(descriptor) != 0) throw AppleCircuitFileException()
        val closeResult = close(descriptor)
        descriptorOpen = false
        if (closeResult != 0) throw AppleCircuitFileException()
        if (rename(temporaryPath, destinationPath) != 0) {
            throw AppleCircuitFileException()
        }
        renamed = true
        fsyncDirectory(destinationPath.substringBeforeLast('/'))
    } finally {
        if (descriptorOpen) {
            close(descriptor)
        }
        if (!renamed) {
            unlink(temporaryPath)
        }
    }
}

private fun fsyncDirectory(path: String) {
    val descriptor = open(path, O_RDONLY)
    if (descriptor < 0) throw AppleCircuitFileException()
    var descriptorOpen = true
    try {
        if (fsync(descriptor) != 0) throw AppleCircuitFileException()
        val closeResult = close(descriptor)
        descriptorOpen = false
        if (closeResult != 0) throw AppleCircuitFileException()
    } finally {
        if (descriptorOpen) {
            close(descriptor)
        }
    }
}

private object AppleCircuitStoreError {
    fun fileFailure(): DataLoomError = error(
        code = "CIRCUIT_APPLE_FILE_IO_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "An Apple circuit-state file operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "CIRCUIT_APPLE_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple circuit state failed integrity validation.",
    )

    fun stateLimitExceeded(): DataLoomError = error(
        code = "CIRCUIT_APPLE_STATE_LIMIT_EXCEEDED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple circuit state exceeds the bounded file limit.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "CIRCUIT_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The circuit-state record version is exhausted.",
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
    ) : DataLoomError
}

internal class AppleCircuitFileException : Exception()
internal class AppleCircuitStateLimitException : Exception()
internal class AppleCircuitAdministrationRecordLimitException : Exception()
internal class MalformedAppleCircuitStateException(cause: Throwable) : Exception(cause)

private const val NULL_MARKER = "-"
private const val HEX_DIGITS = "0123456789abcdef"
private const val FILE_IO_BUFFER_BYTES = 4 * 1024
private const val MAX_STATE_FILE_BYTES = 4 * 1024 * 1024
private const val LOCK_RETRY_DELAY_MILLISECONDS = 5L
