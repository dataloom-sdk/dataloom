package io.dataloom.queue.room

import io.dataloom.api.asset.AssetChunkDescriptor
import io.dataloom.api.asset.AssetChunkLayout
import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetManifestHistoryState
import io.dataloom.api.asset.AssetManifestHistoryStateCodec
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.asset.DurableAssetManifestApplyOutcome
import io.dataloom.api.asset.DurableAssetManifestHistory
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.DurableStateCompareAndSetEntityResult
import io.dataloom.queue.room.internal.DurableStateDao
import io.dataloom.queue.room.internal.DurableStateEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Fifth real domain exercised through [RoomDurableStateStore], after
 * `RoomDurableStateStoreTest`'s stand-in fixtures and the configuration
 * history, policy decision, unresolved conflict, and strategy decision
 * adoptions -- again with zero new Room DAO/entity code, just
 * [AssetManifestHistoryStateCodec] and [DurableAssetManifestHistory.KeyEncoder].
 */
class RoomDurableStateStoreAssetManifestHistoryIntegrationTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao
    private lateinit var store: RoomDurableStateStore<AssetId, AssetManifestHistoryState>

    private val assetId = AssetId("asset-001")
    private val manifest = AssetManifest(
        assetId = assetId,
        version = 1L,
        sizeBytes = 10L,
        mediaType = AssetMediaType("application/octet-stream"),
        checksum = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { it.toByte() }),
        chunkLayout = AssetChunkLayout(
            listOf(
                AssetChunkDescriptor(0, 0L, 10L, DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { (it + 1).toByte() })),
            ),
        ),
    )
    private val state = AssetManifestHistoryState(listOf(manifest))

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
        store = RoomDurableStateStore(
            database,
            "asset-manifest-history",
            DurableAssetManifestHistory.KeyEncoder,
            AssetManifestHistoryStateCodec(),
        )
    }

    @Test
    fun insertsAndRoundTripsAnAssetManifestHistoryThroughTheGenericRoomStore() {
        runBlocking {
            val encodedKey = DurableAssetManifestHistory.KeyEncoder.encode(assetId)
            val encodedPayload = AssetManifestHistoryStateCodec().encode(state)
            val persistedEntity = DurableStateEntity(
                namespace = "asset-manifest-history",
                scopeKey = encodedKey,
                statePayload = encodedPayload,
                schemaVersion = 1,
                recordVersion = 0L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("asset-manifest-history", encodedKey)).thenReturn(persistedEntity)

            val inserted = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<AssetManifestHistoryState>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(assetId, null, state, 1)),
            )
            val updated = assertIs<DurableStateCompareAndSetResult.Updated<AssetManifestHistoryState>>(inserted.value)
            assertEquals(state, updated.record.state)

            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<AssetManifestHistoryState>>>(
                store.load(assetId),
            )
            val found = assertIs<DurableStateLoadResult.Found<AssetManifestHistoryState>>(loaded.value)
            assertEquals(state, found.record.state)
        }
    }

    /**
     * Restart proof: nothing but the encoded row (returned here by the mocked
     * DAO, the same seam [RoomDurableStateStoreUnresolvedConflictIntegrationTest]
     * uses for its own reopened-store proof) survives a process restart. A
     * freshly constructed [RoomDurableStateStore] instance -- sharing only the
     * underlying [DataLoomRoomDatabase], never in-memory state from [store] --
     * must still recover the previously committed [AssetManifestHistoryState],
     * including every retained [AssetManifest] revision.
     */
    @Test
    fun restartReopensAFreshStoreInstanceAndRecoversThePreviouslyCommittedManifestHistory() {
        runBlocking {
            val secondManifest = manifest.copy(version = 2L)
            val twoRevisionState = AssetManifestHistoryState(listOf(manifest, secondManifest))
            val encodedKey = DurableAssetManifestHistory.KeyEncoder.encode(assetId)
            val persistedEntity = DurableStateEntity(
                namespace = "asset-manifest-history",
                scopeKey = encodedKey,
                statePayload = AssetManifestHistoryStateCodec().encode(twoRevisionState),
                schemaVersion = 1,
                recordVersion = 1L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("asset-manifest-history", encodedKey)).thenReturn(persistedEntity)

            store.compareAndSet(DurableStateCompareAndSetRequest(assetId, null, twoRevisionState, 1))

            val reopenedStore = RoomDurableStateStore(
                database,
                "asset-manifest-history",
                DurableAssetManifestHistory.KeyEncoder,
                AssetManifestHistoryStateCodec(),
            )
            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<AssetManifestHistoryState>>>(
                reopenedStore.load(assetId),
            )
            val found = assertIs<DurableStateLoadResult.Found<AssetManifestHistoryState>>(loaded.value)
            assertEquals(twoRevisionState, found.record.state)
            assertEquals(listOf(1L, 2L), found.record.state.retainedManifests.map { it.version })
        }
    }

    /**
     * End-to-end proof through [DurableAssetManifestHistory] itself, not just
     * the raw [RoomDurableStateStore]: applying a second, higher-versioned
     * revision through the real generic Room store persists both revisions
     * and reports the newer one as current.
     */
    @Test
    fun durableAssetManifestHistoryAppliesASecondRevisionThroughTheRealRoomStore() {
        runBlocking {
            val encodedKey = DurableAssetManifestHistory.KeyEncoder.encode(assetId)
            val firstEntity = DurableStateEntity(
                namespace = "asset-manifest-history",
                scopeKey = encodedKey,
                statePayload = AssetManifestHistoryStateCodec().encode(state),
                schemaVersion = 1,
                recordVersion = 0L,
            )
            val secondManifest = manifest.copy(version = 2L)
            val secondState = AssetManifestHistoryState(listOf(manifest, secondManifest))
            val secondEntity = DurableStateEntity(
                namespace = "asset-manifest-history",
                scopeKey = encodedKey,
                statePayload = AssetManifestHistoryStateCodec().encode(secondState),
                schemaVersion = 1,
                recordVersion = 1L,
            )
            // Three loads happen in sequence: apply(manifest)'s own load sees
            // nothing yet, apply(secondManifest)'s load sees the first
            // committed revision, and current()'s final load sees the second.
            whenever(dao.load("asset-manifest-history", encodedKey)).thenReturn(null, firstEntity, secondEntity)
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(firstEntity),
            )
            whenever(dao.compareAndSet(eq(0L), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(secondEntity),
            )

            val history = DurableAssetManifestHistory(store)
            history.apply(assetId, manifest)
            val outcome = history.apply(assetId, secondManifest)

            assertIs<DurableAssetManifestApplyOutcome.Applied>(outcome)
            val current = assertIs<ProviderOperationResult.Success<AssetManifest?>>(history.current(assetId))
            assertEquals(2L, current.value?.version)
        }
    }
}
