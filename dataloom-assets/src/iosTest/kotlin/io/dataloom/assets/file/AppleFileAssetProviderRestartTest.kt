package io.dataloom.assets.file

import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetQuota
import io.dataloom.assets.AssetTransferEngine
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.TestAsset
import io.dataloom.assets.fixedKeys
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.assets.noiseBytes
import io.dataloom.assets.octetStream
import io.dataloom.assets.patternBytes
import io.dataloom.assets.platformDigests
import io.dataloom.assets.platformSecureRandom
import io.dataloom.assets.sessionId
import io.dataloom.assets.testAsset
import io.dataloom.assets.testCipher
import io.dataloom.assets.testKeyReference
import io.dataloom.assets.transform.AssetTransferTransforms
import io.dataloom.assets.transform.DeflateAssetCompressor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Restart recovery of [AppleFileAssetProvider]'s committed-asset index: the
 * Apple counterpart of the JVM `FileAssetProviderRestartTest`, same scenarios
 * against a real directory under the process temp directory.
 *
 * Compile-verified only; see [AppleFileAssetProviderContractTest]'s KDoc for
 * what could not run on this host.
 */
class AppleFileAssetProviderRestartTest {

    private val digests = platformDigests()

    private fun newProvider(base: String, quota: AssetQuota = AssetQuota.UNLIMITED) =
        AppleFileAssetProvider(base, digests, quota)

    private fun entryDir(base: String, assetId: String, version: Long): String =
        "$base/committed/${AppleFileAssetIo.safeName(assetId)}/$version"

    /** Replaces [path]'s content atomically (a test-only way to corrupt or truncate a file). */
    private fun overwrite(path: String, bytes: ByteArray) {
        val temp = AppleFileAssetIo.createSecureTempFile(AppleFileAssetIo.parentOf(path), "overwrite-", ".tmp")
        val descriptor = AppleFileAssetIo.openReadWrite(temp)
        try {
            AppleFileAssetIo.writeFully(descriptor, 0L, bytes, 0, bytes.size)
        } finally {
            AppleFileAssetIo.closeDescriptorQuietly(descriptor)
        }
        AppleFileAssetIo.promoteAtomically(temp, path)
    }

    private suspend fun commit(provider: AssetProvider, asset: TestAsset, session: String) {
        val id = sessionId(session)
        assertIs<ProviderOperationResult.Success<*>>(provider.openUpload(AssetUploadRequest(id, asset.manifest)))
        for (i in 0 until asset.manifest.chunkLayout.chunkCount) {
            assertIs<ProviderOperationResult.Success<*>>(provider.uploadChunk(AssetChunkUpload(id, i, asset.chunk(i))))
        }
        assertIs<ProviderOperationResult.Success<*>>(provider.completeUpload(id))
    }

    private suspend fun readAll(provider: AssetProvider, asset: TestAsset): ByteArray {
        val manifest = (provider.readManifest(asset.assetId, asset.version) as ProviderOperationResult.Success).value
        var out = ByteArray(0)
        for (i in 0 until manifest.chunkLayout.chunkCount) {
            out += (provider.readChunk(asset.assetId, asset.version, i) as ProviderOperationResult.Success).value
        }
        return out
    }

    private fun kindOf(result: ProviderOperationResult<*>): AssetErrorKind {
        assertIs<ProviderOperationResult.Failure>(result)
        return (result.error as AssetTransferError).kind
    }

    @Test
    fun `a fresh provider over the same directory serves the committed asset byte-identical and digest-verified`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart")
        val asset = testAsset(id = "survivor", size = 5_000, chunkSize = 700)
        commit(newProvider(base), asset, "s1")

