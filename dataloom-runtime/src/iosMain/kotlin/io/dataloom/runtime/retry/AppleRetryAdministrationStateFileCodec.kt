package io.dataloom.runtime.retry

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationCommandState
import io.dataloom.api.retry.RetryAdministrationCommandStatus
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAdministrationStateRecord
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.time.DataLoomInstant

/** Strict deterministic codec for the Apple retry-administration state store. */
internal object AppleRetryAdministrationStateFileCodec {
    private const val HEADER: String = "DATALOOM_RETRY_ADMINISTRATION_STATE\t1"
    private const val FIELD_COUNT: Int = 20

    fun encode(records: Map<String, RetryAdministrationStateRecord>): String {
        if (records.size > APPLE_RETRY_ADMIN_MAX_RECORD_COUNT) {
            throw AppleRetryAdministrationRecordLimitException()
        }
        val content = buildString {
            append(HEADER)
            append('\n')
            records.entries.sortedBy { it.key }.forEach { mapEntry ->
                val commandId = mapEntry.key
                val record = mapEntry.value
                check(commandId == record.state.request.commandId.value) {
                    "Retry-administration map key does not match the command identifier."
                }
                append(encodeRecord(record))
                append('\n')
            }
        }
        if (content.encodeToByteArray().size > APPLE_RETRY_ADMIN_MAX_STATE_FILE_BYTES) {
            throw AppleRetryAdministrationStateLimitException()
        }
        return content
    }

    fun decode(content: String): MutableMap<String, RetryAdministrationStateRecord> {
        if (content.encodeToByteArray().size > APPLE_RETRY_ADMIN_MAX_STATE_FILE_BYTES) {
            throw AppleRetryAdministrationStateLimitException()
        }
        return try {
            val lines = content.split('\n')
            require(lines.isNotEmpty() && lines.first() == HEADER)
            val records = linkedMapOf<String, RetryAdministrationStateRecord>()
            for (index in 1 until lines.size) {
                val line = lines[index]
                if (line.isEmpty()) {
                    require(index == lines.lastIndex)
                    continue
                }
                if (records.size >= APPLE_RETRY_ADMIN_MAX_RECORD_COUNT) {
                    throw AppleRetryAdministrationRecordLimitException()
                }
                val record = decodeRecord(line)
                val commandId = record.state.request.commandId.value
                require(records.put(commandId, record) == null)
            }
            records
        } catch (limit: AppleRetryAdministrationStateLimitException) {
            throw limit
        } catch (limit: AppleRetryAdministrationRecordLimitException) {
            throw limit
        } catch (invalid: Exception) {
            throw MalformedAppleRetryAdministrationStateException(invalid)
        }
    }

    private fun encodeRecord(record: RetryAdministrationStateRecord): String {
        val state = record.state
        val request = state.request
        val original = request.originalFailure
        val execution = state.executionFailure
        return listOf(
            appleRetryAdminHexEncode(request.commandId.value),
            appleRetryAdminHexEncode(request.queueEntryId.value),
            appleRetryAdminHexEncode(request.principalId.value),
            request.requestedAt.epochMilliseconds.toString(),
            request.action.name,
            appleRetryAdminHexEncode(request.reason.value),
            appleRetryAdminHexEncode(original.code.value),
            original.category.name,
            original.severity.name,
            original.recoverability.name,
            state.status.name,
            appleRetryAdminEncodeNullableString(state.authorizationId?.value),
            state.effectiveRecoverability?.name ?: APPLE_RETRY_ADMIN_NULL_MARKER,
            state.updatedAt.epochMilliseconds.toString(),
            appleRetryAdminEncodeNullableString(state.rejectionReasonCode),
            appleRetryAdminEncodeNullableString(execution?.code?.value),
            execution?.category?.name ?: APPLE_RETRY_ADMIN_NULL_MARKER,
            execution?.severity?.name ?: APPLE_RETRY_ADMIN_NULL_MARKER,
            execution?.recoverability?.name ?: APPLE_RETRY_ADMIN_NULL_MARKER,
            record.version.toString(),
        ).joinToString("\t")
    }

