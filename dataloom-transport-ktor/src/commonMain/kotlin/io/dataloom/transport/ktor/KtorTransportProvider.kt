package io.dataloom.transport.ktor

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.RetryDelayHint
import io.dataloom.api.error.RetryDelayHintCarrier
import io.dataloom.api.error.RetryDelayHintSource
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.coroutines.CancellationException

/**
 * Reference [TransportProvider] implementation backed by the Ktor HTTP client.
 *
 * This optional module is a reference adapter that applications may use, fork,
 * or replace. Endpoint selection, authentication headers, and payload
 * serialization remain application-owned through [codec].
 *
 * The provider is safe for concurrent use when the supplied [codec] is safe for
 * concurrent use.
 */
public class KtorTransportProvider private constructor(
    private val codec: KtorTransportCodec,
    override val descriptor: ProviderDescriptor,
    private val httpClient: HttpClient,
    private val closeHttpClientOnClose: Boolean,
) : TransportProvider {
    init {
        require(descriptor.type == ProviderType.TRANSPORT) {
            "KtorTransportProvider descriptor.type must be TRANSPORT."
        }
    }

    /**
     * Creates a provider backed by an internally managed Ktor client.
     */
    public constructor(
        codec: KtorTransportCodec,
        descriptor: ProviderDescriptor = defaultDescriptor(),
        httpConfiguration: KtorTransportHttpConfiguration = KtorTransportHttpConfiguration(),
    ) : this(
        codec = codec,
        descriptor = descriptor,
        httpClient = createHttpClient(httpConfiguration),
        closeHttpClientOnClose = true,
    )

    internal companion object {
        internal fun createForTesting(
            codec: KtorTransportCodec,
            descriptor: ProviderDescriptor,
            httpClient: HttpClient,
            closeHttpClientOnClose: Boolean,
        ): KtorTransportProvider = KtorTransportProvider(
                codec = codec,
                descriptor = descriptor,
                httpClient = httpClient,
                closeHttpClientOnClose = closeHttpClientOnClose,
            )
    }

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> {
        if (closeHttpClientOnClose) {
            httpClient.close()
        }
        return ProviderOperationResult.Success(Unit)
    }

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> = execute(
        operation = TransportOperation.PUSH,
        encode = { codec.encodePushRequest(request) },
        decode = { response -> codec.decodePushResponse(request, response) },
    )

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> = execute(
        operation = TransportOperation.PULL,
        encode = { codec.encodePullRequest(request) },
        decode = { response -> codec.decodePullResponse(request, response) },
    )

    private suspend fun <T> execute(
        operation: TransportOperation,
        encode: suspend () -> KtorTransportHttpRequest,
        decode: suspend (KtorTransportHttpResponse) -> T,
    ): ProviderOperationResult<T> {
        val outboundRequest: KtorTransportHttpRequest = try {
            encode()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return ProviderOperationResult.Failure(
                KtorTransportError.serializationFailure(operation),
            )
        }

        val httpResponse: KtorTransportHttpResponse = try {
            executeHttpRequest(outboundRequest)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return ProviderOperationResult.Failure(
                KtorTransportError.transportFailure(operation, exception),
            )
        }

        if (httpResponse.statusCode !in 200..299) {
            return ProviderOperationResult.Failure(
                KtorTransportError.httpFailure(operation, httpResponse),
            )
        }

        return try {
            ProviderOperationResult.Success(decode(httpResponse))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            ProviderOperationResult.Failure(
                KtorTransportError.serializationFailure(operation),
            )
        }
    }

    private suspend fun executeHttpRequest(
        request: KtorTransportHttpRequest,
    ): KtorTransportHttpResponse {
        val response: HttpResponse = httpClient.request {
            url(request.url)
            applyRequest(request)
        }
        val body: ByteArray = response.body()
        return KtorTransportHttpResponse(
            statusCode = response.status.value,
            headers = response.headers.entries().associate { (name, values) -> name to values.toList() },
            body = body,
            contentType = response.contentType()?.toString(),
        )
    }

    private fun HttpRequestBuilder.applyRequest(request: KtorTransportHttpRequest) {
        method = request.method.toKtorHttpMethod()
        request.headers.forEach { (name, values) ->
            values.forEach { value ->
                headers.append(name, value)
            }
        }
        request.contentType?.let { contentType(ContentType.parse(it)) }
        request.copyBody()?.let { body -> setBody(body) }
    }
}

