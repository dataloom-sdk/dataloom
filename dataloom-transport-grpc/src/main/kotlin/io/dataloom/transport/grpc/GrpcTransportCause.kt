package io.dataloom.transport.grpc

/**
 * Transport-neutral wrapper for the underlying gRPC failure that produced a
 * [GrpcDataLoomError].
 *
 * Storing this wrapper as [GrpcDataLoomError.cause] avoids leaking raw
 * `io.grpc.StatusException` or `io.grpc.StatusRuntimeException` types through
 * the public [io.dataloom.api.error.DataLoomError] API surface. Callers
 * receive a [Throwable] whose type does not reference any `io.grpc.*` class.
 *
 * The sanitized [message] describes the failure using the gRPC status code
 * name only — it must not include credentials, call metadata, tokens, headers,
 * or personal data.
 *
 * This class is `internal` and intentionally not part of the public API of
 * `dataloom-transport-grpc`.
 */
internal class GrpcTransportCause(
    message: String,
    cause: Throwable,
) : Exception(message, cause)
