package io.dataloom.assets.transport.ktor

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.security.SystemDataLoomDigestCalculator
import io.dataloom.assets.AssetChunkPlan
import io.dataloom.assets.AssetChunkSizeBounds
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetIntegrityVerifier
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.memory.InMemoryAssetSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [KtorAssetProvider] is a thin HTTP client in front of a real network call;
 * every other `AssetProvider` in this codebase is local storage with no
 * network-shaped failure mode to exercise. These tests cover exactly the
 * failure shapes only a transport-backed provider has: a connection dropped
 * mid-upload, a non-2xx response the server did not annotate with the
 * documented error header, and a client-side request timeout. Each must map
 * to a typed [AssetTransferError] -- never an uncaught exception or a silent
 * success -- per [KtorAssetProvider]'s own documented error mapping.
 */
class KtorAssetProviderNetworkFailureTest {

    private val chunkBounds = AssetChunkSizeBounds(1, 64 * 1024 * 1024)

    private suspend fun oneChunkManifest(assetId: String): AssetManifest {
        val digests = SystemDataLoomDigestCalculator()
        val bytes = ByteArray(32) { it.toByte() }
        val plan = AssetChunkPlan(bytes.size.toLong(), bytes.size)
        return AssetIntegrityVerifier(digests).prepareManifest(
            AssetId(assetId),
            1L,
            AssetMediaType("application/octet-stream"),
            InMemoryAssetSource(bytes),
            plan,
            DigestAlgorithm.SHA_256,
        )
    }

    private fun kindOf(result: ProviderOperationResult<*>): AssetErrorKind {
        val failure = assertIs<ProviderOperationResult.Failure>(result)
        return (failure.error as AssetTransferError).kind
    }

    private fun providerWithClient(client: HttpClient): KtorAssetProvider = KtorAssetProvider.createForTesting(
        baseUrl = "https://assets.example.test",
        chunkSizeBounds = chunkBounds,
        httpClient = client,
        closeHttpClientOnClose = true,
    )

    @Test
    fun `a connection dropped mid-upload maps to a typed recoverable failure, not a crash`() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                // No response is ever produced: the engine itself fails, exactly as a
                // real dropped TCP connection would surface to the HTTP client.
                addHandler { throw IOException("Connection reset by peer") }
            }
        }
        val provider = providerWithClient(client)

        val result = provider.uploadChunk(AssetChunkUpload(AssetTransferSessionId("s-dropped"), 0, ByteArray(32)))

        assertEquals(AssetErrorKind.PROVIDER_UNAVAILABLE, kindOf(result))
        provider.close()
    }

    @Test
    fun `a non-2xx response without the documented error header still maps to a typed failure`() = runTest {
        val manifest = oneChunkManifest("unannotated")

        val serverFailure500 = providerWithClient(
            HttpClient(MockEngine) {
                engine { addHandler { respond(content = "boom", status = HttpStatusCode.InternalServerError) } }
            },
        )
        val result500 = serverFailure500.openUpload(AssetUploadRequest(AssetTransferSessionId("s-500"), manifest))
        assertEquals(AssetErrorKind.PROVIDER_UNAVAILABLE, kindOf(result500))
        serverFailure500.close()

        val serverFailure400 = providerWithClient(
            HttpClient(MockEngine) {
                engine { addHandler { respond(content = "bad", status = HttpStatusCode.BadRequest) } }
            },
        )
        val result400 = serverFailure400.openUpload(AssetUploadRequest(AssetTransferSessionId("s-400"), manifest))
        assertEquals(AssetErrorKind.PROVIDER_REJECTED, kindOf(result400))
        serverFailure400.close()
    }

    @Test
    fun `a response carrying an unrecognised error header falls back by status range`() = runTest {
        val manifest = oneChunkManifest("unrecognised-header")
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = "",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headers { append(ASSET_ERROR_HEADER, "NOT_A_REAL_KIND") },
                    )
                }
            }
        }
        val provider = providerWithClient(client)

        val result = provider.openUpload(AssetUploadRequest(AssetTransferSessionId("s-unrecognised"), manifest))

        assertEquals(AssetErrorKind.PROVIDER_UNAVAILABLE, kindOf(result))
        provider.close()
    }

    // Uses runBlocking, not runTest: HttpTimeout measures real wall-clock time, and
    // racing it against runTest's virtualized delay() would be a timing coincidence,
    // not a real proof (the same real-time-dependent-test rule other suites in this
    // repo already follow for circuit-breaker/lease timeouts).
    @Test
    fun `a client-side request timeout maps to a typed recoverable failure`() = runBlocking {
        val manifest = oneChunkManifest("timeout")
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    delay(500)
                    respond(content = "too-late", status = HttpStatusCode.OK)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 20
            }
        }
        val provider = providerWithClient(client)

        val result = provider.openUpload(AssetUploadRequest(AssetTransferSessionId("s-timeout"), manifest))

        assertEquals(AssetErrorKind.PROVIDER_UNAVAILABLE, kindOf(result))
        provider.close()
    }
}
