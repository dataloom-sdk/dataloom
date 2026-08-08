package io.dataloom.transport.ktor

import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest

/**
 * Application-owned codec boundary for [KtorTransportProvider].
 *
 * The codec owns endpoint selection, authentication headers, payload encoding,
 * and response decoding. DataLoom payloads remain opaque to the provider.
 */
public interface KtorTransportCodec {
    /**
     * Encodes an outbound push request into an HTTP request.
     *
     * Returned header names and values are sent as-is, but the request
     * representation redacts header values from [toString].
     */
    public suspend fun encodePushRequest(request: PushChangesRequest): KtorTransportHttpRequest

    /**
     * Decodes a successful 2xx push response.
     *
     * This method is called only after the provider receives a 2xx HTTP status.
     */
    public suspend fun decodePushResponse(
        request: PushChangesRequest,
        response: KtorTransportHttpResponse,
    ): ChangeSetAcknowledgement

    /**
     * Encodes an inbound pull request into an HTTP request.
     */
    public suspend fun encodePullRequest(request: PullChangesRequest): KtorTransportHttpRequest

    /**
     * Decodes a successful 2xx pull response.
     *
     * This method is called only after the provider receives a 2xx HTTP status.
     */
    public suspend fun decodePullResponse(
        request: PullChangesRequest,
        response: KtorTransportHttpResponse,
    ): PullChangesResult
}

/**
 * Supported HTTP methods for [KtorTransportProvider] requests.
 */
public enum class KtorTransportHttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    HEAD,
}

/**
 * Immutable outbound HTTP request prepared by a [KtorTransportCodec].
 *
 * Header values and body content are intentionally omitted from [toString] so
 * credentials, tokens, and opaque payload bytes do not appear in diagnostics.
 */
public class KtorTransportHttpRequest(
    /** HTTP method to execute. */
    public val method: KtorTransportHttpMethod,
    /** Absolute target URL. */
    public val url: String,
    headers: Map<String, List<String>> = emptyMap(),
    body: ByteArray? = null,
    /** Optional content type for [body]. */
    public val contentType: String? = null,
) {
    init {
        require(url.isNotBlank()) { "KtorTransportHttpRequest url must not be blank." }
    }

    private val headerSnapshot: Map<String, List<String>> = headers.entries
        .associate { (name, values) -> name to values.toList() }
    private val bodySnapshot: ByteArray? = body?.copyOf()

    /** Immutable snapshot of request headers. */
    public val headers: Map<String, List<String>>
        get() = headerSnapshot.mapValues { (_, values) -> values.toList() }

    /** Number of bytes in [body], or zero when no body is supplied. */
    public val bodySize: Int
        get() = bodySnapshot?.size ?: 0

    /** Returns a defensive copy of the request body, if present. */
    public fun copyBody(): ByteArray? = bodySnapshot?.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KtorTransportHttpRequest) return false

        return method == other.method &&
            url == other.url &&
            headerSnapshot == other.headerSnapshot &&
            contentType == other.contentType &&
            ((bodySnapshot == null && other.bodySnapshot == null) ||
                (bodySnapshot != null && other.bodySnapshot != null &&
                    bodySnapshot.contentEquals(other.bodySnapshot)))
    }

    override fun hashCode(): Int {
        var result: Int = method.hashCode()
        result = (31 * result) + url.hashCode()
        result = (31 * result) + headerSnapshot.hashCode()
        result = (31 * result) + (bodySnapshot?.contentHashCode() ?: 0)
        result = (31 * result) + (contentType?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "KtorTransportHttpRequest(method=$method, url=$url, headerNames=${headerSnapshot.keys}, " +
            "bodySize=$bodySize, contentType=$contentType)"
}

/**
 * Immutable HTTP response exposed to a [KtorTransportCodec].
 *
 * Header values and body content are intentionally omitted from [toString] so
 * sensitive response data cannot leak through routine diagnostics.
 */
public class KtorTransportHttpResponse(
    /** Numeric HTTP status code. */
    public val statusCode: Int,
    headers: Map<String, List<String>> = emptyMap(),
    body: ByteArray = ByteArray(0),
    /** Optional response content type. */
    public val contentType: String? = null,
) {
    init {
        require(statusCode in 100..599) {
            "KtorTransportHttpResponse statusCode must be in the HTTP status range, but was $statusCode."
        }
    }

    private val headerSnapshot: Map<String, List<String>> = headers.entries
        .associate { (name, values) -> name to values.toList() }
    private val bodySnapshot: ByteArray = body.copyOf()

    /** Immutable snapshot of response headers. */
    public val headers: Map<String, List<String>>
        get() = headerSnapshot.mapValues { (_, values) -> values.toList() }

    /** Number of bytes in the response body. */
    public val bodySize: Int
        get() = bodySnapshot.size

    /** Returns a defensive copy of the response body. */
    public fun copyBody(): ByteArray = bodySnapshot.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KtorTransportHttpResponse) return false

        return statusCode == other.statusCode &&
            headerSnapshot == other.headerSnapshot &&
            contentType == other.contentType &&
            bodySnapshot.contentEquals(other.bodySnapshot)
    }

    override fun hashCode(): Int {
        var result: Int = statusCode
        result = (31 * result) + headerSnapshot.hashCode()
        result = (31 * result) + bodySnapshot.contentHashCode()
        result = (31 * result) + (contentType?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "KtorTransportHttpResponse(statusCode=$statusCode, headerNames=${headerSnapshot.keys}, " +
            "bodySize=$bodySize, contentType=$contentType)"
}

/**
 * Optional client-level timeout configuration for [KtorTransportProvider].
 */
public data class KtorTransportHttpConfiguration(
    /** Total request timeout in milliseconds. `null` disables the client timeout. */
    public val requestTimeoutMillis: Long? = null,
    /** Connection timeout in milliseconds. `null` uses the engine default. */
    public val connectTimeoutMillis: Long? = null,
    /** Socket/read timeout in milliseconds. `null` uses the engine default. */
    public val socketTimeoutMillis: Long? = null,
    /** Whether HTTP redirects should be followed automatically. */
    public val followRedirects: Boolean = false,
) {
    init {
        require(requestTimeoutMillis == null || requestTimeoutMillis > 0L) {
            "requestTimeoutMillis must be greater than zero when supplied."
        }
        require(connectTimeoutMillis == null || connectTimeoutMillis > 0L) {
            "connectTimeoutMillis must be greater than zero when supplied."
        }
        require(socketTimeoutMillis == null || socketTimeoutMillis > 0L) {
            "socketTimeoutMillis must be greater than zero when supplied."
        }
    }
}
