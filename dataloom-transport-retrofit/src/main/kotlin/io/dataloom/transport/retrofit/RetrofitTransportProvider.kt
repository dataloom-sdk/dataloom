package io.dataloom.transport.retrofit

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import retrofit2.HttpException

/**
 * Reference [TransportProvider] that executes transport calls through Retrofit suspend APIs.
 *
 * This module is intentionally JVM/Android-only because Retrofit and OkHttp are JVM libraries.
 * It is an optional reference integration that applications may fork or replace.
 *
 * The provider remains protocol-agnostic by requiring application-owned mapping lambdas for:
 * - transforming DataLoom push/pull requests to app-owned Retrofit DTOs;
 * - invoking app-owned Retrofit service methods; and
 * - translating Retrofit results into canonical DataLoom transport outputs.
 *
 * The application chooses its Retrofit converter (Moshi, Gson, kotlinx.serialization, or custom)
 * when constructing the Retrofit instance used by the supplied call functions.
 *
 * Raw Retrofit/OkHttp errors do not escape [pushChanges] or [pullChanges]. Failures are mapped to
 * canonical [DataLoomError] values via [errorMapper].
 */
public class RetrofitTransportProvider<PushRequestBody, PushResponseBody, PullRequestBody, PullResponseBody>(
    override val descriptor: ProviderDescriptor,
    private val pushRequestMapper: (PushChangesRequest) -> PushRequestBody,
    private val pullRequestMapper: (PullChangesRequest) -> PullRequestBody,
    private val pushCall: suspend (PushRequestBody) -> PushResponseBody,
    private val pullCall: suspend (PullRequestBody) -> PullResponseBody,
    private val pushResponseMapper: (PushChangesRequest, PushResponseBody) -> ChangeSetAcknowledgement,
    private val pullResponseMapper: (PullChangesRequest, PullResponseBody) -> PullChangesResult,
    private val errorMapper: RetrofitTransportErrorMapper = RetrofitTransportErrorMapper.Default,
) : TransportProvider {

    init {
        require(descriptor.type == ProviderType.TRANSPORT) {
            "RetrofitTransportProvider descriptor.type must be TRANSPORT."
        }
    }

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> = executeTransportCall {
        val remoteRequest: PushRequestBody = pushRequestMapper(request)
        val remoteResponse: PushResponseBody = pushCall(remoteRequest)
        pushResponseMapper(request, remoteResponse)
    }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> = executeTransportCall {
        val remoteRequest: PullRequestBody = pullRequestMapper(request)
        val remoteResponse: PullResponseBody = pullCall(remoteRequest)
        pullResponseMapper(request, remoteResponse)
    }

    private suspend fun <T> executeTransportCall(block: suspend () -> T): ProviderOperationResult<T> {
        return try {
            ProviderOperationResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ProviderOperationResult.Failure(errorMapper.toDataLoomError(t))
        }
    }
}

/**
 * Maps Retrofit/OkHttp failures to canonical [DataLoomError] values.
 */
public fun interface RetrofitTransportErrorMapper {
    /** Maps [throwable] to a sanitized canonical [DataLoomError]. */
    public fun toDataLoomError(throwable: Throwable): DataLoomError

    public companion object {
        /**
         * Default Retrofit/OkHttp error mapper.
         *
         * Messages intentionally exclude headers, tokens, credentials, URLs, and payload content.
         */
        public val Default: RetrofitTransportErrorMapper =
            RetrofitTransportErrorMapper { throwable -> DefaultRetrofitTransportErrorMapper.map(throwable) }
    }
}

private object DefaultRetrofitTransportErrorMapper {
    fun map(throwable: Throwable): DataLoomError {
        return when (throwable) {
            is InterruptedIOException,
            -> if (throwable is SocketTimeoutException) {
                error(
                    code = "RETROFIT_TIMEOUT",
                    category = ErrorCategory.NETWORK,
                    severity = ErrorSeverity.ERROR,
                    recoverability = Recoverability.RECOVERABLE,
                    message = "Transport request timed out.",
                    cause = throwable,
                )
            } else {
                error(
                    code = "RETROFIT_NETWORK_IO",
                    category = ErrorCategory.NETWORK,
                    severity = ErrorSeverity.ERROR,
                    recoverability = Recoverability.RECOVERABLE,
                    message = "Transport network I/O was interrupted.",
                    cause = throwable,
                )
            }

            is HttpException -> mapHttpException(throwable)
            is IOException -> error(
                code = "RETROFIT_NETWORK_IO",
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "Transport network I/O failed.",
                cause = throwable,
            )

            else -> error(
                code = "RETROFIT_UNEXPECTED_FAILURE",
                category = ErrorCategory.PROVIDER,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.UNKNOWN,
                message = "Transport operation failed unexpectedly.",
                cause = throwable,
            )
        }
    }

    private fun mapHttpException(httpException: HttpException): DataLoomError {
        val statusCode: Int = httpException.code()
        return when (statusCode) {
            401 -> error(
                code = "RETROFIT_HTTP_401",
                category = ErrorCategory.AUTHENTICATION,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "Transport HTTP request was not authenticated (401).",
                cause = httpException,
            )

            403 -> error(
                code = "RETROFIT_HTTP_403",
                category = ErrorCategory.AUTHORIZATION,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "Transport HTTP request was not authorized (403).",
                cause = httpException,
            )

            409 -> error(
                code = "RETROFIT_HTTP_409",
                category = ErrorCategory.CONFLICT,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "Transport HTTP request conflicted with remote state (409).",
                cause = httpException,
            )

            400,
            404,
            422,
            -> error(
                code = "RETROFIT_HTTP_${statusCode}",
                category = ErrorCategory.VALIDATION,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "Transport HTTP request was invalid ($statusCode).",
                cause = httpException,
            )

            408,
            429,
            in 500..599,
            -> error(
                code = "RETROFIT_HTTP_${statusCode}",
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "Transport HTTP request failed with status $statusCode.",
                cause = httpException,
            )

            else -> error(
                code = "RETROFIT_HTTP_${statusCode}",
                category = ErrorCategory.PROVIDER,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.UNKNOWN,
                message = "Transport HTTP request failed with status $statusCode.",
                cause = httpException,
            )
        }
    }

    private fun error(
        code: String,
        category: ErrorCategory,
        severity: ErrorSeverity,
        recoverability: Recoverability,
        message: String,
        cause: Throwable,
    ): DataLoomError = RetrofitTransportError(
        code = ErrorCode(code),
        category = category,
        severity = severity,
        recoverability = recoverability,
        message = message,
        cause = cause,
    )

    private data class RetrofitTransportError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
