package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * End-to-end proof that [LastWriteWinsConflictResolver] is a genuinely
 * selectable, genuinely invoked [io.dataloom.api.conflict.ConflictResolver]
 * -- registered through the real [ConflictResolverRegistry], selected
 * through real [ConflictOrchestrationBindings], invoked by the real
 * [SynchronizationConflictOrchestrator.detectAndResolve], and its decision
 * durably recorded by the real [DurableConflictDetectionCoordinator] wired to
 * a [DurableResolvedConflictDecisionLog] -- not just exercised in isolation
 * by [LastWriteWinsConflictResolverTest].
 */
class LastWriteWinsConflictResolverOrchestrationIntegrationTest {

    private val entityType = EntityType("invoice")
    private val entityId = EntityId("entity-001")
    private val entityRef = EntityReference(entityType, entityId)
    private val localEvent = ChangeEvent(ChangeEventId("event-local"), entityRef, ChangeOperation.UPDATE)
    private val remoteEvent = ChangeEvent(ChangeEventId("event-remote"), entityRef, ChangeOperation.UPDATE)
    private val conflictId = ConflictId("conflict-001")
    private val sampleConflict = SynchronizationConflict(
        id = conflictId,
        type = ConflictType.CONCURRENT_CHANGE,
        entity = entityRef,
        localChange = localEvent,
        remoteChange = remoteEvent,
    )
    private val detectorId = ConflictDetectorId("detector-1")
    private val syncRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(executionId = ExecutionId("execution-001"), correlationId = CorrelationId("corr-001")),
    )
    private val detectionRequest = ConflictDetectionRequest(syncRequest, localEvent, remoteEvent)

    @Test
    fun theOrchestratorSelectsAndInvokesTheRegisteredResolverAndReturnsItsRealDecision() = runTest {
        val resolver = LastWriteWinsConflictResolver()
        val orchestrator = SynchronizationConflictOrchestrator(
            detectorRegistry = ConflictDetectorRegistry(listOf(FakeDetector(detectorId, sampleConflict))),
            resolverRegistry = ConflictResolverRegistry(listOf(resolver)),
        )
        val bindings = ConflictOrchestrationBindings(detectorId, resolver.id)

        val result = orchestrator.detectAndResolve(ConflictOrchestrationRequest(detectionRequest, bindings))

        val resolved = assertIs<ConflictOrchestrationResult.Resolved>(result)
        assertEquals(resolver.id, resolved.resolverId)
        assertIs<ConflictResolutionDecision.UseRemote>(resolved.decision)
    }

    @Test
    fun theCoordinatorDurablyRecordsTheResolversRealDecisionEndToEnd() = runTest {
        val resolver = LastWriteWinsConflictResolver()
        val orchestrator = SynchronizationConflictOrchestrator(
            detectorRegistry = ConflictDetectorRegistry(listOf(FakeDetector(detectorId, sampleConflict))),
            resolverRegistry = ConflictResolverRegistry(listOf(resolver)),
        )
        val resolvedLog = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val coordinator = DurableConflictDetectionCoordinator(
            orchestrator = orchestrator,
            unresolvedConflictLog = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore()),
            clock = FixedDataLoomClock(DataLoomInstant(5_000L)),
            resolvedConflictDecisionLog = resolvedLog,
        )
        val bindings = ConflictOrchestrationBindings(detectorId, resolver.id)

        val result = coordinator.detectAndResolve(ConflictOrchestrationRequest(detectionRequest, bindings))

        assertIs<ConflictOrchestrationResult.Resolved>(result.orchestration)
        val recorded = assertIs<DurableResolvedConflictDecisionRecordOutcome.Recorded>(result.resolvedDecisionRecordOutcome)
        assertEquals(ResolvedConflictDecisionKind.USE_REMOTE, recorded.record.decisionKind)
        assertEquals(resolver.id, recorded.record.resolverId)
        assertEquals(5_000L, recorded.record.committedAt.epochMilliseconds)

        val current = assertIs<ProviderOperationResult.Success<ResolvedConflictDecisionRecord?>>(resolvedLog.current(conflictId))
        assertEquals(recorded.record, current.value)
    }

    @Test
    fun aNullResolvedConflictDecisionLogRecordsNothingAndReturnsNullOutcome() = runTest {
        val resolver = LastWriteWinsConflictResolver()
        val orchestrator = SynchronizationConflictOrchestrator(
            detectorRegistry = ConflictDetectorRegistry(listOf(FakeDetector(detectorId, sampleConflict))),
            resolverRegistry = ConflictResolverRegistry(listOf(resolver)),
        )
        val coordinator = DurableConflictDetectionCoordinator(
            orchestrator = orchestrator,
            unresolvedConflictLog = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore()),
            clock = FixedDataLoomClock(DataLoomInstant(5_000L)),
        )
        val bindings = ConflictOrchestrationBindings(detectorId, resolver.id)

        val result = coordinator.detectAndResolve(ConflictOrchestrationRequest(detectionRequest, bindings))

        assertIs<ConflictOrchestrationResult.Resolved>(result.orchestration)
        assertEquals(null, result.resolvedDecisionRecordOutcome)
    }

    private class FakeDetector(
        override val id: ConflictDetectorId,
        private val conflict: SynchronizationConflict,
    ) : ConflictDetector {
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(conflict)
    }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

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
}
