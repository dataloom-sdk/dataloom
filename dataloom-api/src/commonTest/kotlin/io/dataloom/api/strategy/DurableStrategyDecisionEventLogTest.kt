package io.dataloom.api.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Verifies [DurableStrategyDecisionEventLog]'s commit-once, idempotent-retry,
 * and conflict-detection behavior.
 */
class DurableStrategyDecisionEventLogTest {

    private val decisionId = StrategyDecisionId("decision-1")

    @Test
    fun keyEncoderEncodesEqualScopesIdenticallyAndDistinctScopesDifferently() {
        val encoder = DurableStrategyDecisionEventLog.KeyEncoder
        assertEquals(encoder.encode(StrategyDecisionId("d1")), encoder.encode(StrategyDecisionId("d1")))
        assertEquals("d1", encoder.encode(StrategyDecisionId("d1")))
        assertEquals(encoder.encode(StrategyDecisionId("d1")) != encoder.encode(StrategyDecisionId("d2")), true)
    }

    @Test
    fun currentIsNullBeforeAnySuccessfulRecord() = runTest {
        val log = DurableStrategyDecisionEventLog(InMemoryStrategyDecisionEventStore())
        val result = assertIs<ProviderOperationResult.Success<StrategyDecisionEvent?>>(log.current(decisionId))
        assertNull(result.value)
    }

    @Test
    fun firstRecordSucceeds() = runTest {
        val log = DurableStrategyDecisionEventLog(InMemoryStrategyDecisionEventStore())
        val event = executedEvent()

        val outcome = log.record(decisionId, event)

        val recorded = assertIs<DurableStrategyDecisionRecordOutcome.Recorded>(outcome)
        assertEquals(event, recorded.event)
        assertEquals(event, currentEvent(log))
    }

    @Test
    fun recordingTheSameFactsAgainIsIdempotentEvenWithADifferentTimestamp() = runTest {
        val log = DurableStrategyDecisionEventLog(InMemoryStrategyDecisionEventStore())
        val first = executedEvent(committedAt = 1_000L)
        log.record(decisionId, first)
        val retried = executedEvent(committedAt = 2_000L)

        val outcome = log.record(decisionId, retried)

        val already = assertIs<DurableStrategyDecisionRecordOutcome.AlreadyRecorded>(outcome)
        // The original commit timestamp is preserved -- the retry never overwrites it.
        assertEquals(1_000L, already.event.committedAt.epochMilliseconds)
    }

    @Test
    fun recordingADifferentOutcomeForTheSameDecisionIdConflicts() = runTest {
        val log = DurableStrategyDecisionEventLog(InMemoryStrategyDecisionEventStore())
        val first = executedEvent()
        log.record(decisionId, first)
        val second = failedEvent()

        val outcome = log.record(decisionId, second)

        val conflict = assertIs<DurableStrategyDecisionRecordOutcome.Conflict>(outcome)
        assertEquals(first, conflict.existing)
        assertEquals(second, conflict.attempted)
        // The first-recorded outcome is preserved -- never silently overwritten by a later retry.
        assertEquals(first, currentEvent(log))
    }

