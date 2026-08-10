package io.dataloom.api.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
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
 * Verifies [DurablePolicyDecisionLog]'s commit-once, idempotent-retry, and
 * conflict-detection behavior.
 */
class DurablePolicyDecisionLogTest {

    private val scope = PolicyDecisionScope(PolicySetId("retry-policy"), ExecutionId("execution-1"))

    @Test
    fun keyEncoderEncodesEqualScopesIdenticallyAndDistinctScopesDifferently() {
        val encoder = PolicyDecisionScope.KeyEncoder
        val a = PolicyDecisionScope(PolicySetId("p1"), ExecutionId("e1"))
        assertEquals(encoder.encode(a), encoder.encode(PolicyDecisionScope(PolicySetId("p1"), ExecutionId("e1"))))
        assertEquals(
            encoder.encode(a) != encoder.encode(PolicyDecisionScope(PolicySetId("p2"), ExecutionId("e1"))),
            true,
        )
        assertEquals(
            encoder.encode(a) != encoder.encode(PolicyDecisionScope(PolicySetId("p1"), ExecutionId("e2"))),
            true,
        )
    }

    @Test
    fun keyEncoderIsCollisionSafeAcrossAFieldBoundaryShift() {
        val encoder = PolicyDecisionScope.KeyEncoder
        // Without length-prefixing, "ab"+"c" and "a"+"bc" would concatenate identically.
        val first = PolicyDecisionScope(PolicySetId("ab"), ExecutionId("c"))
        val second = PolicyDecisionScope(PolicySetId("a"), ExecutionId("bc"))
        assertEquals(encoder.encode(first) != encoder.encode(second), true)
    }

    @Test
    fun currentIsNullBeforeAnySuccessfulCommit() = runTest {
        val log = DurablePolicyDecisionLog(InMemoryPolicyDecisionStore())
        val result = assertIs<ProviderOperationResult.Success<PolicyDecisionRecord?>>(log.current(scope))
        assertNull(result.value)
    }

    @Test
    fun firstCommitSucceeds() = runTest {
        val log = DurablePolicyDecisionLog(InMemoryPolicyDecisionStore())
        val decision = allowDecision()

        val outcome = log.commit(scope, decision, DataLoomInstant(1_000L))

        val committed = assertIs<DurablePolicyDecisionCommitOutcome.Committed>(outcome)
        assertEquals(decision, committed.record.decision)
        assertEquals(1_000L, committed.record.committedAt.epochMilliseconds)
        assertEquals(decision, currentDecision(log))
    }

    @Test
    fun committingTheSameDecisionAgainIsIdempotent() = runTest {
        val log = DurablePolicyDecisionLog(InMemoryPolicyDecisionStore())
        val decision = allowDecision()
        log.commit(scope, decision, DataLoomInstant(1_000L))

        val outcome = log.commit(scope, decision, DataLoomInstant(2_000L))

        val already = assertIs<DurablePolicyDecisionCommitOutcome.AlreadyCommitted>(outcome)
        // The original commit timestamp is preserved -- the second commit call never overwrites it.
        assertEquals(1_000L, already.record.committedAt.epochMilliseconds)
    }

    @Test
    fun committingADifferentDecisionForTheSameScopeConflicts() = runTest {
        val log = DurablePolicyDecisionLog(InMemoryPolicyDecisionStore())
        val first = allowDecision()
        log.commit(scope, first, DataLoomInstant(1_000L))
        val second = denyDecision()

        val outcome = log.commit(scope, second, DataLoomInstant(2_000L))

        val conflict = assertIs<DurablePolicyDecisionCommitOutcome.Conflict>(outcome)
        assertEquals(first, conflict.existing.decision)
        assertEquals(second, conflict.attempted)
        // The original commit is never overwritten.
        assertEquals(first, currentDecision(log))
    }

