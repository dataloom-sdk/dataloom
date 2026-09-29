package io.dataloom.assets.file

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.platformDigests
import io.dataloom.assets.sessionId
import io.dataloom.assets.testAsset
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * [FileAssetProvider]'s cleanup and atomic-promotion guarantees, checked
 * directly against the filesystem rather than only against the API's return
 * values (FR-ASSET-009).
 */
class FileAssetProviderCleanupTest {

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix)

    private fun uploadDir(base: java.nio.file.Path, sessionValue: String) =
        base.resolve("uploads").resolve(FileAssetIo.safeName(sessionValue))

    private fun committedAsset(base: java.nio.file.Path, assetId: String, version: Long) =
        base.resolve("committed").resolve(FileAssetIo.safeName(assetId)).resolve(version.toString()).resolve("asset.bin")

    @Test
    fun `abort deletes the session's temp chunk files eagerly`() = runTest {
        val base = tempDir("file-provider-abort")
        val provider = FileAssetProvider(base, platformDigests())
        val asset = testAsset(id = "abort-me")
        val session = sessionId("s-abort")
        provider.openUpload(AssetUploadRequest(session, asset.manifest))
        provider.uploadChunk(AssetChunkUpload(session, 0, asset.chunk(0)))
        val dir = uploadDir(base, session.value)
        assertTrue(Files.exists(dir), "the chunk file should exist before abort")

        provider.abortUpload(session)

        assertFalse(Files.exists(dir), "abort must remove the session's temp files")
    }

    @Test
    fun `completing an upload with a wrong whole-object digest leaves nothing at the committed path`() = runTest {
        val base = tempDir("file-provider-crash")
        val provider = FileAssetProvider(base, platformDigests())
        val asset = testAsset(id = "tampered")
        val wrongChecksum = platformDigests().digest(DigestAlgorithm.SHA_256, byteArrayOf(9, 9, 9))
        val tampered = asset.manifest.copy(checksum = wrongChecksum)
        val session = sessionId("s-tampered")
        provider.openUpload(AssetUploadRequest(session, tampered))
        for (i in 0 until asset.manifest.chunkLayout.chunkCount) {
            provider.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i)))
        }
        val committedPath = committedAsset(base, "tampered", 1)

        val result = provider.completeUpload(session)

        assertTrue(result is ProviderOperationResult.Failure)
        val kind = (result.error as AssetTransferError).kind
        assertEquals(AssetErrorKind.OBJECT_DIGEST_MISMATCH, kind)
        assertFalse(Files.exists(committedPath), "a tampered object must never become visible at its committed path")
        assertFalse(Files.exists(uploadDir(base, session.value)), "the failed session's temp files must be cleaned up")
    }

    @Test
    fun `a completed upload is visible at the committed path in full and its temp files are gone`() = runTest {
        val base = tempDir("file-provider-complete")
        val provider = FileAssetProvider(base, platformDigests())
        val asset = testAsset(id = "whole")
        val session = sessionId("s-whole")
        provider.openUpload(AssetUploadRequest(session, asset.manifest))
        for (i in 0 until asset.manifest.chunkLayout.chunkCount) {
            provider.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i)))
        }

        val completed = provider.completeUpload(session)

        assertTrue(completed is ProviderOperationResult.Success)
        val committedPath = committedAsset(base, "whole", 1)
        assertTrue(Files.exists(committedPath))
        assertEquals(asset.bytes.size.toLong(), Files.size(committedPath))
        assertTrue(asset.bytes.contentEquals(Files.readAllBytes(committedPath)))
        assertFalse(Files.exists(uploadDir(base, session.value)), "chunk temp files are cleaned up once committed")
    }

    @Test
    fun `sweepAbandonedUploads removes only sessions older than the threshold`() = runTest {
        val base = tempDir("file-provider-sweep")
        val provider = FileAssetProvider(base, platformDigests())
        val oldAsset = testAsset(id = "old-session")
        val freshAsset = testAsset(id = "fresh-session")
        val oldSession = sessionId("s-old")
        val freshSession = sessionId("s-fresh")

        provider.openUpload(AssetUploadRequest(oldSession, oldAsset.manifest))
        provider.uploadChunk(AssetChunkUpload(oldSession, 0, oldAsset.chunk(0)))
        provider.openUpload(AssetUploadRequest(freshSession, freshAsset.manifest))
        provider.uploadChunk(AssetChunkUpload(freshSession, 0, freshAsset.chunk(0)))

        val oldDir = uploadDir(base, oldSession.value)
        val freshDir = uploadDir(base, freshSession.value)
        assertTrue(Files.exists(oldDir))
        assertTrue(Files.exists(freshDir))

        // Simulate the old session having last been touched an hour ago (abandoned).
        Files.setLastModifiedTime(oldDir, FileTime.fromMillis(System.currentTimeMillis() - 60 * 60 * 1000))

        val removed = provider.sweepAbandonedUploads(30.minutes)

        assertEquals(1, removed)
        assertFalse(Files.exists(oldDir), "the abandoned session's temp files must be gone")
        assertTrue(Files.exists(freshDir), "a recently active session must not be swept")

        // The swept session id is now unusable — a later chunk upload for it fails cleanly,
        // it does not silently succeed against a resurrected directory.
        val afterSweep = provider.uploadChunk(AssetChunkUpload(oldSession, 1, oldAsset.chunk(1)))
        assertTrue(afterSweep is ProviderOperationResult.Failure)
        val kind = (afterSweep.error as AssetTransferError).kind
        assertEquals(AssetErrorKind.SESSION_NOT_FOUND, kind)
    }
}
