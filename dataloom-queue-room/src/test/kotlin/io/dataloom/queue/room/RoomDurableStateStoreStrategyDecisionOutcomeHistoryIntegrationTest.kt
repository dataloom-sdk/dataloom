package io.dataloom.queue.room

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.DurableStrategyDecisionOutcomeAppendOutcome
import io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionEvent
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDecisionOutcomeHistoryState
import io.dataloom.api.strategy.StrategyDecisionOutcomeHistoryStateCodec
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
 * Sixth real domain exercised through [RoomDurableStateStore], after
 * `RoomDurableStateStoreTest`'s stand-in fixtures and the configuration
 * history, policy decision, unresolved conflict, strategy decision, and
 * asset manifest history adoptions -- again with zero new Room DAO/entity
 * code, just [StrategyDecisionOutcomeHistoryStateCodec] and
 * [DurableStrategyDecisionOutcomeHistory.KeyEncoder].
 */
class RoomDurableStateStoreStrategyDecisionOutcomeHistoryIntegrationTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao
    private lateinit var store: RoomDurableStateStore<StrategyDecisionId, StrategyDecisionOutcomeHistoryState>

    private val decisionId = StrategyDecisionId("decision-1")
    private val firstAttempt = attempt(committedAt = 1_000L, outcome = StrategyDecisionOutcomeKind.FAILED, detail = "TRANSPORT_FAILURE")
    private val secondAttempt = attempt(committedAt = 2_000L, outcome = StrategyDecisionOutcomeKind.EXECUTED, detail = null)

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
        store = RoomDurableStateStore(
            database,
            "strategy-decision-outcome-history",
            DurableStrategyDecisionOutcomeHistory.KeyEncoder,
            StrategyDecisionOutcomeHistoryStateCodec(),
        )
    }

    @Test
    fun insertsAndRoundTripsAStrategyDecisionOutcomeHistoryThroughTheGenericRoomStore() {
        runBlocking {
            val state = StrategyDecisionOutcomeHistoryState(listOf(firstAttempt))
            val encodedKey = DurableStrategyDecisionOutcomeHistory.KeyEncoder.encode(decisionId)
            val encodedPayload = StrategyDecisionOutcomeHistoryStateCodec().encode(state)
            val persistedEntity = DurableStateEntity(
                namespace = "strategy-decision-outcome-history",
                scopeKey = encodedKey,
                statePayload = encodedPayload,
                schemaVersion = 1,
                recordVersion = 0L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("strategy-decision-outcome-history", encodedKey)).thenReturn(persistedEntity)

            val inserted = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<StrategyDecisionOutcomeHistoryState>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(decisionId, null, state, 1)),
            )
            val updated = assertIs<DurableStateCompareAndSetResult.Updated<StrategyDecisionOutcomeHistoryState>>(inserted.value)
            assertEquals(state, updated.record.state)

            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<StrategyDecisionOutcomeHistoryState>>>(
                store.load(decisionId),
            )
            val found = assertIs<DurableStateLoadResult.Found<StrategyDecisionOutcomeHistoryState>>(loaded.value)
            assertEquals(state, found.record.state)
        }
    }

    /**
     * Restart proof: nothing but the encoded row (returned here by the mocked
     * DAO, the same seam every other domain's own reopened-store proof uses)
     * survives a process restart. A freshly constructed [RoomDurableStateStore]
     * instance -- sharing only the underlying [DataLoomRoomDatabase], never
     * in-memory state from [store] -- must still recover every previously
     * committed attempt, in order.
     */
    @Test
    fun restartReopensAFreshStoreInstanceAndRecoversEveryPreviouslyCommittedAttempt() {
        runBlocking {
            val twoAttemptState = StrategyDecisionOutcomeHistoryState(listOf(firstAttempt, secondAttempt))
            val encodedKey = DurableStrategyDecisionOutcomeHistory.KeyEncoder.encode(decisionId)
            val persistedEntity = DurableStateEntity(
                namespace = "strategy-decision-outcome-history",
                scopeKey = encodedKey,
                statePayload = StrategyDecisionOutcomeHistoryStateCodec().encode(twoAttemptState),
                schemaVersion = 1,
                recordVersion = 1L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("strategy-decision-outcome-history", encodedKey)).thenReturn(persistedEntity)

            store.compareAndSet(DurableStateCompareAndSetRequest(decisionId, null, twoAttemptState, 1))

            val reopenedStore = RoomDurableStateStore(
                database,
                "strategy-decision-outcome-history",
                DurableStrategyDecisionOutcomeHistory.KeyEncoder,
                StrategyDecisionOutcomeHistoryStateCodec(),
            )
            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<StrategyDecisionOutcomeHistoryState>>>(
                reopenedStore.load(decisionId),
            )
            val found = assertIs<DurableStateLoadResult.Found<StrategyDecisionOutcomeHistoryState>>(loaded.value)
            assertEquals(twoAttemptState, found.record.state)
            assertEquals(
                listOf(StrategyDecisionOutcomeKind.FAILED, StrategyDecisionOutcomeKind.EXECUTED),
                found.record.state.retainedAttempts.map { it.outcomeKind },
            )
        }
    }

    /**
     * End-to-end proof through [DurableStrategyDecisionOutcomeHistory] itself,
     * not just the raw [RoomDurableStateStore]: appending a second attempt
     * through the real generic Room store retains both attempts, in order,
     * neither rejected as a conflict.
     */
    @Test
    fun durableStrategyDecisionOutcomeHistoryAppendsASecondAttemptThroughTheRealRoomStore() {
        runBlocking {
            val encodedKey = DurableStrategyDecisionOutcomeHistory.KeyEncoder.encode(decisionId)
            val firstState = StrategyDecisionOutcomeHistoryState(listOf(firstAttempt))
            val firstEntity = DurableStateEntity(
                namespace = "strategy-decision-outcome-history",
                scopeKey = encodedKey,
                statePayload = StrategyDecisionOutcomeHistoryStateCodec().encode(firstState),
                schemaVersion = 1,
                recordVersion = 0L,
            )
            val secondState = StrategyDecisionOutcomeHistoryState(listOf(firstAttempt, secondAttempt))
            val secondEntity = DurableStateEntity(
                namespace = "strategy-decision-outcome-history",
                scopeKey = encodedKey,
                statePayload = StrategyDecisionOutcomeHistoryStateCodec().encode(secondState),
                schemaVersion = 1,
                recordVersion = 1L,
            )
            // Three loads happen in sequence: append(first)'s own load sees
            // nothing yet, append(second)'s load sees the first committed
            // attempt, and history()'s final load sees both.
            whenever(dao.load("strategy-decision-outcome-history", encodedKey)).thenReturn(null, firstEntity, secondEntity)
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(firstEntity),
            )
            whenever(dao.compareAndSet(eq(0L), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(secondEntity),
            )

            val history = DurableStrategyDecisionOutcomeHistory(store)
            history.append(decisionId, firstAttempt)
            val outcome = history.append(decisionId, secondAttempt)

            val appended = assertIs<DurableStrategyDecisionOutcomeAppendOutcome.Appended>(outcome)
            assertEquals(2, appended.retainedCount)
            val current = assertIs<ProviderOperationResult.Success<List<StrategyDecisionEvent>>>(history.history(decisionId))
            assertEquals(listOf(firstAttempt, secondAttempt), current.value)
        }
    }
}

private fun attempt(
    committedAt: Long,
    outcome: StrategyDecisionOutcomeKind,
    detail: String?,
): StrategyDecisionEvent = StrategyDecisionEvent(
    planId = StrategyPlanId("plan-1"),
    requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
    effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
    effectiveProfileId = StrategyProfileId("profile-1"),
    configurationVersion = StrategyConfigurationVersion(1L),
    direction = SynchronizationDirection.PULL,
    mode = SynchronizationMode.DELTA,
    disposition = StrategyDisposition.EXECUTE,
    reasonCodes = listOf("CACHE_FIRST_DEFAULT"),
    outcomeKind = outcome,
    outcomeDetail = detail,
    committedAt = DataLoomInstant(committedAt),
)
