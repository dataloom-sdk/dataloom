package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.work.testing.WorkManagerTestInitHelper
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.security.SystemDataLoomDigestCalculator
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferPhase
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.file.FileAssetProvider
import io.dataloom.assets.file.FileAssetSink
import io.dataloom.assets.file.FileAssetSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real on-emulator proof that `DataLoomBuilder.assetTransferConfiguration`
 * genuinely works on Android (`#97`, the Android half of AC-FUNC-005 minus a
 * real network transport): a real multi-chunk upload and download run
 * through a real [io.dataloom.runtime.facade.DataLoom] built by
 * [buildReferenceDataLoom], with transfer-session state persisted in a real
 * Room database and recovered by a fresh DataLoom/Room instance.
 *
 * ## What this proves
 *
 * 1. A 5,000-byte file is uploaded through
 *    `DataLoom.assetTransfer` as five 1,024-byte chunks (the spec's chunk
 *    size is honoured), through real files on both ends
 *    ([FileAssetSource] read, [FileAssetProvider]
 *    chunk files, atomic promotion to a committed asset).
 * 2. The completed session is durable: after the first DataLoom and its
 *    Room database are shut down and closed, a brand-new
 *    [buildReferenceAssetTransfer] (a new Room database instance over the
 *    same on-disk file, sharing no in-memory client state) reads back the
 *    same session, `COMPLETED`, at the same revision with the same
 *    whole-object digest. The *provider* restarts too: the second side
 *    gets a FRESH [FileAssetProvider] over the same directory, sharing no
 *    in-memory state with the first, so its committed index must be
 *    rebuilt from the files on disk.
 * 3. The restarted DataLoom, through that fresh provider, downloads the
 *    asset, again chunk by chunk, into a [FileAssetSink], and the promoted
 *    file is byte-identical to the original.
 *
 * ## What this does not prove
 *
 * - A real network transport. [FileAssetProvider]
 *   is an in-process, filesystem reference for the *remote* side of the
 *   [io.dataloom.assets.AssetProvider] SPI; `KtorAssetProvider`
 *   (`dataloom-assets-transport-ktor`) against a real HTTP server on
 *   Android is not exercised here.
 * - An *interrupted* transfer resuming after a restart. This uploads to
 *   completion in one process; interrupted-resume is proven at the engine
 *   level (`DurableAssetTransferResumeTest`) and the session store level,
 *   not re-proven on the emulator.
 * - A physical device, or API levels below 26 (`FileAssetProvider` uses
 *   `java.nio.file`, which this module's `minSdk` 21 does not guarantee).
 *
 * Verified by running `connectedDebugAndroidTest` against a locally booted
 * `Pixel_8_Pro` AVD; see this PR's `docs/status/fragments/` entry for the
 * observed counts.
 */
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class AndroidReferenceConsumerAssetTransferInstrumentedTest {

    @Test
    fun aRealMultiChunkUploadAndDownloadSurviveARestartOfTheDurableSessionState() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val runId = UUID.randomUUID().toString()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val workDir = File(context.cacheDir, "dataloom-asset-transfer-$runId").apply { mkdirs() }
        val assetDir = File(workDir, "remote")
        val digests = SystemDataLoomDigestCalculator()
        val remote = buildReferenceFileAssetProvider(assetDir, digests)
        val sessionDatabaseName = "dataloom-asset-session-$runId.db"
        val sessionId = AssetTransferSessionId("android-upload-$runId")
        val assetId = AssetId("android-asset-$runId")

        val originalBytes = ByteArray(5_000) { (it * 7).toByte() }
        val originalFile = File(workDir, "original.bin").apply { writeBytes(originalBytes) }

        // --- First "process": upload ---------------------------------------
        val firstTransfer = buildReferenceAssetTransfer(context, remote, digests, sessionDatabaseName, chunkSizeBytes = 1_024)
        val firstDataLoom = buildReferenceDataLoom(
            context = context,
            storageDatabaseName = "dataloom-storage-asset-$runId.db",
            queueDatabaseName = "dataloom-queue-asset-$runId.db",
            assetTransfer = firstTransfer,
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, firstDataLoom.initialize())
        val firstEngine = assertNotNull(firstDataLoom.assetTransfer, "assetTransferConfiguration must expose an engine")

        val uploaded = FileAssetSource(originalFile.toPath()).use { source ->
            firstEngine.upload(sessionId, assetId, 1, AssetMediaType("application/octet-stream"), source)
        }
        val completed = assertIs<AssetTransferOutcome.Completed>(uploaded)
        assertEquals(5, completed.session.manifest.chunkLayout.chunkCount, "the spec's chunk size was honoured")
        assertTrue(
            File(assetDir, "committed").walkTopDown().any { it.isFile && it.length() == originalBytes.size.toLong() },
            "the whole asset must be committed as one real file under the provider directory",
        )

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, firstDataLoom.shutdown())
        firstTransfer.close()

        // --- Second "process": nothing in memory is shared -----------------
        val restartedRemote = buildReferenceFileAssetProvider(assetDir, digests)
        val secondTransfer = buildReferenceAssetTransfer(context, restartedRemote, digests, sessionDatabaseName, chunkSizeBytes = 1_024)
        try {
            val restored = assertNotNull(secondTransfer.spec.sessionStore.load(sessionId), "session must survive the restart")
            assertEquals(AssetTransferPhase.COMPLETED, restored.phase)
            assertEquals(completed.session.revision, restored.revision)
            assertEquals(completed.session.manifest.checksum, restored.manifest.checksum)

            val secondDataLoom = buildReferenceDataLoom(
                context = context,
                storageDatabaseName = "dataloom-storage-asset-$runId.db",
                queueDatabaseName = "dataloom-queue-asset-$runId.db",
                assetTransfer = secondTransfer,
            )
            assertEquals(ProviderLifecycleResult.InitializeSuccess, secondDataLoom.initialize())
            val secondEngine = assertNotNull(secondDataLoom.assetTransfer)

            val downloadedFile = File(workDir, "downloaded.bin")
            val sink = FileAssetSink(downloadedFile.toPath())
            val downloaded = secondEngine.download(AssetTransferSessionId("android-download-$runId"), assetId, null, sink)
            assertIs<AssetTransferOutcome.Completed>(downloaded)
            sink.promote()

            assertContentEquals(originalBytes, downloadedFile.readBytes())
            assertEquals(ProviderLifecycleResult.ShutdownSuccess, secondDataLoom.shutdown())
        } finally {
            secondTransfer.close()
            workDir.deleteRecursively()
        }
    }
}
