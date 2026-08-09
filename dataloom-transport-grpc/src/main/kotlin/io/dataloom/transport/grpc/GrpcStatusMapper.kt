package io.dataloom.transport.grpc

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException

/**
 * Maps gRPC [Status] codes to canonical [DataLoomError] instances.
 *
 * Only [StatusException] and [StatusRuntimeException] from the gRPC layer are
 * accepted. Raw `io.grpc.*` types must not cross the [GrpcTransportProvider]
 * public API boundary — callers receive [DataLoomError] exclusively.
 *
 * **Sensitive-data restriction:** No credential, token, call-metadata, or
 * header value may appear in mapped error messages. Only the gRPC status code
 * name and a brief neutral description are included.
 */
public object GrpcStatusMapper {

    /**
     * Maps a gRPC [StatusException] to a canonical [DataLoomError].
     *
     * @param exception gRPC status exception to map.
     * @return canonical [DataLoomError] with code, category, severity,
     *   recoverability, and a sanitized message. The [exception] is retained
     *   as [DataLoomError.cause] for diagnostics.
     */
    public fun map(exception: StatusException): DataLoomError =
        fromStatus(exception.status, exception)

    /**
     * Maps a gRPC [StatusRuntimeException] to a canonical [DataLoomError].
     *
     * @param exception gRPC status runtime exception to map.
     * @return canonical [DataLoomError] with code, category, severity,
     *   recoverability, and a sanitized message. The [exception] is retained
     *   as [DataLoomError.cause] for diagnostics.
     */
    public fun map(exception: StatusRuntimeException): DataLoomError =
        fromStatus(exception.status, exception)

    private fun fromStatus(status: Status, cause: Throwable): DataLoomError {
        val code = status.code ?: Status.Code.UNKNOWN
        val sanitizedMessage = "gRPC call failed: ${code.name}"
        return GrpcDataLoomError(
            code = ErrorCode(errorCodeFor(code)),
            category = categoryFor(code),
            severity = ErrorSeverity.ERROR,
            recoverability = recoverabilityFor(code),
            message = sanitizedMessage,
            // Wrap in a transport-neutral cause so no io.grpc.* type is
            // accessible via the DataLoomError.cause property type.
            cause = GrpcTransportCause(sanitizedMessage, cause),
        )
    }

    private fun errorCodeFor(code: Status.Code): String = when (code) {
        Status.Code.CANCELLED -> "GRPC_CANCELLED"
        Status.Code.UNKNOWN -> "GRPC_UNKNOWN"
        Status.Code.INVALID_ARGUMENT -> "GRPC_INVALID_ARGUMENT"
        Status.Code.DEADLINE_EXCEEDED -> "GRPC_DEADLINE_EXCEEDED"
        Status.Code.NOT_FOUND -> "GRPC_NOT_FOUND"
        Status.Code.ALREADY_EXISTS -> "GRPC_ALREADY_EXISTS"
        Status.Code.PERMISSION_DENIED -> "GRPC_PERMISSION_DENIED"
        Status.Code.RESOURCE_EXHAUSTED -> "GRPC_RESOURCE_EXHAUSTED"
        Status.Code.FAILED_PRECONDITION -> "GRPC_FAILED_PRECONDITION"
        Status.Code.ABORTED -> "GRPC_ABORTED"
        Status.Code.OUT_OF_RANGE -> "GRPC_OUT_OF_RANGE"
        Status.Code.UNIMPLEMENTED -> "GRPC_UNIMPLEMENTED"
        Status.Code.INTERNAL -> "GRPC_INTERNAL"
        Status.Code.UNAVAILABLE -> "GRPC_UNAVAILABLE"
        Status.Code.DATA_LOSS -> "GRPC_DATA_LOSS"
        Status.Code.UNAUTHENTICATED -> "GRPC_UNAUTHENTICATED"
        Status.Code.OK -> "GRPC_UNEXPECTED_OK"
    }

    private fun categoryFor(code: Status.Code): ErrorCategory = when (code) {
        Status.Code.UNAUTHENTICATED -> ErrorCategory.AUTHENTICATION
        Status.Code.PERMISSION_DENIED -> ErrorCategory.AUTHORIZATION
        Status.Code.INVALID_ARGUMENT,
        Status.Code.FAILED_PRECONDITION,
        Status.Code.OUT_OF_RANGE,
        -> ErrorCategory.VALIDATION
        Status.Code.INTERNAL,
        Status.Code.DATA_LOSS,
        Status.Code.OK,
        -> ErrorCategory.INTERNAL
        else -> ErrorCategory.NETWORK
    }

    private fun recoverabilityFor(code: Status.Code): Recoverability = when (code) {
        Status.Code.UNAVAILABLE,
        Status.Code.RESOURCE_EXHAUSTED,
        Status.Code.ABORTED,
        Status.Code.CANCELLED,
        Status.Code.DEADLINE_EXCEEDED,
        -> Recoverability.RECOVERABLE
        Status.Code.UNKNOWN -> Recoverability.UNKNOWN
        else -> Recoverability.NON_RECOVERABLE
    }
}
