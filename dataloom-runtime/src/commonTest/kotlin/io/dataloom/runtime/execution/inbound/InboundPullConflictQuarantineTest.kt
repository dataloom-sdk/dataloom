package io.dataloom.runtime.execution.inbound

import io.dataloom.api.conflict.ConflictAdministrationAuthorizationId
import io.dataloom.api.conflict.ConflictAdministrationCommandId
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationReason
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineRelease
import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.ConflictQuarantineStatus
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.DurableConflictQuarantineLog
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.runtime.conflict.ConflictQuarantineTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves a quarantined entity blocks inbound application and checkpoint
 * advancement exactly like the existing fail-closed `Defer`/`Fail` outcomes,
 * through the real [InboundPullSynchronizationPipeline].
 */
class InboundPullConflictQuarantineTest : ConflictDecisionApplicationFixture() {

    private val scope = ConflictQuarantineScope.of(entity)

    private class CountingResolver(
        override val id: ConflictResolverId,
        private val decision: ConflictResolutionDecision,
    ) : ConflictResolver {
        var invocations = 0
            private set

        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
            invocations++
            return decision
        }
    }

    private fun resolver(decision: ConflictResolutionDecision) = CountingResolver(resolverId, decision)

    private fun release() = ConflictQuarantineRelease(
        commandId = ConflictAdministrationCommandId("release-1"),
        principalId = ConflictAdministrationPrincipalId("operator"),
        authorizationId = ConflictAdministrationAuthorizationId("auth-1"),
        reason = ConflictAdministrationReason("upstream fixed"),
        releasedAt = FixedClock.now(),
    )

    private fun tracker(
        store: DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord>,
        threshold: Int,
    ) = ConflictQuarantineTracker(
        log = DurableConflictQuarantineLog(store, maximumStateUpdateAttempts = 2),
        clock = FixedClock,
        policy = ConflictQuarantinePolicy(occurrenceThreshold = threshold),
    )

    @Test
    fun aDeferLoopIsQuarantinedAtTheThreshold_andNothingIsAppliedOrCheckpointed() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val storage = storageWithCandidate(localEvent)
        val deferring = resolver(ConflictResolutionDecision.Defer())
        val pipeline = pipeline(
            resolver = deferring,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(quarantineStore, threshold = 3),
        )

        val codes = List(5) {
            val result = runSuspend {
                pipeline.execute(context(storage, transport(changeSet(remoteEvent), nextCheckpoint = checkpoint)))
            }
            assertIs<SynchronizationResult.Failed>(result).error.code.value
        }

        // The first two occurrences are re-resolved (and deferred); from the third on, quarantined.
        assertEquals(
            listOf(
                "DL-CONFLICT-DECISION-DEFERRED",
                "DL-CONFLICT-DECISION-DEFERRED",
                "DL-CONFLICT-QUARANTINED",
                "DL-CONFLICT-QUARANTINED",
                "DL-CONFLICT-QUARANTINED",
            ),
            codes,
        )
        assertEquals(2, deferring.invocations)
        assertEquals(0, storage.applyCallCount)
        assertTrue(storage.writtenCheckpoints.isEmpty())
        val record = quarantineStore.state(scope)
        assertEquals(ConflictQuarantineStatus.QUARANTINED, record?.status)
        assertEquals(3, record?.occurrenceCount)
        assertEquals(resolverId, record?.lastResolverId)
        assertEquals(conflictId, record?.lastConflictId)
    }

    @Test
    fun quarantineFailureShapeMatchesTheOtherFailClosedOutcomes() {
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = resolver(ConflictResolutionDecision.UseRemote()),
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(InMemoryDurableStore(), threshold = 2),
        )
        runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent)))) }

        val failed = assertIs<SynchronizationResult.Failed>(
            runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent)))) },
        )
        assertEquals(ErrorCategory.CONFLICT, failed.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failed.error.recoverability)
        assertEquals(1L, failed.summary.conflictsDetected)
    }

    @Test
    fun successfulResolutionsBelowTheThresholdApplyAndCheckpointNormally() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val storage = storageWithCandidate(localEvent)
        val remoteWins = resolver(ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = remoteWins,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(quarantineStore, threshold = 4),
        )

        repeat(3) {
            assertIs<SynchronizationResult.Succeeded>(
                runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent), nextCheckpoint = checkpoint))) },
            )
        }

        assertEquals(3, remoteWins.invocations)
        assertEquals(3, storage.applyCallCount)
        assertEquals(3, storage.writtenCheckpoints.size)

        // The fourth occurrence is the loop: quarantined, not applied, not checkpointed.
        val fourth = assertIs<SynchronizationResult.Failed>(
            runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent), nextCheckpoint = checkpoint))) },
        )
        assertEquals("DL-CONFLICT-QUARANTINED", fourth.error.code.value)
        assertEquals(3, remoteWins.invocations)
        assertEquals(3, storage.applyCallCount)
        assertEquals(3, storage.writtenCheckpoints.size)
    }

    @Test
    fun releaseMakesTheEntityResolvableAgain() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val storage = storageWithCandidate(localEvent)
        val remoteWins = resolver(ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = remoteWins,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(quarantineStore, threshold = 2),
        )
        val run = {
            runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent), nextCheckpoint = checkpoint))) }
        }
        assertIs<SynchronizationResult.Succeeded>(run())
        assertEquals("DL-CONFLICT-QUARANTINED", assertIs<SynchronizationResult.Failed>(run()).error.code.value)

        runSuspend {
            DurableConflictQuarantineLog(quarantineStore).release(scope, release())
        }

        assertIs<SynchronizationResult.Succeeded>(run())
        assertEquals(2, remoteWins.invocations)
    }

    @Test
    fun aCounterThatCannotBeUpdatedFailsClosedWithoutInvokingTheResolver() {
        val storage = storageWithCandidate(localEvent)
        val remoteWins = resolver(ConflictResolutionDecision.UseRemote())
        val failure = TestError(
            code = ErrorCode("QUARANTINE-STORE-DOWN"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val pipeline = pipeline(
            resolver = remoteWins,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(FailingQuarantineStore(failure), threshold = 5),
        )

        val failed = assertIs<SynchronizationResult.Failed>(
            runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent), nextCheckpoint = checkpoint))) },
        )

        assertEquals(failure.code, failed.error.code)
        assertEquals(0, remoteWins.invocations)
        assertEquals(0, storage.applyCallCount)
        assertTrue(storage.writtenCheckpoints.isEmpty())
    }

    @Test
    fun contentionOnTheCounterFailsClosedAsARecoverableStateError() {
        val storage = storageWithCandidate(localEvent)
        val remoteWins = resolver(ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = remoteWins,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(AlwaysLosingQuarantineStore(), threshold = 5),
        )

        val failed = assertIs<SynchronizationResult.Failed>(
            runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent)))) },
        )

        assertEquals("DL-CONFLICT-QUARANTINE-CONTENTION", failed.error.code.value)
        assertEquals(ErrorCategory.STATE, failed.error.category)
        assertEquals(Recoverability.RECOVERABLE, failed.error.recoverability)
        assertEquals(0, remoteWins.invocations)
        assertEquals(0, storage.applyCallCount)
    }

    @Test
    fun withoutATracker_repeatedConflictsAreResolvedForeverAsBefore() {
        val storage = storageWithCandidate(localEvent)
        val remoteWins = resolver(ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = remoteWins,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
        )

        repeat(25) {
            assertIs<SynchronizationResult.Succeeded>(
                runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent)))) },
            )
        }

        assertEquals(25, remoteWins.invocations)
    }

    @Test
    fun inObservationalMode_aQuarantinedConflictIsCountedButNothingBlocks() {
        val storage = storageWithCandidate(localEvent)
        val remoteWins = resolver(ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = remoteWins,
            resolvedStore = null,
            quarantineTracker = tracker(InMemoryDurableStore(), threshold = 2),
        )

        val summaries = List(3) {
            assertIs<SynchronizationResult.Succeeded>(
                runSuspend { pipeline.execute(context(storage, transport(changeSet(remoteEvent)))) },
            ).summary
        }

        assertEquals(listOf(1L, 1L, 1L), summaries.map { it.conflictsDetected })
        // The resolver stops being invoked once quarantined, but observation never blocks application.
        assertEquals(1, remoteWins.invocations)
    }

    private class FailingQuarantineStore(
        private val error: TestError,
    ) : DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> {
        override suspend fun load(
            scope: ConflictQuarantineScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConflictQuarantineRecord>> =
            ProviderOperationResult.Failure(error)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictQuarantineScope, ConflictQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConflictQuarantineRecord>> =
            ProviderOperationResult.Failure(error)
    }

    private class AlwaysLosingQuarantineStore : DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> {
        override suspend fun load(
            scope: ConflictQuarantineScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConflictQuarantineRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictQuarantineScope, ConflictQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConflictQuarantineRecord>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }
}
