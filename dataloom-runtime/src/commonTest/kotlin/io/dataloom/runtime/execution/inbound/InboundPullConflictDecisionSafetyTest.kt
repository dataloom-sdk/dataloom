package io.dataloom.runtime.execution.inbound

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.synchronization.SynchronizationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InboundPullConflictDecisionSafetyTest : ConflictDecisionApplicationFixture() {
    @Test
    fun detectorContractMismatch_blocksAndIsNotDurablyRecorded() {
        val alternateLocal = localEvent.copy(id = ChangeEventId("different-local"))
        val detector = FixedDetector(
            ConflictDetectionResult.ConflictDetected(
                SynchronizationConflict(
                    id = conflictId,
                    type = ConflictType.CONCURRENT_CHANGE,
                    entity = entity,
                    localChange = alternateLocal,
                    remoteChange = remoteEvent,
                ),
            ),
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            detector = detector,
            resolver = MutableResolver(ConflictResolutionDecision.UseRemote()),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(context(storage, transport(changeSet(remoteEvent))))
        }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals("DL-CONFLICT-DETECTOR-CONTRACT-VIOLATION", failed.error.code.value)
        assertEquals(0, storage.applyCallCount)
        assertNull(resolvedStore.state(conflictId))
    }

    @Test
    fun mergeForDifferentEntity_blocksAndIsNotDurablyRecorded() {
        val otherEntity = EntityReference(
            type = EntityType("Document"),
            id = EntityId("document-other"),
        )
        val invalidMerge = ConflictResolutionDecision.Merge(
            expectedEntity = otherEntity,
            resolvedChange = ChangeEvent(
                id = ChangeEventId("invalid-merge"),
                entity = otherEntity,
                operation = ChangeOperation.MERGE,
            ),
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val storage = storageWithCandidate(localEvent)
        val pipeline = pipeline(
            resolver = MutableResolver(invalidMerge),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(context(storage, transport(changeSet(remoteEvent))))
        }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals("DL-CONFLICT-MERGE-CONTRACT-VIOLATION", failed.error.code.value)
        assertEquals(0, storage.applyCallCount)
        assertNull(resolvedStore.state(conflictId))
    }

    @Test
    fun mixedBatch_preservesOrderAndCountsOnlyEffectiveApplications() {
        val secondEntity = EntityReference(
            type = EntityType("Document"),
            id = EntityId("document-2"),
        )
        val secondRemote = ChangeEvent(
            id = ChangeEventId("remote-second"),
            entity = secondEntity,
            operation = ChangeOperation.UPDATE,
        )
        val storage = FakeStorageProvider(
            candidateResults = mutableMapOf(
                entity.id.value to ProviderOperationResult.Success(
                    LocalConflictCandidateReadResult.Found(localEvent),
                ),
                secondEntity.id.value to ProviderOperationResult.Success(
                    LocalConflictCandidateReadResult.NotFound,
                ),
            ),
        )
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val pipeline = pipeline(
            resolver = MutableResolver(ConflictResolutionDecision.UseLocal()),
            resolvedStore = resolvedStore,
        )

        val result = runSuspend {
            pipeline.execute(
                context(
                    storage,
                    transport(changeSet(remoteEvent, secondRemote)),
                ),
            )
        }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(2L, succeeded.summary.inboundEventsReceived)
        assertEquals(1L, succeeded.summary.inboundEventsApplied)
        assertEquals(1L, succeeded.summary.conflictsDetected)
        assertEquals(listOf(secondRemote), storage.appliedChangeSets.single().events)
    }
}
