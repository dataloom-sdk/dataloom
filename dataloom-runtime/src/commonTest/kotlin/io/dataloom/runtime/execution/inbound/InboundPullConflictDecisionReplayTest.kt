package io.dataloom.runtime.execution.inbound

import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.synchronization.SynchronizationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InboundPullConflictDecisionReplayTest : ConflictDecisionApplicationFixture() {
    @Test
    fun sameDecisionAfterApplyFailure_replaysThroughAlreadyRecordedAndSucceeds() {
        val applyError = TestError(
            code = ErrorCode("APPLY-FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val unresolvedStore = InMemoryDurableStore<ConflictId, UnresolvedConflictRecord>()
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val resolver = MutableResolver(ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = resolver,
            unresolvedStore = unresolvedStore,
            resolvedStore = resolvedStore,
        )
        val firstStorage = storageWithCandidate(
            localEvent,
            applyResults = mutableListOf(ProviderOperationResult.Failure(applyError)),
        )

        val first = runSuspend {
            pipeline.execute(context(firstStorage, transport(changeSet(remoteEvent))))
        }
        assertIs<SynchronizationResult.Failed>(first)
        assertTrue(resolvedStore.state(conflictId) != null)

        val secondStorage = storageWithCandidate(localEvent)
        val second = runSuspend {
            pipeline.execute(context(secondStorage, transport(changeSet(remoteEvent))))
        }

        assertIs<SynchronizationResult.Succeeded>(second)
        assertEquals(1, secondStorage.applyCallCount)
        assertEquals(listOf(remoteEvent), secondStorage.appliedChangeSets.single().events)
    }

    @Test
    fun sameUseLocalDecisionAfterCheckpointFailure_replaysAndAdvancesCheckpoint() {
        val checkpointError = TestError(
            code = ErrorCode("CHECKPOINT-FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val resolver = MutableResolver(ConflictResolutionDecision.UseLocal())
        val pipeline = pipeline(
            resolver = resolver,
            resolvedStore = resolvedStore,
        )
        val firstStorage = FakeStorageProvider(
            candidateResults = mutableMapOf(
                entity.id.value to ProviderOperationResult.Success(
                    LocalConflictCandidateReadResult.Found(localEvent),
                ),
            ),
            writeResults = mutableListOf(
                ProviderOperationResult.Failure(checkpointError),
            ),
        )

        val first = runSuspend {
            pipeline.execute(
                context(
                    firstStorage,
                    transport(changeSet(remoteEvent), nextCheckpoint = checkpoint),
                ),
            )
        }
        assertIs<SynchronizationResult.Failed>(first)
        assertEquals(0, firstStorage.applyCallCount)
        assertTrue(resolvedStore.state(conflictId) != null)

        val secondStorage = storageWithCandidate(localEvent)
        val second = runSuspend {
            pipeline.execute(
                context(
                    secondStorage,
                    transport(changeSet(remoteEvent), nextCheckpoint = checkpoint),
                ),
            )
        }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(second)
        assertEquals(0L, succeeded.summary.inboundEventsApplied)
        assertEquals(0, secondStorage.applyCallCount)
        assertEquals(listOf(checkpoint), secondStorage.writtenCheckpoints)
    }

    @Test
    fun changedDecisionOnReplay_isRejectedBeforeStorageApplication() {
        val applyError = TestError(
            code = ErrorCode("APPLY-FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val resolver = MutableResolver(ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = resolver,
            resolvedStore = resolvedStore,
        )
        val firstStorage = storageWithCandidate(
            localEvent,
            applyResults = mutableListOf(ProviderOperationResult.Failure(applyError)),
        )
        runSuspend {
            pipeline.execute(context(firstStorage, transport(changeSet(remoteEvent))))
        }

        resolver.decision = ConflictResolutionDecision.UseLocal()
        val secondStorage = storageWithCandidate(localEvent)
        val second = runSuspend {
            pipeline.execute(context(secondStorage, transport(changeSet(remoteEvent))))
        }

        val failed = assertIs<SynchronizationResult.Failed>(second)
        assertEquals("DL-CONFLICT-DECISION-NON-CONVERGENT", failed.error.code.value)
        assertEquals(0, secondStorage.applyCallCount)
        assertEquals(
            ResolvedConflictDecisionKind.USE_REMOTE,
            resolvedStore.state(conflictId)?.decisionKind,
        )
    }

    @Test
    fun noResolvedDecisionLog_preservesHistoricalObservationalBehavior() {
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.UseLocal()),
            resolvedStore = null,
        )

        val result = runSuspend {
            pipeline.execute(context(storage, transport(changeSet(remoteEvent))))
        }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(1L, succeeded.summary.conflictsDetected)
        assertEquals(1L, succeeded.summary.inboundEventsApplied)
        assertEquals(listOf(remoteEvent), storage.appliedChangeSets.single().events)
    }

    @Test
    fun localCandidateReadFailure_blocksOnlyDecisionApplicationMode() {
        val candidateError = TestError(
            code = ErrorCode("LOCAL-CANDIDATE-FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val applicationStorage = FakeStorageProvider(
            candidateResults = mutableMapOf(
                entity.id.value to ProviderOperationResult.Failure(candidateError),
            ),
        )
        val applicationPipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.UseRemote()),
            resolvedStore = resolvedStore,
        )

        val applicationResult = runSuspend {
            applicationPipeline.execute(
                context(applicationStorage, transport(changeSet(remoteEvent))),
            )
        }
        val failed = assertIs<SynchronizationResult.Failed>(applicationResult)
        assertSame(candidateError, failed.error)
        assertEquals(0, applicationStorage.applyCallCount)

        val observationalStorage = FakeStorageProvider(
            candidateResults = mutableMapOf(
                entity.id.value to ProviderOperationResult.Failure(candidateError),
            ),
        )
        val observationalPipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.UseRemote()),
            resolvedStore = null,
        )
        val observationalResult = runSuspend {
            observationalPipeline.execute(
                context(observationalStorage, transport(changeSet(remoteEvent))),
            )
        }
        assertIs<SynchronizationResult.Succeeded>(observationalResult)
        assertEquals(1, observationalStorage.applyCallCount)
    }
}
