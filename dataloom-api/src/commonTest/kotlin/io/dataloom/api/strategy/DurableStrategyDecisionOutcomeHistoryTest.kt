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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies [DurableStrategyDecisionOutcomeHistory]'s append-only,
 * bounded-retention, and compare-and-set retry/failure behavior -- the
 * append-only counterpart to [DurableStrategyDecisionEventLogTest]'s
 * commit-once-log coverage, and structurally the same shape
 * [io.dataloom.api.asset.DurableAssetManifestHistoryTest] already proves for
 * a different domain.
 */
class DurableStrategyDecisionOutcomeHistoryTest {

    private val decisionId = StrategyDecisionId("decision-1")

    @Test
    fun keyEncoderEncodesEqualScopesIdenticallyAndDistinctScopesDifferently() {
        val encoder = DurableStrategyDecisionOutcomeHistory.KeyEncoder
        assertEquals(encoder.encode(StrategyDecisionId("d1")), encoder.encode(StrategyDecisionId("d1")))
        assertEquals("d1", encoder.encode(StrategyDecisionId("d1")))
        assertTrue(encoder.encode(StrategyDecisionId("d1")) != encoder.encode(StrategyDecisionId("d2")))
    }

    @Test
    fun historyIsEmptyBeforeAnySuccessfulAppend() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(InMemoryOutcomeHistoryStore())
        val result = assertIs<ProviderOperationResult.Success<List<StrategyDecisionEvent>>>(history.history(decisionId))
        assertEquals(emptyList(), result.value)
    }

    @Test
    fun firstAppendSucceeds() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(InMemoryOutcomeHistoryStore())
        val event = attempt(outcome = StrategyDecisionOutcomeKind.EXECUTED)

        val outcome = history.append(decisionId, event)

        val appended = assertIs<DurableStrategyDecisionOutcomeAppendOutcome.Appended>(outcome)
        assertEquals(event, appended.event)
        assertEquals(1, appended.retainedCount)
        assertEquals(listOf(event), attempts(history))
    }

    @Test
    fun repeatingTheSameOutcomeIsAppendedAsANewAttemptRatherThanRejected() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(InMemoryOutcomeHistoryStore())
        val first = attempt(outcome = StrategyDecisionOutcomeKind.FAILED, committedAt = 1_000L)
        val second = attempt(outcome = StrategyDecisionOutcomeKind.FAILED, committedAt = 2_000L)

        history.append(decisionId, first)
        val outcome = history.append(decisionId, second)

        // Unlike DurableStrategyDecisionEventLog, an identical repeat is never
        // a Conflict/AlreadyRecorded outcome -- it is just another attempt.
        val appended = assertIs<DurableStrategyDecisionOutcomeAppendOutcome.Appended>(outcome)
        assertEquals(second, appended.event)
        assertEquals(2, appended.retainedCount)
        assertEquals(listOf(first, second), attempts(history))
    }

    @Test
    fun differentOutcomesAcrossAttemptsAreAllRetainedInOrder() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(InMemoryOutcomeHistoryStore())
        val failed = attempt(outcome = StrategyDecisionOutcomeKind.FAILED, committedAt = 1_000L)
        val executed = attempt(outcome = StrategyDecisionOutcomeKind.EXECUTED, committedAt = 2_000L)

        history.append(decisionId, failed)
        history.append(decisionId, executed)

        assertEquals(listOf(failed, executed), attempts(history))
    }

    @Test
    fun retentionIsBoundedByMaxRetainedAttempts() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(InMemoryOutcomeHistoryStore(), maxRetainedAttempts = 2)
        val first = attempt(outcome = StrategyDecisionOutcomeKind.FAILED, committedAt = 1_000L)
        val second = attempt(outcome = StrategyDecisionOutcomeKind.FAILED, committedAt = 2_000L)
        val third = attempt(outcome = StrategyDecisionOutcomeKind.EXECUTED, committedAt = 3_000L)

        history.append(decisionId, first)
        history.append(decisionId, second)
        val outcome = history.append(decisionId, third)

        val appended = assertIs<DurableStrategyDecisionOutcomeAppendOutcome.Appended>(outcome)
        assertEquals(2, appended.retainedCount)
        assertEquals(listOf(second, third), attempts(history))
    }

    @Test
    fun distinctDecisionIdsAreIndependent() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(InMemoryOutcomeHistoryStore())
        val other = StrategyDecisionId("decision-2")
        val first = attempt(outcome = StrategyDecisionOutcomeKind.EXECUTED)
        val second = attempt(outcome = StrategyDecisionOutcomeKind.FAILED)

        history.append(decisionId, first)
        history.append(other, second)

        assertEquals(listOf(first), attempts(history, decisionId))
        assertEquals(listOf(second), attempts(history, other))
    }

    @Test
    fun maxRetainedAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableStrategyDecisionOutcomeHistory(InMemoryOutcomeHistoryStore(), maxRetainedAttempts = 0)
        }
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableStrategyDecisionOutcomeHistory(InMemoryOutcomeHistoryStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun appendRetriesAfterATransientConflictAndSucceeds() = runTest {
        val store = InMemoryOutcomeHistoryStore()
        val history = DurableStrategyDecisionOutcomeHistory(store)
        history.append(decisionId, attempt(outcome = StrategyDecisionOutcomeKind.FAILED))
        store.conflictOnNextCompareAndSetCalls = 1

        val outcome = history.append(decisionId, attempt(outcome = StrategyDecisionOutcomeKind.EXECUTED))

        val appended = assertIs<DurableStrategyDecisionOutcomeAppendOutcome.Appended>(outcome)
        assertEquals(2, appended.retainedCount)
    }

    @Test
    fun appendReturnsPersistenceFailureWhenLoadFails() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(FailingLoadStore())
        val outcome = history.append(decisionId, attempt(outcome = StrategyDecisionOutcomeKind.EXECUTED))
        assertIs<DurableStrategyDecisionOutcomeAppendOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun appendReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(FailingCompareAndSetStore())
        val outcome = history.append(decisionId, attempt(outcome = StrategyDecisionOutcomeKind.EXECUTED))
        assertIs<DurableStrategyDecisionOutcomeAppendOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun appendReturnsContentionLimitReachedWhenCompareAndSetAlwaysConflicts() = runTest {
        val history = DurableStrategyDecisionOutcomeHistory(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = history.append(decisionId, attempt(outcome = StrategyDecisionOutcomeKind.EXECUTED))
        assertIs<DurableStrategyDecisionOutcomeAppendOutcome.ContentionLimitReached>(outcome)
    }

    private suspend fun attempts(
        history: DurableStrategyDecisionOutcomeHistory,
        forDecisionId: StrategyDecisionId = decisionId,
    ): List<StrategyDecisionEvent> =
        (assertIs<ProviderOperationResult.Success<List<StrategyDecisionEvent>>>(history.history(forDecisionId))).value

    private fun attempt(
        outcome: StrategyDecisionOutcomeKind,
        committedAt: Long = 1_000L,
    ): StrategyDecisionEvent = StrategyDecisionEvent(
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
        effectiveStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
        effectiveProfileId = StrategyProfileId("profile-1"),
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.EXECUTE,
        reasonCodes = listOf("NETWORK_ONLY_DEFAULT"),
        outcomeKind = outcome,
        outcomeDetail = if (outcome == StrategyDecisionOutcomeKind.FAILED) "TRANSPORT_FAILURE" else null,
        committedAt = DataLoomInstant(committedAt),
    )

    /**
     * Minimal, non-thread-safe in-memory [DurableStateStore] fake used only
     * to prove [DurableStrategyDecisionOutcomeHistory] behaves as documented.
     * Not a production reference implementation -- see `RoomDurableStateStore`
     * in `dataloom-queue-room` for one.
     */
    private class InMemoryOutcomeHistoryStore : DurableStateStore<StrategyDecisionId, StrategyDecisionOutcomeHistoryState> {
        private val records = mutableMapOf<StrategyDecisionId, DurableStateRecord<StrategyDecisionOutcomeHistoryState>>()

        /** When positive, the next N compare-and-set calls report a conflict instead of applying. */
        var conflictOnNextCompareAndSetCalls: Int = 0

        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionOutcomeHistoryState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionOutcomeHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionOutcomeHistoryState>> {
            val current = records[request.scope]
            if (conflictOnNextCompareAndSetCalls > 0) {
                conflictOnNextCompareAndSetCalls -= 1
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
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

    private class FailingLoadStore : DurableStateStore<StrategyDecisionId, StrategyDecisionOutcomeHistoryState> {
        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionOutcomeHistoryState>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionOutcomeHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionOutcomeHistoryState>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<StrategyDecisionId, StrategyDecisionOutcomeHistoryState> {
        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionOutcomeHistoryState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionOutcomeHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionOutcomeHistoryState>> =
            ProviderOperationResult.Failure(testError())
    }

    private class AlwaysConflictStore : DurableStateStore<StrategyDecisionId, StrategyDecisionOutcomeHistoryState> {
        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionOutcomeHistoryState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionOutcomeHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionOutcomeHistoryState>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }
}

private fun testError(): DataLoomError = DurableStrategyDecisionOutcomeHistoryTestError(
    code = ErrorCode("DURABLE_STRATEGY_DECISION_OUTCOME_HISTORY_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableStrategyDecisionOutcomeHistoryTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
