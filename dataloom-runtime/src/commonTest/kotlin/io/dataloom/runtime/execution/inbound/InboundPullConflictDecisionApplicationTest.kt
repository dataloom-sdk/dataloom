package io.dataloom.runtime.execution.inbound

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.synchronization.SynchronizationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InboundPullConflictDecisionApplicationTest : ConflictDecisionApplicationFixture() {
    @Test
    fun useRemote_isRecordedBeforeTheExactRemoteEventIsApplied() {
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val storage = storageWithCandidate(localEvent)
        val changeSet = changeSet(remoteEvent)
        val pipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.UseRemote()),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(context(storage, transport(changeSet)))
        }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(1L, succeeded.summary.inboundEventsReceived)
        assertEquals(1L, succeeded.summary.inboundEventsApplied)
        assertEquals(1L, succeeded.summary.conflictsDetected)
        assertEquals(listOf(remoteEvent), storage.appliedChangeSets.single().events)
        assertEquals(
            ResolvedConflictDecisionKind.USE_REMOTE,
            resolvedStore.state(conflictId)?.decisionKind,
        )
    }

    @Test
    fun useLocal_omitsTheRemoteEventAndStillAdvancesTheCheckpoint() {
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.UseLocal()),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(
                context(
                    storage,
                    transport(changeSet(remoteEvent), nextCheckpoint = checkpoint),
                ),
            )
        }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(1L, succeeded.summary.inboundEventsReceived)
        assertEquals(0L, succeeded.summary.inboundEventsApplied)
        assertEquals(1L, succeeded.summary.conflictsDetected)
        assertEquals(0, storage.applyCallCount)
        assertEquals(listOf(checkpoint), storage.writtenCheckpoints)
        assertEquals(
            ResolvedConflictDecisionKind.USE_LOCAL,
            resolvedStore.state(conflictId)?.decisionKind,
        )
    }

    @Test
    fun merge_replacesTheRemoteEventBeforeApplication() {
        val mergedEvent = ChangeEvent(
            id = ChangeEventId("merged-event"),
            entity = entity,
            operation = ChangeOperation.MERGE,
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = MutableResolver(
                ConflictResolutionDecision.Merge(
                    expectedEntity = entity,
                    resolvedChange = mergedEvent,
                ),
            ),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(context(storage, transport(changeSet(remoteEvent))))
        }

        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(listOf(mergedEvent), storage.appliedChangeSets.single().events)
        assertEquals(
            ResolvedConflictDecisionKind.MERGE,
            resolvedStore.state(conflictId)?.decisionKind,
        )
    }

    @Test
    fun defer_isDurablyRecordedAndBlocksApplicationAndCheckpoint() {
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.Defer()),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(
                context(
                    storage,
                    transport(changeSet(remoteEvent), nextCheckpoint = checkpoint),
                ),
            )
        }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals("DL-CONFLICT-DECISION-DEFERRED", failed.error.code.value)
        assertEquals(0, storage.applyCallCount)
        assertTrue(storage.writtenCheckpoints.isEmpty())
        assertEquals(
            ResolvedConflictDecisionKind.DEFER,
            resolvedStore.state(conflictId)?.decisionKind,
        )
    }

    @Test
    fun fail_returnsTheExactResolverErrorAfterDurableRecording() {
        val resolverError = TestError(
            code = ErrorCode("DOMAIN-CONFLICT-REJECTED"),
            category = ErrorCategory.CONFLICT,
            recoverability = Recoverability.NON_RECOVERABLE,
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.Fail(resolverError)),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(context(storage, transport(changeSet(remoteEvent))))
        }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(resolverError, failed.error)
        assertEquals(0, storage.applyCallCount)
        assertEquals(
            ResolvedConflictDecisionKind.FAIL,
            resolvedStore.state(conflictId)?.decisionKind,
        )
    }

    @Test
    fun missingResolver_isRecordedAsUnresolvedAndBlocksApplication() {
        val unresolvedStore = InMemoryDurableStore<ConflictId, UnresolvedConflictRecord>()
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = null,
            unresolvedStore = unresolvedStore,
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(context(storage, transport(changeSet(remoteEvent))))
        }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals("DL-CONFLICT-UNRESOLVED", failed.error.code.value)
        assertEquals(0, storage.applyCallCount)
        assertTrue(unresolvedStore.state(conflictId) != null)
        assertNull(resolvedStore.state(conflictId))
    }

    @Test
    fun resolvedDecisionPersistenceFailure_blocksBeforeApplication() {
        val persistenceError = TestError(
            code = ErrorCode("DECISION-STORE-FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(
            compareAndSetFailure = persistenceError,
        )
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.UseRemote()),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(context(storage, transport(changeSet(remoteEvent))))
        }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(persistenceError, failed.error)
        assertEquals(0, storage.applyCallCount)
    }
}