    @Test
    fun distinctScopesAreIndependent() = runTest {
        val log = DurablePolicyDecisionLog(InMemoryPolicyDecisionStore())
        val other = PolicyDecisionScope(PolicySetId("conflict-policy"), ExecutionId("execution-1"))
        log.commit(scope, allowDecision(), DataLoomInstant(1_000L))
        log.commit(other, denyDecision(), DataLoomInstant(1_000L))

        assertEquals(allowDecision(), currentDecision(log, scope))
        assertEquals(denyDecision(), currentDecision(log, other))
    }

    @Test
    fun commitRetriesAfterLosingTheInsertRaceAndReportsAlreadyCommitted() = runTest {
        val decision = allowDecision()
        val winningRecord = DurableStateRecord(
            state = PolicyDecisionRecord(decision, DataLoomInstant(500L)),
            version = 0L,
            schemaVersion = 1,
        )
        // load() sees Missing (no record yet), but a concurrent committer's insert wins the
        // race before this call's own compareAndSet -- so compareAndSet reports Conflict once,
        // and the retry's load() then sees the concurrent committer's Found record.
        val store = RaceThenConsistentStore(winningRecord)
        val log = DurablePolicyDecisionLog(store)

        val outcome = log.commit(scope, decision, DataLoomInstant(1_000L))

        val already = assertIs<DurablePolicyDecisionCommitOutcome.AlreadyCommitted>(outcome)
        assertEquals(500L, already.record.committedAt.epochMilliseconds)
    }