private fun createHttpClient(
    configuration: KtorTransportHttpConfiguration,
): HttpClient = HttpClient {
    followRedirects = configuration.followRedirects
    if (
        configuration.requestTimeoutMillis != null ||
        configuration.connectTimeoutMillis != null ||
        configuration.socketTimeoutMillis != null
    ) {
        install(HttpTimeout) {
            requestTimeoutMillis = configuration.requestTimeoutMillis
            connectTimeoutMillis = configuration.connectTimeoutMillis
            socketTimeoutMillis = configuration.socketTimeoutMillis
        }
    }
}

private enum class TransportOperation(
    val label: String,
) {
    PUSH(label = "pushChanges"),
    PULL(label = "pullChanges"),
}

private fun KtorTransportHttpMethod.toKtorHttpMethod(): HttpMethod = when (this) {
    KtorTransportHttpMethod.GET -> HttpMethod.Get
    KtorTransportHttpMethod.POST -> HttpMethod.Post
    KtorTransportHttpMethod.PUT -> HttpMethod.Put
    KtorTransportHttpMethod.PATCH -> HttpMethod.Patch
    KtorTransportHttpMethod.DELETE -> HttpMethod.Delete
    KtorTransportHttpMethod.HEAD -> HttpMethod.Head
}

private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId("io.dataloom.transport.ktor"),
    name = ProviderName("KtorTransportProvider"),
    type = ProviderType.TRANSPORT,
    version = ProviderVersion("1.0.0"),
    capabilities = setOf(
        ProviderCapability("reference-implementation"),
        ProviderCapability("http-request-response"),
    ),
)

private object KtorTransportError {
    fun serializationFailure(operation: TransportOperation): DataLoomError = BasicError(
        code = ErrorCode("KTOR_TRANSPORT_SERIALIZATION_FAILURE"),
        category = ErrorCategory.SERIALIZATION,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The Ktor transport provider could not encode or decode ${operation.label} payloads.",
    )

