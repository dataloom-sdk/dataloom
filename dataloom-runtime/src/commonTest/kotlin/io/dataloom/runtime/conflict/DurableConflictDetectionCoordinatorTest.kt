package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
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
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies [DurableConflictDetectionCoordinator] durably records only the
 * two unresolved orchestration outcomes, leaves every other outcome
 * untouched, and never lets a durable-recording failure hide the real
 * [ConflictOrchestrationResult].
 */
class DurableConflictDetectionCoordinatorTest {

    private val entityType = EntityType("invoice")
    private val entityId = EntityId("entity-001")
    private val entityRef = io.dataloom.api.change.EntityReference(entityType, entityId)

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

    private val syncRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(executionId = ExecutionId("execution-001"), correlationId = CorrelationId("corr-001")),
    )
    private val detectionRequest = ConflictDetectionRequest(syncRequest, localEvent, remoteEvent)
    private val detectorId = ConflictDetectorId("detector-1")
    private val resolverId = ConflictResolverId("resolver-1")
    private val clock = FixedDataLoomClock(DataLoomInstant(5_000L))

    @Test
    fun noConflictProducesNoDurableRecordAttempt() = runTest {
        val coordinator = coordinator(detector = FakeDetector(detectorId, ConflictDetectionResult.NoConflict))

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId)),
        )

        assertIs<ConflictOrchestrationResult.NoConflict>(result.orchestration)
        assertNull(result.unresolvedRecordOutcome)
    }

    @Test
    fun detectorNotFoundProducesNoDurableRecordAttempt() = runTest {
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(ConflictDetectorId("missing"), resolverId)),
        )

        assertIs<ConflictOrchestrationResult.DetectorNotFound>(result.orchestration)
        assertNull(result.unresolvedRecordOutcome)
    }

    @Test
    fun resolvedConflictProducesNoDurableRecordAttempt() = runTest {
        val decision = ConflictResolutionDecision.UseLocal()
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            resolver = FakeResolver(resolverId, decision),
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId)),
        )

        val resolved = assertIs<ConflictOrchestrationResult.Resolved>(result.orchestration)
        assertEquals(decision, resolved.decision)
        assertNull(result.unresolvedRecordOutcome)
    }

    @Test
    fun resolverNotConfiguredIsDurablyRecorded() = runTest {
        val log = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            unresolvedConflictLog = log,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId = null)),
        )

        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result.orchestration)
        val recorded = assertIs<DurableUnresolvedConflictRecordOutcome.Recorded>(result.unresolvedRecordOutcome)
        assertEquals(UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED, recorded.record.reason)
        assertEquals(ConflictType.CONCURRENT_CHANGE, recorded.record.conflictType)
        assertEquals(5_000L, recorded.record.committedAt.epochMilliseconds)

        val current = assertIs<ProviderOperationResult.Success<UnresolvedConflictRecord?>>(log.current(conflictId))
        assertEquals(recorded.record, current.value)
    }

    @Test
    fun resolverNotFoundIsDurablyRecorded() = runTest {
        val log = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            unresolvedConflictLog = log,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, ConflictResolverId("missing"))),
        )

        assertIs<ConflictOrchestrationResult.ResolverNotFound>(result.orchestration)
        val recorded = assertIs<DurableUnresolvedConflictRecordOutcome.Recorded>(result.unresolvedRecordOutcome)
        assertEquals(UnresolvedConflictReason.RESOLVER_NOT_FOUND, recorded.record.reason)
    }

    @Test
    fun repeatedUnresolvedOutcomesForTheSameConflictAreIdempotent() = runTest {
        val log = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            unresolvedConflictLog = log,
        )
        val orchestrationRequest = request(bindings = ConflictOrchestrationBindings(detectorId, resolverId = null))

        coordinator.detectAndResolve(orchestrationRequest)
        val second = coordinator.detectAndResolve(orchestrationRequest)

        assertIs<DurableUnresolvedConflictRecordOutcome.AlreadyRecorded>(second.unresolvedRecordOutcome)
    }

    @Test
    fun durableRecordingFailureDoesNotSuppressTheRealOrchestrationResult() = runTest {
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            unresolvedConflictLog = DurableUnresolvedConflictLog(FailingStore()),
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId = null)),
        )

        // The real orchestration outcome is still returned even though durable recording failed.
        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result.orchestration)
        assertIs<DurableUnresolvedConflictRecordOutcome.PersistenceFailure>(result.unresolvedRecordOutcome)
    }

    @Test
    fun aNullResolvedConflictDecisionLogSkipsRecordingAResolvedOutcome() = runTest {
        val decision = ConflictResolutionDecision.UseRemote()
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            resolver = FakeResolver(resolverId, decision),
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId)),
        )

        assertIs<ConflictOrchestrationResult.Resolved>(result.orchestration)
        assertEquals(null, result.resolvedDecisionRecordOutcome)
    }

    @Test
    fun aResolvedOutcomeIsDurablyRecordedWhenAResolvedConflictDecisionLogIsConfigured() = runTest {
        val decision = ConflictResolutionDecision.UseRemote()
        val resolvedLog = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            resolver = FakeResolver(resolverId, decision),
            resolvedConflictDecisionLog = resolvedLog,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId)),
        )

        assertIs<ConflictOrchestrationResult.Resolved>(result.orchestration)
        val recorded = assertIs<DurableResolvedConflictDecisionRecordOutcome.Recorded>(result.resolvedDecisionRecordOutcome)
        assertEquals(ResolvedConflictDecisionKind.USE_REMOTE, recorded.record.decisionKind)
        assertEquals(resolverId, recorded.record.resolverId)
        assertEquals(5_000L, recorded.record.committedAt.epochMilliseconds)

        val current = assertIs<ProviderOperationResult.Success<ResolvedConflictDecisionRecord?>>(resolvedLog.current(conflictId))
        assertEquals(recorded.record, current.value)
    }

    @Test
    fun repeatedResolvedOutcomesForTheSameConflictAreIdempotent() = runTest {
        val decision = ConflictResolutionDecision.UseRemote()
        val resolvedLog = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            resolver = FakeResolver(resolverId, decision),
            resolvedConflictDecisionLog = resolvedLog,
        )
        val orchestrationRequest = request(bindings = ConflictOrchestrationBindings(detectorId, resolverId))

        coordinator.detectAndResolve(orchestrationRequest)
        val second = coordinator.detectAndResolve(orchestrationRequest)

        assertIs<DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded>(second.resolvedDecisionRecordOutcome)
    }

    @Test
    fun aMergeDecisionIsRecordedWithOnlyStructuralIdentityNeverPayload() = runTest {
        val mergedEntity = io.dataloom.api.change.EntityReference(entityType, entityId)
        val mergedChangeEvent = ChangeEvent(
            ChangeEventId("merged-event"),
            mergedEntity,
            ChangeOperation.UPDATE,
            payload = io.dataloom.api.payload.DataLoomPayload(
                io.dataloom.api.payload.PayloadContentType("application/octet-stream"),
                byteArrayOf(1, 2, 3),
            ),
        )
        val decision = ConflictResolutionDecision.Merge(expectedEntity = mergedEntity, resolvedChange = mergedChangeEvent)
        val resolvedLog = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            resolver = FakeResolver(resolverId, decision),
            resolvedConflictDecisionLog = resolvedLog,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId)),
        )

        val recorded = assertIs<DurableResolvedConflictDecisionRecordOutcome.Recorded>(result.resolvedDecisionRecordOutcome)
        assertEquals(ResolvedConflictDecisionKind.MERGE, recorded.record.decisionKind)
        assertEquals(ChangeEventId("merged-event"), recorded.record.mergedChange?.changeEventId)
    }

    @Test
    fun aFailDecisionIsRecordedWithOnlyTheBoundedErrorCode() = runTest {
        val error = object : DataLoomError {
            override val code = ErrorCode("SIMULATED_RESOLUTION_FAILURE")
            override val category = ErrorCategory.VALIDATION
            override val severity = ErrorSeverity.ERROR
            override val recoverability = Recoverability.NON_RECOVERABLE
            override val message = "Simulated resolution failure with sensitive detail that must never be persisted."
            override val cause: Throwable? = null
        }
        val decision = ConflictResolutionDecision.Fail(error = error)
        val resolvedLog = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            resolver = FakeResolver(resolverId, decision),
            resolvedConflictDecisionLog = resolvedLog,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId)),
        )

        val recorded = assertIs<DurableResolvedConflictDecisionRecordOutcome.Recorded>(result.resolvedDecisionRecordOutcome)
        assertEquals(ResolvedConflictDecisionKind.FAIL, recorded.record.decisionKind)
        assertEquals("SIMULATED_RESOLUTION_FAILURE", recorded.record.failureErrorCode)
    }

    private fun coordinator(
        detector: ConflictDetector,
        resolver: ConflictResolver? = null,
        unresolvedConflictLog: DurableUnresolvedConflictLog = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore()),
        resolvedConflictDecisionLog: DurableResolvedConflictDecisionLog? = null,
        operationalEventOutbox: DurableOperationalEventOutbox? = null,
        operationalEventOutboxScope: OperationalEventOutboxScope? = null,
    ): DurableConflictDetectionCoordinator {
        val detectorRegistry = ConflictDetectorRegistry(listOf(detector))
        val resolverRegistry = ConflictResolverRegistry(if (resolver == null) emptyList() else listOf(resolver))
        val orchestrator = SynchronizationConflictOrchestrator(detectorRegistry, resolverRegistry)
        return DurableConflictDetectionCoordinator(
            orchestrator,
            unresolvedConflictLog,
            clock,
            resolvedConflictDecisionLog,
            operationalEventOutbox,
            operationalEventOutboxScope,
        )
    }

    private fun request(bindings: ConflictOrchestrationBindings): ConflictOrchestrationRequest =
        ConflictOrchestrationRequest(detectionRequest, bindings)

    private class FakeDetector(
        override val id: ConflictDetectorId,
        private val result: ConflictDetectionResult,
    ) : ConflictDetector {
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult = result
    }

    private class FakeResolver(
        override val id: ConflictResolverId,
        private val decision: ConflictResolutionDecision,
    ) : ConflictResolver {
        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision = decision
    }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
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

    private class FailingStore : DurableStateStore<ConflictId, UnresolvedConflictRecord> {
        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<UnresolvedConflictRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, UnresolvedConflictRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<UnresolvedConflictRecord>> =
            ProviderOperationResult.Failure(
                object : DataLoomError {
                    override val code = ErrorCode("DURABLE_CONFLICT_DETECTION_COORDINATOR_TEST_FAILURE")
                    override val category = ErrorCategory.STORAGE
                    override val severity = ErrorSeverity.ERROR
                    override val recoverability = Recoverability.RECOVERABLE
                    override val message = "Simulated store failure."
                    override val cause: Throwable? = null
                },
            )
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

    // -------------------------------------------------------------------------
    // Operational-event outbox bridging (opt-in)
    // -------------------------------------------------------------------------

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records =
            mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
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

    @Test
    fun unconfiguredOutbox_leavesNoTraceOfAnUnresolvedOutcome() = runTest {
        // Neither operationalEventOutbox nor operationalEventOutboxScope supplied -- defaults to null/null.
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            unresolvedConflictLog = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore()),
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId = null)),
        )

        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result.orchestration)
        assertIs<DurableUnresolvedConflictRecordOutcome.Recorded>(result.unresolvedRecordOutcome)
    }

    @Test
    fun configuredOutbox_durablyAppendsAnEnvelope_forAnUnresolvedOutcome() = runTest {
        val outboxScope = OperationalEventOutboxScope("test-conflict-resolution-events")
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            unresolvedConflictLog = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore()),
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = outboxScope,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId = null)),
        )

        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result.orchestration)
        val entries = outbox.entries(outboxScope)
        assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(entries)
        assertEquals(1, entries.value.size)
        assertEquals("dataloom.conflict.resolution.resolver_not_configured", entries.value[0].type.value)
        assertEquals(CorrelationId(conflictId.value), entries.value[0].correlationId)
    }

    @Test
    fun unconfiguredOutbox_leavesNoTraceOfAResolvedOutcome() = runTest {
        val decision = ConflictResolutionDecision.UseRemote()
        val resolvedLog = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            resolver = FakeResolver(resolverId, decision),
            resolvedConflictDecisionLog = resolvedLog,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId)),
        )

        assertIs<ConflictOrchestrationResult.Resolved>(result.orchestration)
        assertIs<DurableResolvedConflictDecisionRecordOutcome.Recorded>(result.resolvedDecisionRecordOutcome)
    }

    @Test
    fun configuredOutbox_durablyAppendsAnEnvelope_forAResolvedOutcome() = runTest {
        val decision = ConflictResolutionDecision.UseRemote()
        val resolvedLog = DurableResolvedConflictDecisionLog(InMemoryResolvedConflictDecisionStore())
        val outboxScope = OperationalEventOutboxScope("test-conflict-resolution-events")
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            resolver = FakeResolver(resolverId, decision),
            resolvedConflictDecisionLog = resolvedLog,
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = outboxScope,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId)),
        )

        assertIs<ConflictOrchestrationResult.Resolved>(result.orchestration)
        val entries = outbox.entries(outboxScope)
        assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(entries)
        assertEquals(1, entries.value.size)
        assertEquals("dataloom.conflict.resolution.resolved.use_remote", entries.value[0].type.value)
        assertEquals(CorrelationId(conflictId.value), entries.value[0].correlationId)
    }

    @Test
    fun bridgingFailureNeverSuppressesTheRealOrchestrationResult() = runTest {
        // operationalEventOutbox backed by a store that fails every call; the coordinator's own
        // return value must be unaffected since bridging failures are swallowed.
        val outboxScope = OperationalEventOutboxScope("test-conflict-resolution-events")
        val outbox = DurableOperationalEventOutbox(FailingOperationalEventOutboxStore())
        val coordinator = coordinator(
            detector = FakeDetector(detectorId, ConflictDetectionResult.ConflictDetected(sampleConflict)),
            unresolvedConflictLog = DurableUnresolvedConflictLog(InMemoryUnresolvedConflictStore()),
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = outboxScope,
        )

        val result = coordinator.detectAndResolve(
            request(bindings = ConflictOrchestrationBindings(detectorId, resolverId = null)),
        )

        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result.orchestration)
        assertIs<DurableUnresolvedConflictRecordOutcome.Recorded>(result.unresolvedRecordOutcome)
    }

    private class FailingOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(
                object : DataLoomError {
                    override val code = ErrorCode("CONFLICT_RESOLUTION_OUTBOX_TEST_FAILURE")
                    override val category = ErrorCategory.STORAGE
                    override val severity = ErrorSeverity.ERROR
                    override val recoverability = Recoverability.RECOVERABLE
                    override val message = "Simulated outbox store failure."
                    override val cause: Throwable? = null
                },
            )

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(
                object : DataLoomError {
                    override val code = ErrorCode("CONFLICT_RESOLUTION_OUTBOX_TEST_FAILURE")
                    override val category = ErrorCategory.STORAGE
                    override val severity = ErrorSeverity.ERROR
                    override val recoverability = Recoverability.RECOVERABLE
                    override val message = "Simulated outbox store failure."
                    override val cause: Throwable? = null
                },
            )
    }
}
