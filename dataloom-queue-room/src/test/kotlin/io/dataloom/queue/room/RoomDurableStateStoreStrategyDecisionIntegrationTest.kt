package io.dataloom.queue.room

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.DurableStrategyDecisionEventLog
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionEvent
import io.dataloom.api.strategy.StrategyDecisionEventCodec
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDecisionOutcomeKind
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
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
 * Fourth real domain exercised through [RoomDurableStateStore], after
 * `RoomDurableStateStoreTest`'s stand-in fixtures,
 * `RoomDurableStateStorePolicyDecisionIntegrationTest`'s policy-decision
 * proof, and `RoomDurableStateStoreUnresolvedConflictIntegrationTest`'s
 * unresolved-conflict proof — again with zero new Room DAO/entity code, just
 * [StrategyDecisionEventCodec] and [DurableStrategyDecisionEventLog.KeyEncoder].
 */
class RoomDurableStateStoreStrategyDecisionIntegrationTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao
    private lateinit var store: RoomDurableStateStore<StrategyDecisionId, StrategyDecisionEvent>

    private val decisionId = StrategyDecisionId("decision-1")
    private val event = StrategyDecisionEvent(
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
        effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
        effectiveProfileId = StrategyProfileId("profile-1"),
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.EXECUTE,
        reasonCodes = listOf("CACHE_FIRST_DEFAULT"),
        outcomeKind = StrategyDecisionOutcomeKind.SERVED_FROM_CACHE,
        outcomeDetail = null,
        committedAt = DataLoomInstant(10_000L),
    )

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
        store = RoomDurableStateStore(
            database,
            "strategy-decisions",
            DurableStrategyDecisionEventLog.KeyEncoder,
            StrategyDecisionEventCodec(),
        )
    }

    @Test
    fun insertsAndRoundTripsAStrategyDecisionEventThroughTheGenericRoomStore() {
        runBlocking {
            val encodedKey = DurableStrategyDecisionEventLog.KeyEncoder.encode(decisionId)
            val encodedPayload = StrategyDecisionEventCodec().encode(event)
            val persistedEntity = DurableStateEntity(
                namespace = "strategy-decisions",
                scopeKey = encodedKey,
                statePayload = encodedPayload,
                schemaVersion = 1,
                recordVersion = 0L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("strategy-decisions", encodedKey)).thenReturn(persistedEntity)

            val inserted = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<StrategyDecisionEvent>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(decisionId, null, event, 1)),
            )
            val updated = assertIs<DurableStateCompareAndSetResult.Updated<StrategyDecisionEvent>>(inserted.value)
            assertEquals(event, updated.record.state)

            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<StrategyDecisionEvent>>>(
                store.load(decisionId),
            )
            val found = assertIs<DurableStateLoadResult.Found<StrategyDecisionEvent>>(loaded.value)
            assertEquals(event, found.record.state)
        }
    }
}
