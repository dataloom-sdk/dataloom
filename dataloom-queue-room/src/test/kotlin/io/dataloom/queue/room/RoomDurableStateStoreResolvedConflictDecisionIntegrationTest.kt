package io.dataloom.queue.room

import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.ResolvedConflictDecisionRecordCodec
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
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
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Sixth real domain exercised through [RoomDurableStateStore], after
 * `RoomDurableStateStoreTest`'s stand-in fixtures,
 * `RoomDurableStateStorePolicyDecisionIntegrationTest`'s policy-decision
 * proof, `RoomDurableStateStoreUnresolvedConflictIntegrationTest`'s
 * unresolved-conflict proof, `RoomDurableStateStoreStrategyDecisionIntegrationTest`'s
 * strategy-decision proof, and the operational-event-outbox proof — again
 * with zero new Room DAO/entity code, just [ResolvedConflictDecisionRecordCodec]
 * and [DurableResolvedConflictDecisionLog.KeyEncoder].
 */
class RoomDurableStateStoreResolvedConflictDecisionIntegrationTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao
    private lateinit var store: RoomDurableStateStore<ConflictId, ResolvedConflictDecisionRecord>

    private val conflictId = ConflictId("conflict-1")
    private val record = ResolvedConflictDecisionRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("invoice"), EntityId("invoice-1")),
        localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
        remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
        conflictMetadata = DataLoomMetadata.Empty,
        resolverId = ConflictResolverId("dataloom.builtin.last-write-wins"),
        decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
        decisionMetadata = DataLoomMetadata.Empty,
        committedAt = DataLoomInstant(10_000L),
    )

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
        store = RoomDurableStateStore(
            database,
            "resolved-conflict-decisions",
            DurableResolvedConflictDecisionLog.KeyEncoder,
            ResolvedConflictDecisionRecordCodec(),
        )
    }

    @Test
    fun insertsAndRoundTripsAResolvedConflictDecisionRecordThroughTheGenericRoomStore() {
        runBlocking {
            val encodedKey = DurableResolvedConflictDecisionLog.KeyEncoder.encode(conflictId)
            val encodedPayload = ResolvedConflictDecisionRecordCodec().encode(record)
            val persistedEntity = DurableStateEntity(
                namespace = "resolved-conflict-decisions",
                scopeKey = encodedKey,
                statePayload = encodedPayload,
                schemaVersion = 1,
                recordVersion = 0L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("resolved-conflict-decisions", encodedKey)).thenReturn(persistedEntity)

            val inserted = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<ResolvedConflictDecisionRecord>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(conflictId, null, record, 1)),
            )
            val updated = assertIs<DurableStateCompareAndSetResult.Updated<ResolvedConflictDecisionRecord>>(inserted.value)
            assertEquals(record, updated.record.state)

            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<ResolvedConflictDecisionRecord>>>(
                store.load(conflictId),
            )
            val found = assertIs<DurableStateLoadResult.Found<ResolvedConflictDecisionRecord>>(loaded.value)
            assertEquals(record, found.record.state)
        }
    }
}
