package io.dataloom.assets.transport.ktor

import io.dataloom.api.security.SystemDataLoomDigestCalculator
import io.dataloom.assets.AssetChunkSizeBounds
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetQuota
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.testkit.AssetProviderContractKit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves [KtorAssetProvider] satisfies the exact same behavioural contract as
 * every other [AssetProvider] in `dataloom-assets`
 * ([io.dataloom.assets.memory.InMemoryAssetProvider], JVM `FileAssetProvider`,
 * Apple `AppleFileAssetProvider`) by running the shared, framework-neutral
 * [AssetProviderContractKit] against it -- unmodified, the same kit the other
 * three pass.
 *
 * The provider under test is a real [KtorAssetProvider] making real Ktor HTTP
 * calls; only the HTTP engine is a [io.ktor.client.engine.mock.MockEngine]
 * pointed at [AssetHttpTestServer], which exists because there is no real
 * DataLoom asset-serving backend to point this at (see [KtorAssetProvider]'s
 * own KDoc for why that is an honest substitute rather than a shortcut: the
 * test server delegates every business rule -- digests, quota, conflicts,
 * visibility -- to a real, already-contract-tested
 * [InMemoryAssetProvider], and only translates HTTP on top of it). This
 * proves [KtorAssetProvider]'s own request/response/error-mapping logic end
 * to end over a real `suspend` HTTP call stack, which an in-process delegate
 * call could not.
 */
class KtorAssetProviderContractTest {

    private fun kit(): AssetProviderContractKit {
        val digests = SystemDataLoomDigestCalculator()
        return AssetProviderContractKit(digests) { quota: AssetQuota ->
            val chunkBounds = AssetChunkSizeBounds(1, 64 * 1024 * 1024)
            val delegate = InMemoryAssetProvider(digests, quota, chunkBounds)
            val server = AssetHttpTestServer(delegate)
            KtorAssetProvider.createForTesting(
                baseUrl = "https://assets.example.test",
                chunkSizeBounds = chunkBounds,
                httpClient = server.client(),
                closeHttpClientOnClose = true,
            ) as AssetProvider
        }
    }

    @Test
    fun `the Ktor transport-backed provider passes the full provider contract kit`() = runTest {
        val report = kit().run()
        report.assertAllPassed()
        assertTrue(report.results.size >= 20, "kit should exercise at least 20 scenarios, ran ${report.results.size}")
    }
}
