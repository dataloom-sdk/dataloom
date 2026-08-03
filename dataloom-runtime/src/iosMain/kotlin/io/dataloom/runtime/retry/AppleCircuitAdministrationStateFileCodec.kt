package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCommandState
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationFailureSnapshot
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitAdministrationStateRecord
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerScopeKind
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.time.DataLoomInstant

/** Strict record codec embedded in the v2 Apple circuit-state snapshot. */
internal object AppleCircuitAdministrationStateFileCodec {
    private const val FIELD_COUNT: Int = 29

    fun encodeRecord(record: CircuitAdministrationStateRecord): String {
        val state = record.state
        val request = state.request
        val scope = request.scope
        val result = state.resultingRecord
        val execution = state.executionFailure
        return listOf(
            appleCircuitAdminHexEncode(request.commandId.value),
            scope.kind.name,
            appleCircuitAdminEncodeNullableString(scope.providerId?.value),
            appleCircuitAdminEncodeNullableString(scope.operation?.value),
            appleCircuitAdminEncodeNullableString(scope.tenantId?.value),
            appleCircuitAdminEncodeNullableString(scope.workflowId?.value),
            appleCircuitAdminHexEncode(request.principalId.value),
            request.requestedAt.epochMilliseconds.toString(),
            request.action.name,
            appleCircuitAdminHexEncode(request.reason.value),
            appleCircuitAdminEncodeNullableLong(request.openUntil?.epochMilliseconds),
            state.status.name,
            appleCircuitAdminEncodeNullableString(state.authorizationId?.value),
            state.updatedAt.epochMilliseconds.toString(),
            appleCircuitAdminEncodeNullableString(state.rejectionReasonCode),
            result?.state?.phase?.name ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            result?.state?.consecutiveFailures?.toString() ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            appleCircuitAdminEncodeNullableLong(result?.state?.failureWindowStartedAt?.epochMilliseconds),
            appleCircuitAdminEncodeNullableLong(result?.state?.openUntil?.epochMilliseconds),
            result?.state?.probeGeneration?.toString() ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            result?.state?.probeInFlight?.let { if (it) "1" else "0" }
                ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            result?.state?.updatedAt?.epochMilliseconds?.toString()
                ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            appleCircuitAdminEncodeNullableLong(result?.state?.probeLeaseUntil?.epochMilliseconds),
            result?.version?.toString() ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            appleCircuitAdminEncodeNullableString(execution?.code?.value),
            execution?.category?.name ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            execution?.severity?.name ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            execution?.recoverability?.name ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER,
            record.version.toString(),
        ).joinToString("\t")
    }

