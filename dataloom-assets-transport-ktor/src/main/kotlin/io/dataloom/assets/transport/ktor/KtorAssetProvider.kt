package io.dataloom.assets.transport.ktor

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetManifestHistoryState
import io.dataloom.api.asset.AssetManifestHistoryStateCodec
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.assets.AssetChunkSizeBounds
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.AssetUploadStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.coroutines.CancellationException

/**
 * Reference [AssetProvider] implementation that performs real HTTP calls
 * against a remote asset-transfer endpoint, using the Ktor HTTP client.
 *
 * Every other `AssetProvider` in this codebase ([io.dataloom.assets.memory.InMemoryAssetProvider],
 * JVM/Android `FileAssetProvider`, Apple `AppleFileAssetProvider`) stores chunk
 * bytes locally; none of them transfers a byte over a network. This class is
 * the transport-backed one (gate `#97`): it owns no storage of its own and
 * instead speaks HTTP to a remote asset server, mapping every
 * [AssetProvider] operation onto one HTTP request and every HTTP response
 * back onto the exact [ProviderOperationResult]/[AssetErrorKind] vocabulary
 * [AssetProvider]'s contract already defines.
 *
 * ## No existing DataLoom asset-server spec -- the wire protocol is this
 * class's own documented decision
 *
 * There is no published DataLoom asset-serving backend to conform to, so the
 * protocol below is a deliberate, minimal, REST-ish design decision, exactly
 * as [io.dataloom.api.asset.AssetManifest]'s own KDoc documents its open
 * design choices (compression/encryption as open tokens, chunk geometry as a
 * descriptor list) rather than assuming they are settled. Any server this
 * provider talks to -- real or, in this module's own tests, a small
 * HTTP-facing adapter in front of [io.dataloom.assets.memory.InMemoryAssetProvider]
 * -- must implement exactly this protocol.
 *
 * ### Endpoints (all paths relative to the configured `baseUrl`)
 *
 * | Operation | Method | Path | Request body | Success body |
 * |---|---|---|---|---|
 * | [openUpload] | `PUT` | `/assets/uploads/{sessionId}` | manifest (see below) | committed chunk indices (see below) |
 * | [uploadChunk] | `PUT` | `/assets/uploads/{sessionId}/chunks/{index}` | raw chunk bytes | committed chunk indices |
 * | [completeUpload] | `POST` | `/assets/uploads/{sessionId}/complete` | (none) | the committed manifest |
 * | [abortUpload] | `DELETE` | `/assets/uploads/{sessionId}` | (none) | (empty) |
 * | [readManifest] | `GET` | `/assets/{assetId}/manifest?version={version}` (query omitted for the latest version) | (none) | the manifest |
 * | [readChunk] | `GET` | `/assets/{assetId}/versions/{version}/chunks/{index}` | (none) | raw chunk bytes |
 *
 * `sessionId`, `assetId`, `index` and `version` are percent-encoded path
 * segments ([io.ktor.http.appendPathSegments] encodes them; neither value
 * type restricts which characters it allows, see [AssetId] and
 * [AssetTransferSessionId]).
 *
 * ### Manifest wire encoding
 *
 * A manifest travels as the exact text [AssetManifestHistoryStateCodec]
 * already produces for one manifest (`AssetManifestHistoryState(listOf(manifest))`),
 * with content type `application/vnd.dataloom.asset-manifest+text`. This
 * reuses an existing, already-tested bounded V1 text codec instead of
 * inventing a second manifest serialization -- the same codec
 * `AssetTransferSessionCodec` already embeds for durable session persistence.
 *
 * ### Committed-chunk-indices wire encoding
 *
 * [openUpload] and [uploadChunk] both return an [AssetUploadStatus], whose
 * only server-known field beyond the (already client-known) session id is
 * [AssetUploadStatus.committedChunks]. The response body is therefore just
 * those indices, ascending, comma-separated (`"0,2,5"`), or an empty body for
 * none, content type `text/plain`.
 *
 * ### Error mapping -- a response header is authoritative, not the HTTP status alone
 *
 * [AssetErrorKind] has more members than generic HTTP status semantics can
 * distinguish (for example `SESSION_CONFLICT` and `ASSET_VERSION_CONFLICT`
 * are both naturally `409`). Every non-2xx response therefore carries a
 * `X-DataLoom-Asset-Error` header whose value is the exact [AssetErrorKind]
 * name; this provider maps the header back to that kind directly rather than
 * approximating it from the status code. The HTTP status code is still
 * chosen with conventional meaning (`404` not found, `409` conflict, `413`
 * quota, `416` out of range, `422` digest/length mismatch) so the protocol
 * remains inspectable with ordinary HTTP tooling, but it is never the
 * authoritative signal this provider decodes.
 *
 * A non-2xx response with a missing or unrecognised header falls back to
 * [AssetErrorKind.PROVIDER_UNAVAILABLE] for a `5xx` status (recoverable --
 * the remote is having a bad moment) or [AssetErrorKind.PROVIDER_REJECTED]
 * for anything else (non-recoverable -- an unexplained client-range
 * rejection). A transport-level failure that never produced an HTTP response
 * at all (connection refused, DNS failure, a request or connect/socket
 * timeout, or any other I/O exception) also maps to
 * [AssetErrorKind.PROVIDER_UNAVAILABLE], mirroring how
 * `io.dataloom.transport.ktor.KtorTransportProvider` -- this module's sibling
 * for change-event push/pull -- separates "no response" network failures from
 * classified HTTP failures. A malformed success response (a 2xx whose body
 * does not parse as the expected shape) maps to
 * [AssetErrorKind.PROVIDER_REJECTED]: the remote answered, but not sensibly.
 *
 * [CancellationException] is never caught or translated; it always
 * propagates.
 *
 * ## What this class does not do
 *
 * It performs no digest, length, quota, or conflict verification itself --
 * that is the remote server's job, exactly as it would be for a real asset
 * store ([AssetProvider]'s own contract documents those checks as the
 * provider's responsibility). This class only encodes requests, decodes
 * responses, and maps failures; see `AssetHttpTestServer` in this module's
 * tests for the reference adapter its own test suite runs against (an
 * HTTP-facing wrapper around [io.dataloom.assets.memory.InMemoryAssetProvider],
 * which already implements exactly that business logic).
 *
 * ## Platform scope
 *
 * JVM and native Android only (Android consumes this module's single `main`
 * source set directly, like `dataloom-transport-retrofit` and
 * `dataloom-transport-grpc`). No Kotlin/Native/Apple target: see this
 * module's own `build.gradle.kts` for why.
 *
 * Safe for concurrent use: each call opens its own request and this class
 * holds no mutable state beyond the (thread-safe) [HttpClient] itself.
 *
 * @param baseUrl absolute base URL of the remote asset endpoint, for example
 *   `https://assets.example.test`. Every request is sent to a path appended
 *   to this URL; see the endpoint table above.
 */