    fun transportFailure(
        operation: TransportOperation,
        cause: Exception,
    ): DataLoomError = when (cause) {
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> RemoteError(
            code = ErrorCode("KTOR_TRANSPORT_TIMEOUT"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.UNKNOWN,
            message = "The Ktor transport provider timed out while executing ${operation.label}.",
            remoteOutcome = StrategyRemoteOutcome.TIMEOUT,
        )

        is UnresolvedAddressException -> RemoteError(
            code = ErrorCode("KTOR_TRANSPORT_UNAVAILABLE"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "The Ktor transport provider could not resolve the remote endpoint for ${operation.label}.",
            remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
        )

        is IllegalArgumentException -> BasicError(
            code = ErrorCode("KTOR_TRANSPORT_CONFIGURATION_FAILURE"),
            category = ErrorCategory.CONFIGURATION,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "The Ktor transport provider rejected its HTTP request configuration for ${operation.label}.",
        )

        else -> RemoteError(
            code = ErrorCode("KTOR_TRANSPORT_NETWORK_FAILURE"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "The Ktor transport provider failed while executing ${operation.label}.",
            remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
        )
    }

    fun httpFailure(
        operation: TransportOperation,
        response: KtorTransportHttpResponse,
    ): DataLoomError {
        val statusCode: Int = response.statusCode
        return when {
            statusCode == 401 -> RemoteError(
                code = ErrorCode("KTOR_TRANSPORT_AUTHENTICATION_FAILURE"),
                category = ErrorCategory.AUTHENTICATION,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "The remote endpoint rejected ${operation.label} with HTTP 401.",
                remoteOutcome = StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
            )

            statusCode == 403 -> RemoteError(
                code = ErrorCode("KTOR_TRANSPORT_AUTHORIZATION_FAILURE"),
                category = ErrorCategory.AUTHORIZATION,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "The remote endpoint rejected ${operation.label} with HTTP 403.",
                remoteOutcome = StrategyRemoteOutcome.AUTHORIZATION_FAILURE,
            )

            statusCode == 408 || statusCode == 504 -> RemoteError(
                code = ErrorCode("KTOR_TRANSPORT_REMOTE_TIMEOUT"),
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.UNKNOWN,
                message = "The remote endpoint timed out while processing ${operation.label}.",
                remoteOutcome = StrategyRemoteOutcome.TIMEOUT,
            )

            statusCode == 409 -> RemoteError(
                code = ErrorCode("KTOR_TRANSPORT_CONFLICT"),
                category = ErrorCategory.CONFLICT,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "The remote endpoint reported a conflict for ${operation.label}.",
                remoteOutcome = StrategyRemoteOutcome.CONFLICT,
            )

            statusCode == 429 -> {
                val retryDelayHint: RetryDelayHint? = parseRetryDelayHint(response)
                if (retryDelayHint == null) {
                    RemoteError(
                        code = ErrorCode("KTOR_TRANSPORT_RATE_LIMITED"),
                        category = ErrorCategory.NETWORK,
                        severity = ErrorSeverity.ERROR,
                        recoverability = Recoverability.RECOVERABLE,
                        message = "The remote endpoint rate limited ${operation.label}.",
                        remoteOutcome = StrategyRemoteOutcome.RATE_LIMITED,
                    )
                } else {
                    RetryableRemoteError(
                        code = ErrorCode("KTOR_TRANSPORT_RATE_LIMITED"),
                        category = ErrorCategory.NETWORK,
                        severity = ErrorSeverity.ERROR,
                        recoverability = Recoverability.RECOVERABLE,
                        message = "The remote endpoint rate limited ${operation.label}.",
                        remoteOutcome = StrategyRemoteOutcome.RATE_LIMITED,
                        retryDelayHint = retryDelayHint,
                    )
                }
            }

            statusCode in 400..499 -> RemoteError(
                code = ErrorCode("KTOR_TRANSPORT_VALIDATION_FAILURE"),
                category = ErrorCategory.VALIDATION,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "The remote endpoint rejected ${operation.label} with HTTP $statusCode.",
                remoteOutcome = StrategyRemoteOutcome.VALIDATION_FAILURE,
            )

            statusCode == 502 || statusCode == 503 -> RemoteError(
                code = ErrorCode("KTOR_TRANSPORT_REMOTE_UNAVAILABLE"),
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "The remote endpoint was unavailable for ${operation.label} (HTTP $statusCode).",
                remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
            )

            statusCode in 500..599 -> RemoteError(
                code = ErrorCode("KTOR_TRANSPORT_SERVER_FAILURE"),
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "The remote endpoint failed while handling ${operation.label} (HTTP $statusCode).",
                remoteOutcome = StrategyRemoteOutcome.SERVER_FAILURE,
            )

            else -> RemoteError(
                code = ErrorCode("KTOR_TRANSPORT_UNKNOWN_REMOTE_FAILURE"),
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.UNKNOWN,
                message = "The remote endpoint returned HTTP $statusCode for ${operation.label}.",
                remoteOutcome = StrategyRemoteOutcome.UNKNOWN_FAILURE,
            )
        }
    }

    private fun parseRetryDelayHint(response: KtorTransportHttpResponse): RetryDelayHint? {
        val retryAfterHeader: String = response.headers.entries.firstOrNull { (name, _) ->
            name.equals("Retry-After", ignoreCase = true)
        }?.value?.firstOrNull() ?: return null
        val seconds: Long = retryAfterHeader.toLongOrNull() ?: return null
        return RetryDelayHint(
            delayMilliseconds = seconds * 1000L,
            source = RetryDelayHintSource.SERVER,
        )
    }

    private data class BasicError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class RemoteError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val remoteOutcome: StrategyRemoteOutcome,
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError

    private data class RetryableRemoteError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val remoteOutcome: StrategyRemoteOutcome,
        override val retryDelayHint: RetryDelayHint,
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError, RetryDelayHintCarrier
}
