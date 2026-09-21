package io.dataloom.queue.room

import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.AssetChunkPlan
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferEvent
import io.dataloom.assets.AssetTransferSession
import io.dataloom.assets.AssetTransferSessionCodec
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetTransferTransition
import io.dataloom.assets.DurableAssetTransferSessionStore
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.DurableStateCompareAndSetEntityResult
import io.dataloom.queue.room.internal.DurableStateDao
import io.dataloom.queue.room.internal.DurableStateEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import io.dataloom.api.asset.AssetManifest

/**
 * Asset transfer sessions through the generic [RoomDurableStateStore], with
 * zero new Room DAO/entity code -- just [AssetTransferSessionCodec] and
 * [DurableAssetTransferSessionStore.KeyEncoder]. Same mocked-DAO seam as
 * [RoomDurableStateStoreAssetManifestHistoryIntegrationTest]: it proves the
 * codec's payload and the domain's revision check survive the Room store's
 * entity mapping and a fresh store instance, not Room SQL itself (which
 * [RoomDurableStateStoreTest] and the instrumented tests cover).
 */
class RoomDurableStateStoreAssetTransferSessionIntegrationTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao

    private val sessionId = AssetTransferSessionId("transfer-001")
    private val namespace = "asset-transfer-session"
    private val encodedKey = DurableAssetTransferSessionStore.KeyEncoder.encode(sessionId)

    private fun digest(seed: Int) = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { (it + seed).toByte() })

    private val manifest: AssetManifest = AssetManifest(
        assetId = AssetId("asset-001"),
        version = 1L,
        sizeBytes = 25L,
        mediaType = AssetMediaType("application/octet-stream"),
        checksum = digest(0),
        chunkLayout = AssetChunkPlan(25L, 10).toLayout(listOf(digest(1), digest(2), digest(3))),
    )

    private val created = AssetTransferSession(sessionId, AssetTransferDirection.UPLOAD, manifest)

    private fun newStore() = RoomDurableStateStore(
        database,
        namespace,
        DurableAssetTransferSessionStore.KeyEncoder,
        AssetTransferSessionCodec(),
    )

    private fun entity(session: AssetTransferSession, version: Long) = DurableStateEntity(
        namespace = namespace,
        scopeKey = encodedKey,
        statePayload = AssetTransferSessionCodec().encode(session),
        schemaVersion = 1,
        recordVersion = version,
    )

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
    }

    @Test
    fun savesAndLoadsASessionThroughTheGenericRoomStore() {
        runBlocking {
            val row = entity(created, 0L)
            whenever(dao.load(namespace, encodedKey)).thenReturn(null, row)
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(DurableStateCompareAndSetEntityResult.Updated(row))

            val sessions = DurableAssetTransferSessionStore(newStore())
            assertTrue(sessions.save(created, expectedRevision = null))
            assertEquals(created, sessions.load(sessionId))
        }
    }

    /**
     * Restart proof: nothing but the encoded row survives. A freshly
     * constructed store instance recovers the committed chunk indices and the
     * manifest's digests, and the domain revision check then accepts exactly
     * the next revision.
     */
    @Test
    fun aFreshStoreInstanceRecoversCommittedChunksAndAcceptsOnlyTheNextRevision() {
        runBlocking {
            val started = (created.reduce(AssetTransferEvent.Start) as AssetTransferTransition.Applied).session
            val progressed = (started.reduce(AssetTransferEvent.ChunkCommitted(1)) as AssetTransferTransition.Applied).session
            val row = entity(progressed, 2L)
            whenever(dao.load(namespace, encodedKey)).thenReturn(row)
            whenever(dao.compareAndSet(eq(2L), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(entity(progressed, 3L)),
            )

            val reopened = DurableAssetTransferSessionStore(newStore())
            val loaded = reopened.load(sessionId)
            assertEquals(progressed, loaded)
            assertEquals(setOf(1), loaded!!.committedChunks)
            assertEquals(manifest.checksum, loaded.manifest.checksum)

            val next = (progressed.reduce(AssetTransferEvent.ChunkCommitted(0)) as AssetTransferTransition.Applied).session
            assertTrue(reopened.save(next, expectedRevision = progressed.revision))
            // A stale writer expecting an older revision never reaches the DAO's compare-and-set.
            assertEquals(false, reopened.save(next, expectedRevision = 0L))
        }
    }
}