public class KtorAssetProvider private constructor(
    private val baseUrl: String,
    override val chunkSizeBounds: AssetChunkSizeBounds,
    private val httpClient: HttpClient,
    private val closeHttpClientOnClose: Boolean,
) : AssetProvider {

    init {
        require(baseUrl.isNotBlank()) { "KtorAssetProvider baseUrl must not be blank." }
    }

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.assets.transport.ktor-asset-provider"),
        name = ProviderName("KtorAssetProvider"),
        type = ProviderType.ASSET,
        version = ProviderVersion("1.0.0"),
    )

    override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    /**
     * Creates a provider backed by an internally managed Ktor client (the CIO
     * engine).
     *
     * @param chunkSizeBounds chunk sizes this provider declares it accepts;
     *   defaults to [AssetChunkSizeBounds.DEFAULT]. This provider does not
     *   validate chunk sizes itself -- the remote server does -- so the value
     *   here is advisory to callers (and to [AssetProviderContractKit]'s test
     *   fixtures), not enforced locally.
     */
    public constructor(
        baseUrl: String,
        chunkSizeBounds: AssetChunkSizeBounds = AssetChunkSizeBounds.DEFAULT,
        httpConfiguration: KtorAssetHttpConfiguration = KtorAssetHttpConfiguration(),
    ) : this(
        baseUrl = baseUrl,
        chunkSizeBounds = chunkSizeBounds,
        httpClient = createHttpClient(httpConfiguration),
        closeHttpClientOnClose = true,
    )

    internal companion object {
        internal fun createForTesting(
            baseUrl: String,
            chunkSizeBounds: AssetChunkSizeBounds,
            httpClient: HttpClient,
            closeHttpClientOnClose: Boolean,
        ): KtorAssetProvider = KtorAssetProvider(baseUrl, chunkSizeBounds, httpClient, closeHttpClientOnClose)
    }

    /** Releases the underlying HTTP client, if this instance owns it. */
    override suspend fun close(): ProviderOperationResult<Unit> {
        if (closeHttpClientOnClose) {
            httpClient.close()
        }
        return ProviderOperationResult.Success(Unit)
    }

    override suspend fun openUpload(request: AssetUploadRequest): ProviderOperationResult<AssetUploadStatus> {
        val manifestText = try {
            ManifestWire.encode(request.manifest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProviderOperationResult.Failure(
                AssetTransferError(AssetErrorKind.PROVIDER_REJECTED, "Could not encode the manifest for openUpload."),
            )
        }
        return execute(
            operation = AssetOperation.OPEN_UPLOAD,
            method = HttpMethod.Put,
            url = uploadUrl(request.sessionId),
            requestContentType = MANIFEST_CONTENT_TYPE,
            requestBody = manifestText.encodeToByteArray(),
        ) { statusCode, headers, body ->
            if (statusCode in SUCCESS_RANGE) {
                ProviderOperationResult.Success(AssetUploadStatus(request.sessionId, decodeCommitted(body)))
            } else {
                failureFromResponse(AssetOperation.OPEN_UPLOAD, statusCode, headers)
            }
        }
    }

    override suspend fun uploadChunk(request: AssetChunkUpload): ProviderOperationResult<AssetUploadStatus> =
        execute(
            operation = AssetOperation.UPLOAD_CHUNK,
            method = HttpMethod.Put,
            url = chunkUploadUrl(request.sessionId, request.index),
            requestContentType = OCTET_STREAM_CONTENT_TYPE,
            // Sent synchronously within this suspend call; never retained afterward.
            requestBody = request.bytes,
        ) { statusCode, headers, body ->
            if (statusCode in SUCCESS_RANGE) {
                ProviderOperationResult.Success(AssetUploadStatus(request.sessionId, decodeCommitted(body)))
            } else {
                failureFromResponse(AssetOperation.UPLOAD_CHUNK, statusCode, headers)
            }
        }

    override suspend fun completeUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<AssetManifest> =
        execute(
            operation = AssetOperation.COMPLETE_UPLOAD,
            method = HttpMethod.Post,
            url = completeUploadUrl(sessionId),
        ) { statusCode, headers, body ->
            if (statusCode in SUCCESS_RANGE) {
                ProviderOperationResult.Success(ManifestWire.decode(body.decodeToString()))
            } else {
                failureFromResponse(AssetOperation.COMPLETE_UPLOAD, statusCode, headers)
            }
        }

    override suspend fun abortUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<Unit> =
        execute(
            operation = AssetOperation.ABORT_UPLOAD,
            method = HttpMethod.Delete,
            url = uploadUrl(sessionId),
        ) { statusCode, headers, _ ->
            if (statusCode in SUCCESS_RANGE) {
                ProviderOperationResult.Success(Unit)
            } else {
                failureFromResponse(AssetOperation.ABORT_UPLOAD, statusCode, headers)
            }
        }

    override suspend fun readManifest(assetId: AssetId, version: Long?): ProviderOperationResult<AssetManifest> =
        execute(
            operation = AssetOperation.READ_MANIFEST,
            method = HttpMethod.Get,
            url = manifestUrl(assetId, version),
        ) { statusCode, headers, body ->
            if (statusCode in SUCCESS_RANGE) {
                ProviderOperationResult.Success(ManifestWire.decode(body.decodeToString()))
            } else {
                failureFromResponse(AssetOperation.READ_MANIFEST, statusCode, headers)
            }
        }

    override suspend fun readChunk(assetId: AssetId, version: Long, index: Int): ProviderOperationResult<ByteArray> =
        execute(
            operation = AssetOperation.READ_CHUNK,
            method = HttpMethod.Get,
            url = readChunkUrl(assetId, version, index),
        ) { statusCode, headers, body ->
            if (statusCode in SUCCESS_RANGE) {
                ProviderOperationResult.Success(body)
            } else {
                failureFromResponse(AssetOperation.READ_CHUNK, statusCode, headers)
            }
        }

    /**
     * Executes one HTTP request and maps its outcome, mirroring
     * `io.dataloom.transport.ktor.KtorTransportProvider.execute`'s own
     * three-phase shape: a transport-level exception (no response at all)
     * maps to [KtorAssetError.transportFailure]; a received response is
     * handed to [decode], and any exception [decode] throws (a malformed
     * success body) maps to [AssetErrorKind.PROVIDER_REJECTED].
     */
    private suspend fun <T> execute(
        operation: AssetOperation,
        method: HttpMethod,
        url: String,
        requestContentType: String? = null,
        requestBody: ByteArray? = null,
        decode: (statusCode: Int, headers: Map<String, List<String>>, body: ByteArray) -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> {
        val response: HttpResponse = try {
            httpClient.request {
                url(url)
                this.method = method
                requestContentType?.let { contentType(ContentType.parse(it)) }
                requestBody?.let { setBody(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProviderOperationResult.Failure(KtorAssetError.transportFailure(operation, e))
        }

        val bodyBytes: ByteArray = try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProviderOperationResult.Failure(KtorAssetError.transportFailure(operation, e))
        }

        val headers: Map<String, List<String>> = response.headers.entries()
            .associate { (name, values) -> name to values.toList() }

        return try {
            decode(response.status.value, headers, bodyBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderOperationResult.Failure(
                AssetTransferError(
                    AssetErrorKind.PROVIDER_REJECTED,
                    "Malformed response from the asset transport for ${operation.label}.",
                ),
            )
        }
    }

    private fun failureFromResponse(
        operation: AssetOperation,
        statusCode: Int,
        headers: Map<String, List<String>>,
    ): ProviderOperationResult.Failure {
        val headerValue: String? = headers.entries
            .firstOrNull { (name, _) -> name.equals(ASSET_ERROR_HEADER, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
        val kind: AssetErrorKind = headerValue
            ?.let { runCatching { AssetErrorKind.valueOf(it) }.getOrNull() }
            ?: if (statusCode in 500..599) AssetErrorKind.PROVIDER_UNAVAILABLE else AssetErrorKind.PROVIDER_REJECTED
        return ProviderOperationResult.Failure(
            AssetTransferError(kind, "The asset transport reported HTTP $statusCode for ${operation.label}."),
        )
    }

    private fun decodeCommitted(body: ByteArray): Set<Int> {
        val text = body.decodeToString().trim()
        if (text.isEmpty()) return emptySet()
        return text.split(",").map { it.trim().toInt() }.toSet()
    }

    private fun uploadUrl(sessionId: AssetTransferSessionId): String =
        URLBuilder(baseUrl).apply { appendPathSegments("assets", "uploads", sessionId.value) }.buildString()

    private fun chunkUploadUrl(sessionId: AssetTransferSessionId, index: Int): String =
        URLBuilder(baseUrl).apply {
            appendPathSegments("assets", "uploads", sessionId.value, "chunks", index.toString())
        }.buildString()

    private fun completeUploadUrl(sessionId: AssetTransferSessionId): String =
        URLBuilder(baseUrl).apply {
            appendPathSegments("assets", "uploads", sessionId.value, "complete")
        }.buildString()

    private fun manifestUrl(assetId: AssetId, version: Long?): String =
        URLBuilder(baseUrl).apply {
            appendPathSegments("assets", assetId.value, "manifest")
            version?.let { parameters.append("version", it.toString()) }
        }.buildString()

    private fun readChunkUrl(assetId: AssetId, version: Long, index: Int): String =
        URLBuilder(baseUrl).apply {
            appendPathSegments("assets", assetId.value, "versions", version.toString(), "chunks", index.toString())
        }.buildString()
}

private const val MANIFEST_CONTENT_TYPE: String = "application/vnd.dataloom.asset-manifest+text"
private const val OCTET_STREAM_CONTENT_TYPE: String = "application/octet-stream"
private val SUCCESS_RANGE: IntRange = 200..299

/**
 * Optional client-level timeout configuration for [KtorAssetProvider].
 * Identical shape to `io.dataloom.transport.ktor.KtorTransportHttpConfiguration`.
 */
public data class KtorAssetHttpConfiguration(
    /** Total request timeout in milliseconds. `null` disables the client timeout. */
    public val requestTimeoutMillis: Long? = null,
    /** Connection timeout in milliseconds. `null` uses the engine default. */
    public val connectTimeoutMillis: Long? = null,
    /** Socket/read timeout in milliseconds. `null` uses the engine default. */
    public val socketTimeoutMillis: Long? = null,
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

private fun createHttpClient(configuration: KtorAssetHttpConfiguration): HttpClient = HttpClient(CIO) {
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

/** The response header carrying the exact [AssetErrorKind] name for a non-2xx response. */
internal const val ASSET_ERROR_HEADER: String = "X-DataLoom-Asset-Error"

private enum class AssetOperation(val label: String) {
    OPEN_UPLOAD("openUpload"),
    UPLOAD_CHUNK("uploadChunk"),
    COMPLETE_UPLOAD("completeUpload"),
    ABORT_UPLOAD("abortUpload"),
    READ_MANIFEST("readManifest"),
    READ_CHUNK("readChunk"),
}

/**
 * Shared manifest wire encoding: reuses [AssetManifestHistoryStateCodec] for
 * exactly one manifest rather than inventing a second serialization. Used by
 * [KtorAssetProvider] and, in this module's tests, by the HTTP-facing test
 * adapter that stands in for a real asset server.
 */
internal object ManifestWire {
    private val codec = AssetManifestHistoryStateCodec()

    fun encode(manifest: AssetManifest): String = codec.encode(AssetManifestHistoryState(listOf(manifest)))

    fun decode(text: String): AssetManifest = codec.decode(text).retainedManifests.single()
}

private object KtorAssetError {
    fun transportFailure(operation: AssetOperation, cause: Exception): AssetTransferError = when (cause) {
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> AssetTransferError(
            AssetErrorKind.PROVIDER_UNAVAILABLE,
            "The asset transport timed out while executing ${operation.label}.",
        )

        is UnresolvedAddressException -> AssetTransferError(
            AssetErrorKind.PROVIDER_UNAVAILABLE,
            "The asset transport could not resolve the remote endpoint for ${operation.label}.",
        )

        else -> AssetTransferError(
            AssetErrorKind.PROVIDER_UNAVAILABLE,
            "The asset transport failed while executing ${operation.label}.",
        )
    }
}
