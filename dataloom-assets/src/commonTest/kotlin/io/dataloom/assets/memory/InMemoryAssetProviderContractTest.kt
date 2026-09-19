package io.dataloom.assets.memory

import io.dataloom.api.asset.AssetManifest
import io.dataloom.assets.AssetChunkSizeBounds
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetQuota
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.AssetUploadStatus
import io.dataloom.assets.platformDigests
import io.dataloom.assets.sessionId
import io.dataloom.assets.testAsset
import io.dataloom.assets.testkit.AssetProviderContractKit
import io.dataloom.assets.testkit.AssetProviderContractReport
import io.dataloom.api.provider.ProviderOperationResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryAssetProviderContractTest {

    private fun kit(factory: suspend (AssetQuota) -> AssetProvider = { InMemoryAssetProvider(platformDigests(), it) }) =
        AssetProviderContractKit(platformDigests(), factory)

    @Test
    fun `the in-memory reference provider passes the full provider contract kit`() = runTest {
        val report = kit().run()
        report.assertAllPassed()
        assertTrue(report.results.size >= 20, "kit should exercise at least 20 scenarios, ran ${report.results.size}")
    }

    /** A broken provider: reports success for chunks whose digest does not match. */
    private class SwallowsCorruption(private val delegate: AssetProvider) : AssetProvider by delegate {
        override suspend fun uploadChunk(request: AssetChunkUpload): ProviderOperationResult<AssetUploadStatus> {
            val result = delegate.uploadChunk(request)
            val kind = ((result as? ProviderOperationResult.Failure)?.error as? AssetTransferError)?.kind
            return if (kind == AssetErrorKind.CHUNK_DIGEST_MISMATCH) {
                ProviderOperationResult.Success(AssetUploadStatus(request.sessionId, emptySet()))
            } else {
                result
            }
        }
    }

    /** A broken provider: never completes an upload. */
    private class NeverCompletes(private val delegate: AssetProvider) : AssetProvider by delegate {
        override suspend fun completeUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<AssetManifest> =
            ProviderOperationResult.Failure(AssetTransferError(AssetErrorKind.PROVIDER_REJECTED, "never completes"))
    }

    @Test
    fun `the kit reports a provider that accepts corrupted chunks`() = runTest {
        val report = kit { SwallowsCorruption(InMemoryAssetProvider(platformDigests(), it)) }.run()
        assertEquals(
            listOf("a chunk with the wrong digest is rejected and not committed"),
            report.failures.map { it.name },
        )
        val error = runCatching { report.assertAllPassed() }.exceptionOrNull()
        assertTrue(error is AssertionError)
        assertTrue("contract scenarios failed" in error.message.orEmpty())
    }

    @Test
    fun `the kit reports a provider that never completes uploads`() = runTest {
        val report = kit { NeverCompletes(InMemoryAssetProvider(platformDigests(), it)) }.run()
        assertFalse(report.failures.isEmpty())
        assertTrue(report.failures.any { it.name.startsWith("upload, complete and download") })
    }

    @Test
    fun `the kit reports a provider that ignores quota`() = runTest {
        val report = kit { InMemoryAssetProvider(platformDigests(), AssetQuota.UNLIMITED) }.run()
        val names = report.failures.map { it.name }
        assertTrue(names.any { "maximum asset size" in it }, "max asset size scenario must fail, failures: $names")
        assertTrue(names.any { "total quota" in it }, "total quota scenario must fail, failures: $names")
    }

    @Test
    fun `a scenario failure is reported not thrown and the others still run`() = runTest {
        val report: AssetProviderContractReport = kit { quota ->
            InMemoryAssetProvider(platformDigests(), quota, AssetChunkSizeBounds(1, 64 * 1024 * 1024))
        }.run()
        assertEquals(report.results.size, report.results.count { it.passed } + report.failures.size)
    }

    // The reference provider's own behaviours the kit cannot express generically.

    @Test
    fun `chunks outside the provider bounds are rejected at open`() = runTest {
        val provider = InMemoryAssetProvider(platformDigests(), chunkSizeBounds = AssetChunkSizeBounds(512, 2_048))
        val tooBig = testAsset(size = 10_000, chunkSize = 4_096)
        val tooSmall = testAsset(id = "small", size = 10_000, chunkSize = 100)
        for (asset in listOf(tooBig, tooSmall)) {
            val result = provider.openUpload(AssetUploadRequest(sessionId("s-${asset.assetId}"), asset.manifest))
            val kind = ((result as ProviderOperationResult.Failure).error as AssetTransferError).kind
            assertEquals(AssetErrorKind.PROVIDER_REJECTED, kind)
        }
        val fits = testAsset(id = "fits", size = 10_000, chunkSize = 1_024)
        assertTrue(provider.openUpload(AssetUploadRequest(sessionId("s-fits"), fits.manifest)) is ProviderOperationResult.Success)
    }

    @Test
    fun `the provider copies uploaded chunk bytes so callers may reuse their buffer`() = runTest {
        val provider = InMemoryAssetProvider(platformDigests())
        val asset = testAsset(size = 2_000, chunkSize = 1_000)
        val session = sessionId("s")
        provider.openUpload(AssetUploadRequest(session, asset.manifest))
        val buffer = asset.chunk(0)
        provider.uploadChunk(AssetChunkUpload(session, 0, buffer))
        buffer.fill(0) // the engine reuses one buffer for every chunk
        provider.uploadChunk(AssetChunkUpload(session, 1, asset.chunk(1)))
        provider.completeUpload(session)
        val stored = (provider.readChunk(asset.assetId, 1, 0) as ProviderOperationResult.Success).value
        assertTrue(stored.contentEquals(asset.chunk(0)))
    }

    @Test
    fun `chunks returned by readChunk are copies`() = runTest {
        val provider = InMemoryAssetProvider(platformDigests())
        val asset = testAsset(size = 2_000, chunkSize = 1_000)
        val session = sessionId("s")
        provider.openUpload(AssetUploadRequest(session, asset.manifest))
        for (i in 0 until 2) provider.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i)))
        provider.completeUpload(session)
        (provider.readChunk(asset.assetId, 1, 0) as ProviderOperationResult.Success).value.fill(0)
        val again = (provider.readChunk(asset.assetId, 1, 0) as ProviderOperationResult.Success).value
        assertTrue(again.contentEquals(asset.chunk(0)))
    }
}