    @Test
    fun commitRetriesAfterLosingTheInsertRaceAndReportsConflictWhenTheWinnerDiffers() = runTest {
        val winningRecord = DurableStateRecord(
            state = PolicyDecisionRecord(denyDecision(), DataLoomInstant(500L)),
            version = 0L,
            schemaVersion = 1,
        )
        val store = RaceThenConsistentStore(winningRecord)
        val log = DurablePolicyDecisionLog(store)

        val outcome = log.commit(scope, allowDecision(), DataLoomInstant(1_000L))

        val conflict = assertIs<DurablePolicyDecisionCommitOutcome.Conflict>(outcome)
        assertEquals(denyDecision(), conflict.existing.decision)
        assertEquals(allowDecision(), conflict.attempted)
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurablePolicyDecisionLog(InMemoryPolicyDecisionStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun commitReturnsPersistenceFailureWhenLoadFails() = runTest {
        val log = DurablePolicyDecisionLog(FailingLoadStore())
        val outcome = log.commit(scope, allowDecision(), DataLoomInstant(1_000L))
        assertIs<DurablePolicyDecisionCommitOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun commitReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val log = DurablePolicyDecisionLog(FailingCompareAndSetStore())
        val outcome = log.commit(scope, allowDecision(), DataLoomInstant(1_000L))
        assertIs<DurablePolicyDecisionCommitOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun currentReturnsPersistenceFailureWhenLoadFails() = runTest {
        val log = DurablePolicyDecisionLog(FailingLoadStore())
        assertIs<ProviderOperationResult.Failure>(log.current(scope))
    }

    @Test
    fun commitReturnsContentionLimitReachedWhenTheInsertRaceIsAlwaysLost() = runTest {
        val log = DurablePolicyDecisionLog(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = log.commit(scope, allowDecision(), DataLoomInstant(1_000L))
        assertIs<DurablePolicyDecisionCommitOutcome.ContentionLimitReached>(outcome)
    }

    private fun allowDecision(): PolicyDecision = PolicyDecision(
        policySetId = scope.policySetId,
        outcome = PolicyCheckOutcome.Allow("no objection"),
        winningCheckId = PolicyCheckId("check-1"),
        evidence = listOf(PolicyCheckEvidence(PolicyCheckId("check-1"), PolicyCheckOutcome.Allow("no objection"))),
    )

    private fun denyDecision(): PolicyDecision = PolicyDecision(
        policySetId = scope.policySetId,
        outcome = PolicyCheckOutcome.Deny("blocked", DataLoomMetadata.of(mapOf("reason" to "quota"))),
        winningCheckId = PolicyCheckId("check-2"),
        evidence = listOf(
            PolicyCheckEvidence(PolicyCheckId("check-1"), PolicyCheckOutcome.Allow("no objection")),
            PolicyCheckEvidence(
                PolicyCheckId("check-2"),
                PolicyCheckOutcome.Deny("blocked", DataLoomMetadata.of(mapOf("reason" to "quota"))),
            ),
        ),
    )

    private suspend fun currentDecision(
        log: DurablePolicyDecisionLog,
        forScope: PolicyDecisionScope = scope,
    ): PolicyDecision? =
        (assertIs<ProviderOperationResult.Success<PolicyDecisionRecord?>>(log.current(forScope))).value?.decision

    /**
     * Minimal, non-thread-safe in-memory [DurableStateStore] fake used only to
     * prove [DurablePolicyDecisionLog] behaves as documented. Not a production
     * reference implementation — see `RoomDurableStateStore` in
     * `dataloom-queue-room` for one.
     */
    private class InMemoryPolicyDecisionStore : DurableStateStore<PolicyDecisionScope, PolicyDecisionRecord> {
        private val records = mutableMapOf<PolicyDecisionScope, DurableStateRecord<PolicyDecisionRecord>>()

        /** When positive, the next N compare-and-set calls report a conflict instead of applying. */
        var conflictOnNextCompareAndSetCalls: Int = 0

        override suspend fun load(
            scope: PolicyDecisionScope,
        ): ProviderOperationResult<DurableStateLoadResult<PolicyDecisionRecord>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<PolicyDecisionScope, PolicyDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<PolicyDecisionRecord>> {
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

    private class FailingLoadStore : DurableStateStore<PolicyDecisionScope, PolicyDecisionRecord> {
        override suspend fun load(
            scope: PolicyDecisionScope,
        ): ProviderOperationResult<DurableStateLoadResult<PolicyDecisionRecord>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<PolicyDecisionScope, PolicyDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<PolicyDecisionRecord>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<PolicyDecisionScope, PolicyDecisionRecord> {
        override suspend fun load(
            scope: PolicyDecisionScope,
        ): ProviderOperationResult<DurableStateLoadResult<PolicyDecisionRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<PolicyDecisionScope, PolicyDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<PolicyDecisionRecord>> =
            ProviderOperationResult.Failure(testError())
    }

    /**
     * Reports [DurableStateLoadResult.Missing] on its first [load], then a
     * losing [DurableStateCompareAndSetResult.Conflict] against
     * [winningRecord] on its first [compareAndSet] -- simulating a concurrent
     * committer's insert landing between this call's load and its own
     * compareAndSet. Every call after that behaves as if [winningRecord] were
     * genuinely persisted.
     */
    private class RaceThenConsistentStore(
        private val winningRecord: DurableStateRecord<PolicyDecisionRecord>,
    ) : DurableStateStore<PolicyDecisionScope, PolicyDecisionRecord> {
        private var loadCalls = 0
        private var compareAndSetCalls = 0

        override suspend fun load(
            scope: PolicyDecisionScope,
        ): ProviderOperationResult<DurableStateLoadResult<PolicyDecisionRecord>> {
            loadCalls += 1
            return ProviderOperationResult.Success(
                if (loadCalls == 1) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(winningRecord),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<PolicyDecisionScope, PolicyDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<PolicyDecisionRecord>> {
            compareAndSetCalls += 1
            check(compareAndSetCalls == 1) { "must not be called again once load() reports Found" }
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(winningRecord))
        }
    }

    private class AlwaysConflictStore : DurableStateStore<PolicyDecisionScope, PolicyDecisionRecord> {
        override suspend fun load(
            scope: PolicyDecisionScope,
        ): ProviderOperationResult<DurableStateLoadResult<PolicyDecisionRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<PolicyDecisionScope, PolicyDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<PolicyDecisionRecord>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }
}

private fun testError(): DataLoomError = DurablePolicyDecisionLogTestError(
    code = ErrorCode("DURABLE_POLICY_DECISION_LOG_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurablePolicyDecisionLogTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
