package io.dataloom.assets.transport.ktor

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.AssetUploadStatus
import io.dataloom.api.identifier.AssetId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.http.headersOf

/**
 * Test-only HTTP-facing adapter in front of a delegate [AssetProvider]
 * (normally [io.dataloom.assets.memory.InMemoryAssetProvider]), implementing
 * exactly the wire protocol [KtorAssetProvider] documents in its own KDoc.
 *
 * This is the "remote asset server" [KtorAssetProvider]'s own test suite
 * talks to, via [io.ktor.client.engine.mock.MockEngine]. It deliberately does
 * not re-implement any digest/quota/conflict business logic: that already
 * exists, already tested, in the delegate. This class is only the HTTP
 * translation layer -- parsing a request into an [AssetProvider] call and
 * encoding the [ProviderOperationResult] back onto the documented wire
 * shapes -- so running [KtorAssetProvider] through
 * [io.dataloom.assets.testkit.AssetProviderContractKit] against this adapter
 * genuinely exercises the client's HTTP request/response handling, not a
 * second copy of the provider contract's own rules.
 */
internal class AssetHttpTestServer(private val delegate: AssetProvider) {

    /**
     * Builds an [HttpClient] whose [MockEngine] routes every request through
     * [handle]. [configure] can install extra client plugins (for example
     * [io.ktor.client.plugins.HttpTimeout], to exercise a client-side
     * timeout against a handler that delays).
     */
    fun client(
        configure: io.ktor.client.HttpClientConfig<io.ktor.client.engine.mock.MockEngineConfig>.() -> Unit = {},
    ): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request -> handle(request) }
        }
        configure()
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val segments: List<String> = request.url.pathSegments.filter { it.isNotEmpty() }
        return when {
            request.method == HttpMethod.Put && segments.size == 3 && segments[0] == "assets" && segments[1] == "uploads" ->
                openUpload(AssetTransferSessionId(segments[2]), request.body.toByteArray())

            request.method == HttpMethod.Put && segments.size == 5 && segments[0] == "assets" &&
                segments[1] == "uploads" && segments[3] == "chunks" ->
                uploadChunk(AssetTransferSessionId(segments[2]), segments[4].toInt(), request.body.toByteArray())

            request.method == HttpMethod.Post && segments.size == 4 && segments[0] == "assets" &&
                segments[1] == "uploads" && segments[3] == "complete" ->
                completeUpload(AssetTransferSessionId(segments[2]))

            request.method == HttpMethod.Delete && segments.size == 3 && segments[0] == "assets" && segments[1] == "uploads" ->
                abortUpload(AssetTransferSessionId(segments[2]))

            request.method == HttpMethod.Get && segments.size == 3 && segments[0] == "assets" && segments[2] == "manifest" ->
                readManifest(AssetId(segments[1]), request.url.parameters["version"]?.toLong())

            request.method == HttpMethod.Get && segments.size == 6 && segments[0] == "assets" &&
                segments[2] == "versions" && segments[4] == "chunks" ->
                readChunk(AssetId(segments[1]), segments[3].toLong(), segments[5].toInt())

            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private suspend fun MockRequestHandleScope.openUpload(
        sessionId: AssetTransferSessionId,
        body: ByteArray,
    ): HttpResponseData {
        val manifest = try {
            ManifestWire.decode(body.decodeToString())
        } catch (e: Exception) {
            return respondFailure(AssetErrorKind.PROVIDER_REJECTED)
        }
        return respondStatus(delegate.openUpload(AssetUploadRequest(sessionId, manifest)))
    }

    private suspend fun MockRequestHandleScope.uploadChunk(
        sessionId: AssetTransferSessionId,
        index: Int,
        bytes: ByteArray,
    ): HttpResponseData = respondStatus(delegate.uploadChunk(AssetChunkUpload(sessionId, index, bytes)))

    private suspend fun MockRequestHandleScope.completeUpload(sessionId: AssetTransferSessionId): HttpResponseData {
        return when (val result = delegate.completeUpload(sessionId)) {
            is ProviderOperationResult.Success -> respond(
                content = ManifestWire.encode(result.value),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
            is ProviderOperationResult.Failure -> respondFailure(kindOf(result))
        }
    }

    private suspend fun MockRequestHandleScope.abortUpload(sessionId: AssetTransferSessionId): HttpResponseData {
        delegate.abortUpload(sessionId)
        return respond("", HttpStatusCode.OK)
    }

    private suspend fun MockRequestHandleScope.readManifest(assetId: AssetId, version: Long?): HttpResponseData =
        when (val result = delegate.readManifest(assetId, version)) {
            is ProviderOperationResult.Success -> respond(
                content = ManifestWire.encode(result.value),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
            is ProviderOperationResult.Failure -> respondFailure(kindOf(result))
        }

    private suspend fun MockRequestHandleScope.readChunk(assetId: AssetId, version: Long, index: Int): HttpResponseData =
        when (val result = delegate.readChunk(assetId, version, index)) {
            is ProviderOperationResult.Success -> respond(
                content = result.value,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
            is ProviderOperationResult.Failure -> respondFailure(kindOf(result))
        }

    private fun MockRequestHandleScope.respondStatus(
        result: ProviderOperationResult<AssetUploadStatus>,
    ): HttpResponseData = when (result) {
        is ProviderOperationResult.Success -> respond(
            content = result.value.committedChunks.sorted().joinToString(","),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
        )
        is ProviderOperationResult.Failure -> respondFailure(kindOf(result))
    }

    private fun kindOf(failure: ProviderOperationResult.Failure): AssetErrorKind =
        (failure.error as? AssetTransferError)?.kind ?: AssetErrorKind.PROVIDER_REJECTED

    private fun MockRequestHandleScope.respondFailure(kind: AssetErrorKind): HttpResponseData = respond(
        content = "",
        status = HttpStatusCode.fromValue(httpStatusFor(kind)),
        headers = headers { append(ASSET_ERROR_HEADER, kind.name) },
    )

    /**
     * The HTTP status this test server uses for each [AssetErrorKind],
     * matching the conventional-meaning table [KtorAssetProvider]'s KDoc
     * documents. Not authoritative to the client -- [ASSET_ERROR_HEADER] is --
     * but kept consistent so the protocol stays inspectable with ordinary
     * HTTP tooling.
     */
    private fun httpStatusFor(kind: AssetErrorKind): Int = when (kind) {
        AssetErrorKind.SESSION_NOT_FOUND, AssetErrorKind.ASSET_NOT_FOUND -> 404
        AssetErrorKind.SESSION_CONFLICT, AssetErrorKind.ASSET_VERSION_CONFLICT, AssetErrorKind.INCOMPLETE_UPLOAD -> 409
        AssetErrorKind.QUOTA_EXCEEDED -> 413
        AssetErrorKind.CHUNK_OUT_OF_RANGE -> 416
        AssetErrorKind.CHUNK_DIGEST_MISMATCH, AssetErrorKind.CHUNK_LENGTH_MISMATCH, AssetErrorKind.OBJECT_DIGEST_MISMATCH -> 422
        AssetErrorKind.PROVIDER_UNAVAILABLE -> 503
        else -> 400
    }
}
