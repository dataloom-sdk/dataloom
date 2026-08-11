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
 * Verifies [DurableUnresolvedConflictLog]'s commit-once, idempotent-retry,
 * and conflict-detection behavior.
 */
class DurableUnresolvedConflictLogTest {

    private val conflictId = ConflictId("conflict-1")

    @Test
    fun keyEncoderEncodesEqualScopesIdenticallyAndDistinctScopesDifferently() {
        val encoder = DurableUnresolvedConflictLog.KeyEncoder
        assertEquals(encoder.encode(ConflictId("c1")), encoder.encode(ConflictId("c1")))
        assertEquals("c1", encoder.encode(ConflictId("c1")))
        assertEquals(encoder.encode(ConflictId("c1")) != encoder.encode(ConflictId("c2")), true)
    }

    @Test
    fun currentIsNullBeforeAnySuccessfulRecord() = runTest {
        val log = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore())
        val result = assertIs<ProviderOperationResult.Success<UnresolvedConflictRecord?>>(log.current(conflictId))
        assertNull(result.value)
    }

    @Test
    fun firstRecordSucceeds() = runTest {
        val log = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore())
        val record = resolverNotConfiguredRecord()

        val outcome = log.record(conflictId, record)

        val recorded = assertIs<DurableUnresolvedConflictRecordOutcome.Recorded>(outcome)
        assertEquals(record, recorded.record)
        assertEquals(record, currentRecord(log))
    }

    @Test
    fun recordingTheSameFactsAgainIsIdempotentEvenWithADifferentTimestamp() = runTest {
        val log = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore())
        val first = resolverNotConfiguredRecord(committedAt = 1_000L)
        log.record(conflictId, first)
        val retried = resolverNotConfiguredRecord(committedAt = 2_000L)

        val outcome = log.record(conflictId, retried)

        val already = assertIs<DurableUnresolvedConflictRecordOutcome.AlreadyRecorded>(outcome)
        // The original commit timestamp is preserved -- the retry never overwrites it.
        assertEquals(1_000L, already.record.committedAt.epochMilliseconds)
    }

    @Test
    fun recordingDifferentFactsForTheSameConflictIdConflicts() = runTest {
        val log = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore())
        val first = resolverNotConfiguredRecord()
        log.record(conflictId, first)
        val second = resolverNotFoundRecord()

        val outcome = log.record(conflictId, second)

        val conflict = assertIs<DurableUnresolvedConflictRecordOutcome.Conflict>(outcome)
        assertEquals(first, conflict.existing)
        assertEquals(second, conflict.attempted)
        assertEquals(first, currentRecord(log))
    }

    @Test
    fun distinctConflictIdsAreIndependent() = runTest {
        val log = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore())
        val other = ConflictId("conflict-2")
        log.record(conflictId, resolverNotConfiguredRecord())
        log.record(other, resolverNotFoundRecord())

        assertEquals(resolverNotConfiguredRecord(), currentRecord(log, conflictId))
        assertEquals(resolverNotFoundRecord(), currentRecord(log, other))
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun recordReturnsPersistenceFailureWhenLoadFails() = runTest {
        val log = DurableUnresolvedConflictLog(FailingLoadStore())
        val outcome = log.record(conflictId, resolverNotConfiguredRecord())
        assertIs<DurableUnresolvedConflictRecordOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun recordReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val log = DurableUnresolvedConflictLog(FailingCompareAndSetStore())
        val outcome = log.record(conflictId, resolverNotConfiguredRecord())
        assertIs<DurableUnresolvedConflictRecordOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun recordReturnsContentionLimitReachedWhenTheInsertRaceIsAlwaysLost() = runTest {
        val log = DurableUnresolvedConflictLog(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = log.record(conflictId, resolverNotConfiguredRecord())
        assertIs<DurableUnresolvedConflictRecordOutcome.ContentionLimitReached>(outcome)
    }

    @Test
    fun recordRetriesAfterLosingTheInsertRaceAndReportsAlreadyRecorded() = runTest {
        val record = resolverNotConfiguredRecord()
        val winningRecord = DurableStateRecord(state = record, version = 0L, schemaVersion = 1)
        val store = RaceThenConsistentStore(winningRecord)
        val log = DurableUnresolvedConflictLog(store)

        val outcome = log.record(conflictId, record)

        assertIs<DurableUnresolvedConflictRecordOutcome.AlreadyRecorded>(outcome)
    }

    private fun resolverNotConfiguredRecord(committedAt: Long = 1_000L): UnresolvedConflictRecord =
        UnresolvedConflictRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("note"), EntityId("note-1")),
            localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
            conflictMetadata = DataLoomMetadata.Empty,
            reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
            committedAt = DataLoomInstant(committedAt),
        )

    private fun resolverNotFoundRecord(): UnresolvedConflictRecord = UnresolvedConflictRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("note"), EntityId("note-1")),
        localChange = UnresolvedConflictChangeSummary(ChangeEventId("local-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
        remoteChange = UnresolvedConflictChangeSummary(ChangeEventId("remote-1"), ChangeOperation.UPDATE, DataLoomMetadata.Empty),
        conflictMetadata = DataLoomMetadata.Empty,
        reason = UnresolvedConflictReason.RESOLVER_NOT_FOUND,
        committedAt = DataLoomInstant(1_000L),
    )

    private suspend fun currentRecord(
        log: DurableUnresolvedConflictLog,
        forConflictId: ConflictId = conflictId,
    ): UnresolvedConflictRecord? =
        (assertIs<ProviderOperationResult.Success<UnresolvedConflictRecord?>>(log.current(forConflictId))).value

    private class InMemoryUnresolvedConflictStore : DurableStateStore<ConflictId, UnresolvedConflictRecord> {
        private val records = mutableMapOf<ConflictId, DurableStateRecord<UnresolvedConflictRecord>>()

        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<UnresolvedConflictRecord>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, UnresolvedConflictRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<UnresolvedConflictRecord>> {
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

    private class FailingLoadStore : DurableStateStore<ConflictId, UnresolvedConflictRecord> {
        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<UnresolvedConflictRecord>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, UnresolvedConflictRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<UnresolvedConflictRecord>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<ConflictId, UnresolvedConflictRecord> {
        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<UnresolvedConflictRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, UnresolvedConflictRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<UnresolvedConflictRecord>> =
            ProviderOperationResult.Failure(testError())
    }

    private class AlwaysConflictStore : DurableStateStore<ConflictId, UnresolvedConflictRecord> {
        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<UnresolvedConflictRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, UnresolvedConflictRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<UnresolvedConflictRecord>> =
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
        private val winningRecord: DurableStateRecord<UnresolvedConflictRecord>,
    ) : DurableStateStore<ConflictId, UnresolvedConflictRecord> {
        private var loadCalls = 0
        private var compareAndSetCalls = 0

        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<UnresolvedConflictRecord>> {
            loadCalls += 1
            return ProviderOperationResult.Success(
                if (loadCalls == 1) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(winningRecord),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, UnresolvedConflictRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<UnresolvedConflictRecord>> {
            compareAndSetCalls += 1
            check(compareAndSetCalls == 1) { "must not be called again once load() reports Found" }
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(winningRecord))
        }
    }
}

private fun testError(): DataLoomError = DurableUnresolvedConflictLogTestError(
    code = ErrorCode("DURABLE_UNRESOLVED_CONFLICT_LOG_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableUnresolvedConflictLogTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
