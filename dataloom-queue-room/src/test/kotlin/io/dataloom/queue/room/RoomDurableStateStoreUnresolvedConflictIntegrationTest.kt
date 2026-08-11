package io.dataloom.queue.room

import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.conflict.UnresolvedConflictRecordCodec
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.DurableStateCompareAndSetEntityResult
import io.dataloom.queue.room.internal.DurableStateDao
import io.dataloom.queue.room.internal.DurableStateEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertIs

/**
 * Third real domain exercised through [RoomDurableStateStore], after
 * `RoomDurableStateStoreTest`'s stand-in fixtures and
 * `RoomDurableStateStorePolicyDecisionIntegrationTest`'s policy-decision
 * proof — again with zero new Room DAO/entity code, just
 * [UnresolvedConflictRecordCodec] and [DurableUnresolvedConflictLog.KeyEncoder].
 */
class RoomDurableStateStoreUnresolvedConflictIntegrationTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao
    private lateinit var store: RoomDurableStateStore<ConflictId, UnresolvedConflictRecord>

    private val conflictId = ConflictId("conflict-1")
    private val record = UnresolvedConflictRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("note"), EntityId("note-1")),
        localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
        remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
        conflictMetadata = DataLoomMetadata.Empty,
        reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
        committedAt = DataLoomInstant(10_000L),
    )

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
        store = RoomDurableStateStore(
            database,
            "unresolved-conflicts",
            DurableUnresolvedConflictLog.KeyEncoder,
            UnresolvedConflictRecordCodec(),
        )
    }

    @Test
    fun insertsAndRoundTripsAnUnresolvedConflictRecordThroughTheGenericRoomStore() {
        runBlocking {
            val encodedKey = DurableUnresolvedConflictLog.KeyEncoder.encode(conflictId)
            val encodedPayload = UnresolvedConflictRecordCodec().encode(record)
            val persistedEntity = DurableStateEntity(
                namespace = "unresolved-conflicts",
                scopeKey = encodedKey,
                statePayload = encodedPayload,
                schemaVersion = 1,
                recordVersion = 0L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("unresolved-conflicts", encodedKey)).thenReturn(persistedEntity)

            val inserted = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<UnresolvedConflictRecord>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(conflictId, null, record, 1)),
            )
            val updated = assertIs<DurableStateCompareAndSetResult.Updated<UnresolvedConflictRecord>>(inserted.value)
            assertEquals(record, updated.record.state)

            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<UnresolvedConflictRecord>>>(
                store.load(conflictId),
            )
            val found = assertIs<DurableStateLoadResult.Found<UnresolvedConflictRecord>>(loaded.value)
            assertEquals(record, found.record.state)
        }
    }
}
