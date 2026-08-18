package io.dataloom.api.conflict

import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
 * Verifies [DurableResolvedConflictDecisionLog]'s commit-once,
 * idempotent-retry, and conflict-detection behavior -- mirroring
 * [DurableUnresolvedConflictLogTest]'s and
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLogTest]'s shape for
 * the same [io.dataloom.api.state.DurableStateStore] adoption pattern.
 */
class DurableResolvedConflictDecisionLogTest {

    private val conflictId = ConflictId("conflict-1")
    private val entity = EntityReference(EntityType("note"), EntityId("note-1"))

    @Test
    fun keyEncoderEncodesEqualScopesIdenticallyAndDistinctScopesDifferently() {
        val encoder = DurableResolvedConflictDecisionLog.KeyEncoder
        assertEquals(encoder.encode(ConflictId("c1")), encoder.encode(ConflictId("c1")))
        assertEquals("c1", encoder.encode(ConflictId("c1")))
        assertEquals(encoder.encode(ConflictId("c1")) != encoder.encode(ConflictId("c2")), true)
    }

    @Test
    fun currentIsNullBeforeAnySuccessfulRecord() = runTest {
        val log = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val result = assertIs<ProviderOperationResult.Success<ResolvedConflictDecisionRecord?>>(log.current(conflictId))
        assertNull(result.value)
    }

    @Test
    fun firstRecordSucceeds() = runTest {
        val log = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val record = useRemoteRecord()

        val outcome = log.record(conflictId, record)

        val recorded = assertIs<DurableResolvedConflictDecisionRecordOutcome.Recorded>(outcome)
        assertEquals(record, recorded.record)
        assertEquals(record, currentRecord(log))
    }

    @Test
    fun recordingTheSameFactsAgainIsIdempotentEvenWithADifferentTimestamp() = runTest {
        val log = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val first = useRemoteRecord(committedAt = 1_000L)
        log.record(conflictId, first)
        val retried = useRemoteRecord(committedAt = 2_000L)

        val outcome = log.record(conflictId, retried)

        val already = assertIs<DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded>(outcome)
        // The original commit timestamp is preserved -- the retry never overwrites it.
        assertEquals(1_000L, already.record.committedAt.epochMilliseconds)
    }

    @Test
    fun recordingADifferentDecisionForTheSameConflictIdConflicts() = runTest {
        val log = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val first = useRemoteRecord()
        log.record(conflictId, first)
        val second = useLocalRecord()

        val outcome = log.record(conflictId, second)

        val conflict = assertIs<DurableResolvedConflictDecisionRecordOutcome.Conflict>(outcome)
        assertEquals(first, conflict.existing)
        assertEquals(second, conflict.attempted)
        // The first-recorded decision is preserved -- never silently overwritten by a later retry.
        assertEquals(first, currentRecord(log))
    }

    @Test
    fun distinctConflictIdsAreIndependent() = runTest {
        val log = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val other = ConflictId("conflict-2")
        log.record(conflictId, useRemoteRecord())
        log.record(other, useLocalRecord())

        assertEquals(useRemoteRecord(), currentRecord(log, conflictId))
        assertEquals(useLocalRecord(), currentRecord(log, other))
    }

    @Test
    fun aMergeDecisionRecordsOnlyStructuralIdentityNeverPayload() = runTest {
        val log = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val record = mergeRecord()

        val outcome = log.record(conflictId, record)

        val recorded = assertIs<DurableResolvedConflictDecisionRecordOutcome.Recorded>(outcome)
        assertEquals(ResolvedConflictDecisionKind.MERGE, recorded.record.decisionKind)
        assertEquals(ChangeEventId("merged-1"), recorded.record.mergedChange?.changeEventId)
    }

    @Test
    fun aFailDecisionRecordsOnlyTheBoundedErrorCode() = runTest {
        val log = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val record = failRecord()

        val outcome = log.record(conflictId, record)

        val recorded = assertIs<DurableResolvedConflictDecisionRecordOutcome.Recorded>(outcome)
        assertEquals(ResolvedConflictDecisionKind.FAIL, recorded.record.decisionKind)
        assertEquals("SIMULATED_FAILURE", recorded.record.failureErrorCode)
    }