    fun decodeRecord(line: String): CircuitAdministrationStateRecord {
        val fields = line.split('\t')
        require(fields.size == FIELD_COUNT)

        val scope = CircuitBreakerScope(
            kind = CircuitBreakerScopeKind.valueOf(fields[1]),
            providerId = appleCircuitAdminDecodeNullableString(fields[2])?.let(::ProviderId),
            operation = appleCircuitAdminDecodeNullableString(fields[3])?.let(::RetryOperation),
            tenantId = appleCircuitAdminDecodeNullableString(fields[4])?.let(::TenantId),
            workflowId = appleCircuitAdminDecodeNullableString(fields[5])?.let(::WorkflowId),
        )
        val request = CircuitAdministrationRequest(
            commandId = CircuitAdministrationCommandId(appleCircuitAdminHexDecode(fields[0])),
            scope = scope,
            principalId = CircuitAdministrationPrincipalId(appleCircuitAdminHexDecode(fields[6])),
            requestedAt = DataLoomInstant(fields[7].appleCircuitAdminToLongStrict()),
            action = CircuitAdministrationAction.valueOf(fields[8]),
            reason = CircuitAdministrationReason(appleCircuitAdminHexDecode(fields[9])),
            openUntil = fields[10].appleCircuitAdminToNullableLong()?.let(::DataLoomInstant),
        )

        val requiredResultFields = listOf(15, 16, 19, 20, 21, 23)
            .map { fields[it].appleCircuitAdminNullMarkerToNull() }
        require(requiredResultFields.all { it == null } || requiredResultFields.all { it != null })
        val hasResult = requiredResultFields.all { it != null }
        require(
            hasResult || listOf(17, 18, 22).all {
                fields[it] == APPLE_CIRCUIT_ADMIN_NULL_MARKER
            },
        )
        val resultingRecord = if (!hasResult) {
            null
        } else {
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.valueOf(checkNotNull(requiredResultFields[0])),
                    consecutiveFailures = checkNotNull(requiredResultFields[1]).appleCircuitAdminToIntStrict(),
                    failureWindowStartedAt = fields[17].appleCircuitAdminToNullableLong()
                        ?.let(::DataLoomInstant),
                    openUntil = fields[18].appleCircuitAdminToNullableLong()
                        ?.let(::DataLoomInstant),
                    probeGeneration = checkNotNull(requiredResultFields[2])
                        .appleCircuitAdminToLongStrict(),
                    probeInFlight = checkNotNull(requiredResultFields[3])
                        .appleCircuitAdminToStrictBoolean(),
                    updatedAt = DataLoomInstant(
                        checkNotNull(requiredResultFields[4]).appleCircuitAdminToLongStrict(),
                    ),
                    probeLeaseUntil = fields[22].appleCircuitAdminToNullableLong()
                        ?.let(::DataLoomInstant),
                ),
                version = checkNotNull(requiredResultFields[5]).appleCircuitAdminToLongStrict(),
            )
        }

        val executionFields = listOf(
            appleCircuitAdminDecodeNullableString(fields[24]),
            fields[25].appleCircuitAdminNullMarkerToNull(),
            fields[26].appleCircuitAdminNullMarkerToNull(),
            fields[27].appleCircuitAdminNullMarkerToNull(),
        )
        require(executionFields.all { it == null } || executionFields.all { it != null })
        val executionFailure = if (executionFields.all { it == null }) {
            null
        } else {
            CircuitAdministrationFailureSnapshot(
                code = ErrorCode(checkNotNull(executionFields[0])),
                category = ErrorCategory.valueOf(checkNotNull(executionFields[1])),
                severity = ErrorSeverity.valueOf(checkNotNull(executionFields[2])),
                recoverability = Recoverability.valueOf(checkNotNull(executionFields[3])),
            )
        }

        return CircuitAdministrationStateRecord(
            state = CircuitAdministrationCommandState(
                request = request,
                status = CircuitAdministrationCommandStatus.valueOf(fields[11]),
                authorizationId = appleCircuitAdminDecodeNullableString(fields[12])
                    ?.let(::CircuitAdministrationAuthorizationId),
                updatedAt = DataLoomInstant(fields[13].appleCircuitAdminToLongStrict()),
                rejectionReasonCode = appleCircuitAdminDecodeNullableString(fields[14]),
                resultingRecord = resultingRecord,
                executionFailure = executionFailure,
            ),
            version = fields[28].appleCircuitAdminToLongStrict(),
        )
    }
}

private fun appleCircuitAdminEncodeNullableString(value: String?): String =
    value?.let(::appleCircuitAdminHexEncode) ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER

private fun appleCircuitAdminDecodeNullableString(value: String): String? =
    if (value == APPLE_CIRCUIT_ADMIN_NULL_MARKER) null else appleCircuitAdminHexDecode(value)

private fun appleCircuitAdminEncodeNullableLong(value: Long?): String =
    value?.toString() ?: APPLE_CIRCUIT_ADMIN_NULL_MARKER

private fun String.appleCircuitAdminToNullableLong(): Long? =
    appleCircuitAdminNullMarkerToNull()?.appleCircuitAdminToLongStrict()

private fun String.appleCircuitAdminNullMarkerToNull(): String? =
    if (this == APPLE_CIRCUIT_ADMIN_NULL_MARKER) null else this

private fun String.appleCircuitAdminToLongStrict(): Long {
    require(isNotEmpty())
    return toLongOrNull() ?: error("Invalid long value.")
}

private fun String.appleCircuitAdminToIntStrict(): Int {
    require(isNotEmpty())
    return toIntOrNull() ?: error("Invalid integer value.")
}

private fun String.appleCircuitAdminToStrictBoolean(): Boolean = when (this) {
    "0" -> false
    "1" -> true
    else -> error("Invalid boolean value.")
}

private fun appleCircuitAdminHexEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        append(APPLE_CIRCUIT_ADMIN_HEX_DIGITS[unsigned ushr 4])
        append(APPLE_CIRCUIT_ADMIN_HEX_DIGITS[unsigned and 0x0f])
    }
}

private fun appleCircuitAdminHexDecode(value: String): String {
    require(value.length % 2 == 0)
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        val offset = index * 2
        val high = appleCircuitAdminHexValue(value[offset])
        val low = appleCircuitAdminHexValue(value[offset + 1])
        require(high >= 0 && low >= 0)
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

private fun appleCircuitAdminHexValue(value: Char): Int = when (value) {
    in '0'..'9' -> value - '0'
    in 'a'..'f' -> value - 'a' + 10
    in 'A'..'F' -> value - 'A' + 10
    else -> -1
}

internal const val APPLE_CIRCUIT_ADMIN_MAX_RECORD_COUNT: Int = 10_000
private const val APPLE_CIRCUIT_ADMIN_NULL_MARKER: String = "-"
private const val APPLE_CIRCUIT_ADMIN_HEX_DIGITS: String = "0123456789abcdef"
