package io.dataloom.runtime.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

internal object AppleQueueProviderError {
    fun duplicateEntry(entryId: String): DataLoomError = appleQueueError(
        code = "QUEUE_DUPLICATE_ENTRY",
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "A queue entry with id '$entryId' already exists.",
    )

    fun staleLease(entryId: String): DataLoomError = appleQueueError(
        code = "QUEUE_STALE_LEASE",
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Lease mismatch or stale lease for entry '$entryId'.",
    )

    fun cancellationRejected(entryId: String): DataLoomError = appleQueueError(
        code = "QUEUE_CANCELLATION_REJECTED",
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.WARNING,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Entry '$entryId' cannot be cancelled in its current state.",
    )

    fun fileFailure(): DataLoomError = appleQueueError(
        code = "QUEUE_APPLE_FILE_IO_FAILURE",
        category = ErrorCategory.STORAGE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "An Apple queue-state file operation failed.",
    )

    fun integrityFailure(): DataLoomError = appleQueueError(
        code = "QUEUE_APPLE_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple queue state failed integrity validation.",
    )

    fun stateLimitExceeded(): DataLoomError = appleQueueError(
        code = "QUEUE_APPLE_STATE_LIMIT_EXCEEDED",
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple queue state exceeds the bounded file limit.",
    )

    fun entryLimitExceeded(): DataLoomError = appleQueueError(
        code = "QUEUE_APPLE_ENTRY_LIMIT_EXCEEDED",
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple queue state exceeds the bounded entry limit.",
    )

    private fun appleQueueError(
        code: String,
        category: ErrorCategory,
        severity: ErrorSeverity,
        recoverability: Recoverability,
        message: String,
    ): DataLoomError = ApplePersistedQueueError(
        code = ErrorCode(code),
        category = category,
        severity = severity,
        recoverability = recoverability,
        message = message,
    )
}

internal data class ApplePersistedQueueError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
) : DataLoomError {
    override val cause: Throwable? = null
}

internal fun DataLoomError.appleQueueSanitizedCopy(): DataLoomError = ApplePersistedQueueError(
    code = code,
    category = category,
    severity = severity,
    recoverability = recoverability,
    message = message,
)

internal fun appleQueueEncodeMetadata(metadata: DataLoomMetadata): String {
    val entries = metadata.entries
    if (entries.isEmpty()) return APPLE_QUEUE_NULL_MARKER
    return entries.entries.sortedBy { it.key }.joinToString(",") { entry ->
        "${appleQueueHexEncode(entry.key)}:${appleQueueHexEncode(entry.value)}"
    }
}

internal fun appleQueueDecodeMetadata(encoded: String): DataLoomMetadata {
    if (encoded == APPLE_QUEUE_NULL_MARKER) return DataLoomMetadata.Empty
    require(encoded.isNotEmpty())
    val values = linkedMapOf<String, String>()
    encoded.split(',').forEach { pair ->
        val separator = pair.indexOf(':')
        require(separator > 0)
        require(pair.indexOf(':', separator + 1) == -1)
        val key = appleQueueHexDecode(pair.substring(0, separator))
        val = appleQueueHexDecode(pair.substring(separator + 1))
        require(values.put(key, value) == null)
    }
    return DataLoomMetadata.of(values)
}

internal fun appleQueueEncodeNullableString(value: String?): String =
    value?.let(::appleQueueHexEncode) ?: APPLE_QUEUE_NULL_MARKER

internal fun appleQueueDecodeNullableString(value: String): String? =
    if (value == APPLE_QUEUE_NULL_MARKER) null else appleQueueHexDecode(value)

internal fun appleQueueEncodeNullableLong(value: Long?): String =
    value?.toString() ?: APPLE_QUEUE_NULL_MARKER

internal fun appleQueueEncodeNullableInt(value: Int?): String =
    value?.toString() ?: APPLE_QUEUE_NULL_MARKER

internal fun String.appleQueueToNullableLong(): Long? =
    if (this == APPLE_QUEUE_NULL_MARKER) null else appleQueueToLongStrict()

internal fun String.appleQueueToNullableInt(): Int? =
    if (this == APPLE_QUEUUE_NULL_MARKER) null else appleQueueToIntStrict()

internal fun String.appleQueueToLongStrict(): Long {
    require(isNotEmpty())
    return toLongOrNull() ?: error("Invalid long value.")
}

internal fun String.appleQueueToIntStrict(): Int {
    require(isNotEmpty())
    return toIntOrNull() ?: error("Invalid integer value.")
}

internal fun appleQueueHexEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        append(APPLE_QUEUE_HEX_DIGITS[unsigned ushr 4])
        append(APPLE_QUEUE_HEX_DIGITS[unsigned and 0x0f])
    }
}

internal fun appleQueueHexDecode(value: String): String {
    require(value.length % 2 == 0)
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        val offset = index * 2
        val high = appleQueueHexValue(value[offset])
        val low = appleQueueHexValue(value[offset + 1])
        require(high >= 0 && low >= 0)
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

private fun appleQueueHexValue(value: Char): Int = when (value) {
    in '0'..'9' -> value - '0'
    in 'a'..'f' -> value - 'a' + 10
    in 'A'..'F' -> value - 'A' + 10
    else -> -1
}