    @Test
    fun distinctDecisionIdsAreIndependent() = runTest {
        val log = DurableStrategyDecisionEventLog(InMemoryStrategyDecisionEventStore())
        val other = StrategyDecisionId("decision-2")
        log.record(decisionId, executedEvent())
        log.record(other, failedEvent())

        assertEquals(executedEvent(), currentEvent(log, decisionId))
        assertEquals(failedEvent(), currentEvent(log, other))
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableStrategyDecisionEventLog(InMemoryStrategyDecisionEventStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun recordReturnsPersistenceFailureWhenLoadFails() = runTest {
        val log = DurableStrategyDecisionEventLog(FailingLoadStore())
        val outcome = log.record(decisionId, executedEvent())
        assertIs<DurableStrategyDecisionRecordOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun recordReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val log = DurableStrategyDecisionEventLog(FailingCompareAndSetStore())
        val outcome = log.record(decisionId, executedEvent())
        assertIs<DurableStrategyDecisionRecordOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun recordReturnsContentionLimitReachedWhenTheInsertRaceIsAlwaysLost() = runTest {
        val log = DurableStrategyDecisionEventLog(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = log.record(decisionId, executedEvent())
        assertIs<DurableStrategyDecisionRecordOutcome.ContentionLimitReached>(outcome)
    }

    @Test
    fun recordRetriesAfterLosingTheInsertRaceAndReportsAlreadyRecorded() = runTest {
        val event = executedEvent()
        val winningRecord = DurableStateRecord(state = event, version = 0L, schemaVersion = 1)
        val store = RaceThenConsistentStore(winningRecord)
        val log = DurableStrategyDecisionEventLog(store)

        val outcome = log.record(decisionId, event)

        assertIs<DurableStrategyDecisionRecordOutcome.AlreadyRecorded>(outcome)
    }

    private fun executedEvent(committedAt: Long = 1_000L): StrategyDecisionEvent = StrategyDecisionEvent(
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
        effectiveStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
        effectiveProfileId = StrategyProfileId("profile-1"),
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.EXECUTE,
        reasonCodes = listOf("NETWORK_ONLY_DEFAULT"),
        outcomeKind = StrategyDecisionOutcomeKind.EXECUTED,
        outcomeDetail = null,
        committedAt = DataLoomInstant(committedAt),
    )

    private fun failedEvent(): StrategyDecisionEvent = StrategyDecisionEvent(
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
        effectiveStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
        effectiveProfileId = StrategyProfileId("profile-1"),
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.EXECUTE,
        reasonCodes = listOf("NETWORK_ONLY_DEFAULT"),
        outcomeKind = StrategyDecisionOutcomeKind.FAILED,
        outcomeDetail = "TRANSPORT_FAILURE",
        committedAt = DataLoomInstant(1_000L),
    )

    private suspend fun currentEvent(
        log: DurableStrategyDecisionEventLog,
        forDecisionId: StrategyDecisionId = decisionId,
    ): StrategyDecisionEvent? =
        (assertIs<ProviderOperationResult.Success<StrategyDecisionEvent?>>(log.current(forDecisionId))).value

    private class InMemoryStrategyDecisionEventStore : DurableStateStore<StrategyDecisionId, StrategyDecisionEvent> {
        private val records = mutableMapOf<StrategyDecisionId, DurableStateRecord<StrategyDecisionEvent>>()

        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionEvent>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionEvent>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionEvent>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private class FailingLoadStore : DurableStateStore<StrategyDecisionId, StrategyDecisionEvent> {
        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionEvent>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionEvent>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionEvent>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<StrategyDecisionId, StrategyDecisionEvent> {
        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionEvent>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionEvent>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionEvent>> =
            ProviderOperationResult.Failure(testError())
    }

    private class AlwaysConflictStore : DurableStateStore<StrategyDecisionId, StrategyDecisionEvent> {
        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionEvent>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionEvent>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionEvent>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }

    /**
     * Reports [DurableStateLoadResult.Missing] on its first [load], then a
     * losing [DurableStateCompareAndSetResult.Conflict] against
     * [winningRecord] on its first [compareAndSet] -- simulating a concurrent
     * recorder's insert landing between this call's load and its own
     * compareAndSet.
     */
    private class RaceThenConsistentStore(
        private val winningRecord: DurableStateRecord<StrategyDecisionEvent>,
    ) : DurableStateStore<StrategyDecisionId, StrategyDecisionEvent> {
        private var loadCalls = 0
        private var compareAndSetCalls = 0

        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionEvent>> {
            loadCalls += 1
            return ProviderOperationResult.Success(
                if (loadCalls == 1) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(winningRecord),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionEvent>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionEvent>> {
            compareAndSetCalls += 1
            check(compareAndSetCalls == 1) { "must not be called again once load() reports Found" }
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(winningRecord))
        }
    }
}

private fun testError(): DataLoomError = DurableStrategyDecisionEventLogTestError(
    code = ErrorCode("DURABLE_STRATEGY_DECISION_EVENT_LOG_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableStrategyDecisionEventLogTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