    private fun decodeRecord(line: String): RetryAdministrationStateRecord {
        val fields = line.split('\t')
        require(fields.size == FIELD_COUNT)

        val request = RetryAdministrationRequest(
            commandId = RetryAdministrationCommandId(appleRetryAdminHexDecode(fields[0])),
            queueEntryId = QueueEntryId(appleRetryAdminHexDecode(fields[1])),
            principalId = RetryAdministrationPrincipalId(appleRetryAdminHexDecode(fields[2])),
            requestedAt = DataLoomInstant(fields[3].appleRetryAdminToLongStrict()),
            action = RetryAdministrationAction.valueOf(fields[4]),
            reason = RetryAdministrationReason(appleRetryAdminHexDecode(fields[5])),
            originalFailure = RetryFailureSnapshot(
                code = ErrorCode(appleRetryAdminHexDecode(fields[6])),
                category = ErrorCategory.valueOf(fields[7]),
                severity = ErrorSeverity.valueOf(fields[8]),
                recoverability = Recoverability.valueOf(fields[9]),
            ),
        )

        val executionCode = appleRetryAdminDecodeNullableString(fields[15])
        val executionCategory = fields[16].appleRetryAdminNullableEnumName()
        val executionSeverity = fields[17].appleRetryAdminNullableEnumName()
        val executionRecoverability = fields[18].appleRetryAdminNullableEnumName()
        val executionColumns = listOf(
            executionCode,
            executionCategory,
            executionSeverity,
            executionRecoverability,
        )
        require(executionColumns.all { it == null } || executionColumns.all { it != null })
        val executionFailure = if (executionColumns.all { it == null }) {
            null
        } else {
            RetryFailureSnapshot(
                code = ErrorCode(checkNotNull(executionCode)),
                category = ErrorCategory.valueOf(checkNotNull(executionCategory)),
                severity = ErrorSeverity.valueOf(checkNotNull(executionSeverity)),
                recoverability = Recoverability.valueOf(checkNotNull(executionRecoverability)),
            )
        }

        return RetryAdministrationStateRecord(
            state = RetryAdministrationCommandState(
                request = request,
                status = RetryAdministrationCommandStatus.valueOf(fields[10]),
                authorizationId = appleRetryAdminDecodeNullableString(fields[11])
                    ?.let(::RetryAdministrationAuthorizationId),
                effectiveRecoverability = fields[12].appleRetryAdminNullableEnumName()
                    ?.let { Recoverability.valueOf(it) },
                updatedAt = DataLoomInstant(fields[13].appleRetryAdminToLongStrict()),
                rejectionReasonCode = appleRetryAdminDecodeNullableString(fields[14]),
                executionFailure = executionFailure,
            ),
            version = fields[19].appleRetryAdminToLongStrict(),
        )
    }
}

internal fun appleRetryAdminEncodeNullableString(value: String?): String =
    value?.let(::appleRetryAdminHexEncode) ?: APPLE_RETRY_ADMIN_NULL_MARKER

internal fun appleRetryAdminDecodeNullableString(value: String): String? =
    if (value == APPLE_RETRY_ADMIN_NULL_MARKER) null else appleRetryAdminHexDecode(value)

private fun String.appleRetryAdminNullableEnumName(): String? =
    if (this == APPLE_RETRY_ADMIN_NULL_MARKER) null else this

internal fun String.appleRetryAdminToLongStrict(): Long {
    require(isNotEmpty())
    return toLongOrNull() ?: error("Invalid long value.")
}

internal fun appleRetryAdminHexEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        append(APPLE_RETRY_ADMIN_HEX_DIGITS[unsigned ushr 4])
        append(APPLE_RETRY_ADMIN_HEX_DIGITS[unsigned and 0x0f])
    }
}

internal fun appleRetryAdminHexDecode(value: String): String {
    require(value.length % 2 == 0)
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        val offset = index * 2
        val high = appleRetryAdminHexValue(value[offset])
        val low = appleRetryAdminHexValue(value[offset + 1])
        require(high >= 0 && low >= 0)
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

private fun appleRetryAdminHexValue(value: Char): Int = when (value) {
    in '0'..'9' -> value - '0'
    in 'a'..'f' -> value - 'a' + 10
    in 'A'..'F' -> value - 'A' + 10
    else -> -1
}