        val restarted = newProvider(base)
        val manifest = (restarted.readManifest(asset.assetId, null) as ProviderOperationResult.Success).value
        assertEquals(asset.manifest, manifest)
        val bytes = readAll(restarted, asset)
        assertContentEquals(asset.bytes, bytes)
        assertTrue(manifest.checksum contentEquals digests.digest(manifest.checksum.algorithm, bytes))
        assertEquals(0, restarted.skippedCommittedEntryCount)
    }

    @Test
    fun `several assets and versions all survive and the latest version is still resolved`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-multi")
        val first = newProvider(base)
        val v1 = testAsset(id = "multi", version = 1, size = 3_000, chunkSize = 1_000, seed = 1)
        val v2 = testAsset(id = "multi", version = 2, size = 2_500, chunkSize = 1_000, seed = 2)
        val other = testAsset(id = "other", version = 1, size = 1_500, chunkSize = 500, seed = 3)
        commit(first, v1, "a")
        commit(first, v2, "b")
        commit(first, other, "c")

        val restarted = newProvider(base)
        assertContentEquals(v1.bytes, readAll(restarted, v1))
        assertContentEquals(v2.bytes, readAll(restarted, v2))
        assertContentEquals(other.bytes, readAll(restarted, other))
        assertEquals(2L, (restarted.readManifest(AssetId("multi"), null) as ProviderOperationResult.Success).value.version)
        assertEquals(0, restarted.skippedCommittedEntryCount)
    }

    @Test
    fun `the recovered index also enforces version conflict and the storage quota`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-index")
        val asset = testAsset(id = "held", size = 4_000, chunkSize = 1_000)
        commit(newProvider(base), asset, "s1")

        val restarted = newProvider(base, AssetQuota(maxAssetSizeBytes = null, maxTotalBytes = 5_000))
        val conflict = restarted.openUpload(AssetUploadRequest(sessionId("again"), asset.manifest))
        assertEquals(AssetErrorKind.ASSET_VERSION_CONFLICT, kindOf(conflict))

        val extra = testAsset(id = "extra", size = 2_000, chunkSize = 1_000)
        val overQuota = restarted.openUpload(AssetUploadRequest(sessionId("extra"), extra.manifest))
        assertEquals(AssetErrorKind.QUOTA_EXCEEDED, kindOf(overQuota))
    }

    @Test
    fun `a compressed and encrypted asset survives a restart and downloads through the engine`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-transformed")
        val transforms = AssetTransferTransforms(
            compressor = DeflateAssetCompressor(),
            cipher = testCipher(fixedKeys(), platformSecureRandom()),
            keyReference = testKeyReference,
        )
        val bytes = patternBytes(5_000, seed = 4) + noiseBytes(1_500, seed = 4)
        fun engine(provider: AssetProvider) = AssetTransferEngine(
            provider, InMemoryAssetTransferSessionStore(), digests, chunkSizeBytes = 1_024, transforms = transforms,
        )

        val up = engine(newProvider(base)).upload(sessionId("up"), AssetId("sealed"), 1, octetStream, InMemoryAssetSource(bytes))
        assertIs<AssetTransferOutcome.Completed>(up)

        val sink = InMemoryAssetSink()
        val down = engine(newProvider(base)).download(sessionId("down"), AssetId("sealed"), null, sink)
        assertIs<AssetTransferOutcome.Completed>(down)
        assertContentEquals(bytes, sink.snapshot())
    }

    @Test
    fun `an in-flight upload is not exposed after a restart`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-inflight")
        val asset = testAsset(id = "partial", size = 3_000, chunkSize = 1_000)
        val first = newProvider(base)
        val session = sessionId("p1")
        first.openUpload(AssetUploadRequest(session, asset.manifest))
        first.uploadChunk(AssetChunkUpload(session, 0, asset.chunk(0)))
        first.uploadChunk(AssetChunkUpload(session, 1, asset.chunk(1)))

        val restarted = newProvider(base)
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readManifest(asset.assetId, null)))
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readChunk(asset.assetId, 1, 0)))
        assertEquals(AssetErrorKind.SESSION_NOT_FOUND, kindOf(restarted.uploadChunk(AssetChunkUpload(session, 2, asset.chunk(2)))))
        assertEquals(AssetErrorKind.SESSION_NOT_FOUND, kindOf(restarted.completeUpload(session)))
    }

    @Test
    fun `a corrupt manifest fails closed without hiding other assets`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-corrupt")
        val first = newProvider(base)
        val bad = testAsset(id = "bad", size = 2_000, chunkSize = 1_000, seed = 5)
        val good = testAsset(id = "good", size = 2_000, chunkSize = 1_000, seed = 6)
        commit(first, bad, "b")
        commit(first, good, "g")
        overwrite("${entryDir(base, "bad", 1)}/manifest.dlc", "DATALOOM_FILE_ASSET_COMMIT\t1\nnot,a,record".encodeToByteArray())

        val restarted = newProvider(base)
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readManifest(bad.assetId, 1)))
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readChunk(bad.assetId, 1, 0)))
        assertContentEquals(good.bytes, readAll(restarted, good))
        assertEquals(1, restarted.skippedCommittedEntryCount)
    }

    @Test
    fun `a truncated manifest and a missing manifest both fail closed`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-manifest-damage")
        val first = newProvider(base)
        val truncated = testAsset(id = "truncated-manifest", size = 2_000, chunkSize = 1_000, seed = 7)
        val missing = testAsset(id = "missing-manifest", size = 2_000, chunkSize = 1_000, seed = 8)
        commit(first, truncated, "t")
        commit(first, missing, "m")
        val manifestPath = "${entryDir(base, "truncated-manifest", 1)}/manifest.dlc"
        overwrite(manifestPath, AppleFileAssetIo.readAllBytes(manifestPath).copyOf(40))
        // The interrupted-commit shape: asset.bin was promoted but the manifest never was.
        AppleFileAssetIo.deleteQuietly("${entryDir(base, "missing-manifest", 1)}/manifest.dlc")

        val restarted = newProvider(base)
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readManifest(truncated.assetId, 1)))
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readManifest(missing.assetId, 1)))
        assertEquals(2, restarted.skippedCommittedEntryCount)
    }

    @Test
    fun `a truncated asset file fails closed`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-truncated")
        val asset = testAsset(id = "short", size = 3_000, chunkSize = 1_000)
        commit(newProvider(base), asset, "s")
        val assetPath = "${entryDir(base, "short", 1)}/asset.bin"
        overwrite(assetPath, AppleFileAssetIo.readAllBytes(assetPath).copyOf(2_100))

        val restarted = newProvider(base)
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readManifest(asset.assetId, 1)))
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readChunk(asset.assetId, 1, 0)))
        assertEquals(1, restarted.skippedCommittedEntryCount)
    }

    @Test
    fun `a manifest copied under another asset id is not trusted`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-swapped")
        val first = newProvider(base)
        val real = testAsset(id = "real", size = 2_000, chunkSize = 1_000, seed = 9)
        val victim = testAsset(id = "victim", size = 2_000, chunkSize = 1_000, seed = 10)
        commit(first, real, "r")
        commit(first, victim, "v")
        overwrite(
            "${entryDir(base, "victim", 1)}/manifest.dlc",
            AppleFileAssetIo.readAllBytes("${entryDir(base, "real", 1)}/manifest.dlc"),
        )

        val restarted = newProvider(base)
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readManifest(victim.assetId, 1)))
        assertContentEquals(real.bytes, readAll(restarted, real))
        assertEquals(1, restarted.skippedCommittedEntryCount)
    }

    @Test
    fun `same-size bit rot in the asset file is caught per chunk and never served`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-bitrot")
        val asset = testAsset(id = "rot", size = 3_000, chunkSize = 1_000)
        commit(newProvider(base), asset, "s")
        val assetPath = "${entryDir(base, "rot", 1)}/asset.bin"
        val damaged = AppleFileAssetIo.readAllBytes(assetPath)
        damaged[1_500] = (damaged[1_500].toInt() xor 0x01).toByte()
        overwrite(assetPath, damaged)

        val restarted = newProvider(base)
        // Size is intact, so the entry is indexed; only the damaged chunk is refused.
        assertEquals(asset.manifest, (restarted.readManifest(asset.assetId, 1) as ProviderOperationResult.Success).value)
        assertContentEquals(asset.chunk(0), (restarted.readChunk(asset.assetId, 1, 0) as ProviderOperationResult.Success).value)
        assertEquals(AssetErrorKind.OBJECT_DIGEST_MISMATCH, kindOf(restarted.readChunk(asset.assetId, 1, 1)))
        assertContentEquals(asset.chunk(2), (restarted.readChunk(asset.assetId, 1, 2) as ProviderOperationResult.Success).value)
    }

    @Test
    fun `a skipped entry can be replaced by uploading the same id and version again`() = runTest {
        val base = uniqueTestDirectory("apple-asset-restart-repair")
        val asset = testAsset(id = "repair", size = 2_000, chunkSize = 1_000)
        commit(newProvider(base), asset, "s1")
        overwrite("${entryDir(base, "repair", 1)}/manifest.dlc", "garbage".encodeToByteArray())

        val restarted = newProvider(base)
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(restarted.readManifest(asset.assetId, 1)))
        commit(restarted, asset, "s2")
        assertContentEquals(asset.bytes, readAll(restarted, asset))

        val again = newProvider(base)
        assertContentEquals(asset.bytes, readAll(again, asset))
        assertEquals(0, again.skippedCommittedEntryCount)
    }

    @Test
    fun `an asset directory that does not exist yet is an empty provider`() = runTest {
        val provider = newProvider("${uniqueTestDirectory("apple-asset-restart-empty")}/not-created-yet")
        assertEquals(AssetErrorKind.ASSET_NOT_FOUND, kindOf(provider.readManifest(AssetId("anything"), null)))
        assertEquals(0, provider.skippedCommittedEntryCount)
    }
}
