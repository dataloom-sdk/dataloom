package io.dataloom.assets.policy

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetTransferEngine
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.InMemoryAssetTransferSessionStore
import io.dataloom.assets.file.FileAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.assets.octetStream
import io.dataloom.assets.patternBytes
import io.dataloom.assets.platformDigests
import io.dataloom.assets.sessionId
import io.dataloom.api.identifier.AssetId
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves [DurableAssetContentQuarantineLog] genuinely persists, against a
 * real file on the real filesystem -- not just an in-memory assertion --
 * the same discipline [io.dataloom.assets.file.FileAssetProviderContentPolicyTest]
 * uses for [io.dataloom.assets.AssetContentPolicy] itself.
 *
 * [FileDurableStateStore] is a minimal, test-only [DurableStateStore]: one
 * file per scope key, containing the exact string
 * [AssetContentQuarantineRecordCodec] produces. It exists to prove this log
 * works end to end against a real, swappable storage backend (not just the
 * in-memory [EncodingQuarantineStateStore] used elsewhere), the same role a
 * production host would give `RoomDurableStateStore` -- it is not itself
 * shipped as a reference storage backend, since this module already has one
 * genuinely production-grade, filesystem-backed analogue
 * ([io.dataloom.assets.file.FileAssetProvider]) and a real host almost
 * always already has its own durable-state backend (Room, SQLDelight, a
 * key-value store) to give [DurableAssetContentQuarantineLog] instead of
 * inventing a new one.
 */
class FileBackedAssetContentQuarantineTest {

    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { dir -> tempDirs.add(dir) }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { dir ->
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** One file per scope key, under [root], holding exactly the codec's encoded string. */
    private class FileDurableStateStore(
        private val root: Path,
        private val codec: AssetContentQuarantineRecordCodec = AssetContentQuarantineRecordCodec(),
    ) : DurableStateStore<AssetTransferSessionId, AssetContentQuarantineRecord> {

        private fun fileFor(scope: AssetTransferSessionId): Path =
            root.resolve(DurableAssetContentQuarantineLog.KeyEncoder.encode(scope) + ".quarantine")

        override suspend fun load(
            scope: AssetTransferSessionId,
        ): ProviderOperationResult<DurableStateLoadResult<AssetContentQuarantineRecord>> {
            val file = fileFor(scope)
            if (!Files.exists(file)) return ProviderOperationResult.Success(DurableStateLoadResult.Missing)
            val lines = Files.readAllLines(file)
            val version = lines[0].toLong()
            val payload = lines.drop(1).joinToString("\n")
            return ProviderOperationResult.Success(
                DurableStateLoadResult.Found(DurableStateRecord(codec.decode(payload), version, schemaVersion = 1)),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AssetTransferSessionId, AssetContentQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AssetContentQuarantineRecord>> {
            val file = fileFor(request.scope)
            val currentVersion = if (Files.exists(file)) Files.readAllLines(file)[0].toLong() else null
            if (currentVersion != request.expectedVersion) {
                val current = if (Files.exists(file)) {
                    val lines = Files.readAllLines(file)
                    DurableStateRecord(codec.decode(lines.drop(1).joinToString("\n")), lines[0].toLong(), schemaVersion = 1)
                } else {
                    null
                }
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val nextVersion = (currentVersion ?: -1L) + 1L
            // Real file write: this is the genuine persistence under test.
            Files.write(file, listOf(nextVersion.toString(), codec.encode(request.nextState)))
            return ProviderOperationResult.Success(
                DurableStateCompareAndSetResult.Updated(DurableStateRecord(request.nextState, nextVersion, request.nextSchemaVersion)),
            )
        }
    }

    @Test
    fun `a quarantine match is written to a real file on disk readable back by a fresh log instance`() = runTest {
        val storeDir = tempDir("quarantine-store")
        val digests = platformDigests()
        val chunk = byteArrayOf(1, 2, 3, 4, 5)
        val digestHex = digests.digest(io.dataloom.api.security.DigestAlgorithm.SHA_256, chunk).toHex()
        val sid = sessionId("file-backed-quarantine")
        val log = DurableAssetContentQuarantineLog(FileDurableStateStore(storeDir))
        val policy = HashDenyListAssetContentPolicy(
            digests,
            denyList = listOf(AssetContentDenyListEntry(digestHex, AssetContentMatchAction.QUARANTINE, "needs-review")),
            quarantineLog = log,
            clock = FixedClock(DataLoomInstant(99_000L)),
        )
        val providerDir = tempDir("quarantine-provider")
        val provider = FileAssetProvider(providerDir, digests)
        val engine = AssetTransferEngine(
            provider,
            InMemoryAssetTransferSessionStore(),
            digests,
            chunkSizeBytes = chunk.size,
            contentPolicy = policy,
        )

        val outcome = engine.upload(sid, AssetId("file-doc"), 1, octetStream, InMemoryAssetSource(chunk))
        val failed = assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CONTENT_POLICY_QUARANTINED, failed.session.failure)

        // Genuine filesystem check, not an in-memory assertion: a real file
        // exists on disk, under the real codec's header, containing the
        // matched digest.
        val expectedFile = storeDir.resolve(DurableAssetContentQuarantineLog.KeyEncoder.encode(sid) + ".quarantine")
        assertTrue(Files.exists(expectedFile), "the quarantine record must be a real file on disk")
        val onDisk = Files.readString(expectedFile)
        assertTrue(onDisk.contains("DATALOOM_ASSET_CONTENT_QUARANTINE"))
        assertTrue(onDisk.contains(digestHex))

        // A fresh log instance over the same directory (simulating a process restart)
        // reads back the exact record, proving the file -- not process memory -- is the
        // source of truth.
        val restarted = DurableAssetContentQuarantineLog(FileDurableStateStore(storeDir))
        val reloaded = (restarted.current(sid) as ProviderOperationResult.Success).value
        requireNotNull(reloaded)
        assertEquals(digestHex, reloaded.matchedDigestHex)
        assertEquals(AssetContentQuarantineStatus.HELD, reloaded.status)

        // And the upload never committed anything visible on the provider's own filesystem.
        val committedAsset = providerDir.resolve("committed").resolve("file-doc").resolve("1").resolve("asset.bin")
        assertTrue(!Files.exists(committedAsset), "a quarantined upload must never become visible at its committed path")
    }
}
