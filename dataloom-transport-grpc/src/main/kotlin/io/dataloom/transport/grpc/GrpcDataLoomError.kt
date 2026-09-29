package io.dataloom.transport.grpc

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.RetryDelayHint
import io.dataloom.api.error.RetryDelayHintCarrier

/**
 * Concrete [DataLoomError] produced by the gRPC transport provider.
 *
 * [cause] may reference a gRPC [io.grpc.StatusException] or
 * [io.grpc.StatusRuntimeException] for diagnostics, but the raw exception is
 * not part of the public contract and must not cross the [GrpcTransportProvider]
 * API boundary.
 *
 * **Sensitive-data restriction:** [message] must not contain credentials,
 * tokens, keys, personal data, or complete application payloads.
 *
 * @param code stable machine-readable code for this error.
 * @param category technology-neutral error category.
 * @param severity canonical severity.
 * @param recoverability canonical recoverability classification.
 * @param message sanitized human-readable diagnostic summary.
 * @param cause optional underlying cause, retained for diagnostics only.
 */
public data class GrpcDataLoomError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable?,
) : DataLoomError

/**
 * [GrpcDataLoomError] carrying normalized `grpc-retry-pushback-ms` guidance.
 *
 * Produced only for a RESOURCE_EXHAUSTED or UNAVAILABLE failure whose status
 * trailers carried a valid, non-negative `grpc-retry-pushback-ms` value; see
 * [GrpcStatusMapper]. All other properties and restrictions mirror
 * [GrpcDataLoomError].
 *
 * @param retryDelayHint normalized, bounded minimum delay guidance.
 */
public data class GrpcRetryableDataLoomError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable?,
    override val retryDelayHint: RetryDelayHint,
) : DataLoomError, RetryDelayHintCarrier