    @Test
    fun mergedChangeMustBePresentIfAndOnlyIfDecisionKindIsMerge() {
        assertFailsWith<IllegalArgumentException> {
            useRemoteRecord().copy(
                decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
                mergedChange = UnresolvedConflictChangeSummary(ChangeEventId("x"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            useRemoteRecord().copy(decisionKind = ResolvedConflictDecisionKind.MERGE, mergedChange = null)
        }
    }

    @Test
    fun failureErrorCodeMustBePresentIfAndOnlyIfDecisionKindIsFail() {
        assertFailsWith<IllegalArgumentException> {
            useRemoteRecord().copy(decisionKind = ResolvedConflictDecisionKind.USE_REMOTE, failureErrorCode = "X")
        }
        assertFailsWith<IllegalArgumentException> {
            useRemoteRecord().copy(decisionKind = ResolvedConflictDecisionKind.FAIL, failureErrorCode = null)
        }
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun recordReturnsPersistenceFailureWhenLoadFails() = runTest {
        val log = DurableResolvedConflictDecisionLog(FailingLoadStore())
        val outcome = log.record(conflictId, useRemoteRecord())
        assertIs<DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun recordReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val log = DurableResolvedConflictDecisionLog(FailingCompareAndSetStore())
        val outcome = log.record(conflictId, useRemoteRecord())
        assertIs<DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun recordReturnsContentionLimitReachedWhenTheInsertRaceIsAlwaysLost() = runTest {
        val log = DurableResolvedConflictDecisionLog(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = log.record(conflictId, useRemoteRecord())
        assertIs<DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached>(outcome)
    }

    @Test
    fun recordRetriesAfterLosingTheInsertRaceAndReportsAlreadyRecorded() = runTest {
        val record = useRemoteRecord()
        val winningRecord = DurableStateRecord(state = record, version = 0L, schemaVersion = 1)
        val store = RaceThenConsistentStore(winningRecord)
        val log = DurableResolvedConflictDecisionLog(store)

        val outcome = log.record(conflictId, record)

        assertIs<DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded>(outcome)
    }

    private fun useRemoteRecord(committedAt: Long = 1_000L): ResolvedConflictDecisionRecord = ResolvedConflictDecisionRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = entity,
        localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
        remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
        conflictMetadata = DataLoomMetadata.Empty,
        resolverId = ConflictResolverId("resolver-1"),
        decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
        decisionMetadata = DataLoomMetadata.Empty,
        committedAt = DataLoomInstant(committedAt),
    )

    private fun useLocalRecord(): ResolvedConflictDecisionRecord = useRemoteRecord().copy(
        decisionKind = ResolvedConflictDecisionKind.USE_LOCAL,
    )

    private fun mergeRecord(): ResolvedConflictDecisionRecord = useRemoteRecord().copy(
        decisionKind = ResolvedConflictDecisionKind.MERGE,
        mergedChange = UnresolvedConflictChangeSummary(ChangeEventId("merged-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
    )

    private fun failRecord(): ResolvedConflictDecisionRecord = useRemoteRecord().copy(
        decisionKind = ResolvedConflictDecisionKind.FAIL,
        failureErrorCode = "SIMULATED_FAILURE",
    )

    private suspend fun currentRecord(
        log: DurableResolvedConflictDecisionLog,
        forConflictId: ConflictId = conflictId,
    ): ResolvedConflictDecisionRecord? =
        (assertIs<ProviderOperationResult.Success<ResolvedConflictDecisionRecord?>>(log.current(forConflictId))).value

    private class InMemoryResolvedConflictDecisionStore : DurableStateStore<ConflictId, ResolvedConflictDecisionRecord> {
        private val records = mutableMapOf<ConflictId, DurableStateRecord<ResolvedConflictDecisionRecord>>()

        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<ResolvedConflictDecisionRecord>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, ResolvedConflictDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ResolvedConflictDecisionRecord>> {
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

    private class FailingLoadStore : DurableStateStore<ConflictId, ResolvedConflictDecisionRecord> {
        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<ResolvedConflictDecisionRecord>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, ResolvedConflictDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ResolvedConflictDecisionRecord>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<ConflictId, ResolvedConflictDecisionRecord> {
        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<ResolvedConflictDecisionRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, ResolvedConflictDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ResolvedConflictDecisionRecord>> =
            ProviderOperationResult.Failure(testError())
    }

    private class AlwaysConflictStore : DurableStateStore<ConflictId, ResolvedConflictDecisionRecord> {
        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<ResolvedConflictDecisionRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, ResolvedConflictDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ResolvedConflictDecisionRecord>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }

    /**
     * Reports [DurableStateLoadResult.Missing] on its first [load], then a
     * losing [DurableStateCompareAndSetResult.Conflict] against
     * [winningRecord] on its first [compareAndSet] -- simulating a
     * concurrent recorder's insert landing between this call's load and its
     * own compareAndSet.
     */
    private class RaceThenConsistentStore(
        private val winningRecord: DurableStateRecord<ResolvedConflictDecisionRecord>,
    ) : DurableStateStore<ConflictId, ResolvedConflictDecisionRecord> {
        private var loadCalls = 0
        private var compareAndSetCalls = 0

        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<ResolvedConflictDecisionRecord>> {
            loadCalls += 1
            return ProviderOperationResult.Success(
                if (loadCalls == 1) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(winningRecord),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, ResolvedConflictDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ResolvedConflictDecisionRecord>> {
            compareAndSetCalls += 1
            check(compareAndSetCalls == 1) { "must not be called again once load() reports Found" }
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(winningRecord))
        }
    }
}

private fun testError(): DataLoomError = DurableResolvedConflictDecisionLogTestError(
    code = ErrorCode("DURABLE_RESOLVED_CONFLICT_DECISION_LOG_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableResolvedConflictDecisionLogTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
