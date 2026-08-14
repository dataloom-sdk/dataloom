package io.dataloom.runtime.facade

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
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
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.conflict.ConflictOrchestrationBindings
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * Proves [DataLoomBuilder.conflictDetectionConfiguration] actually reaches
 * the registered pipelines end to end — not just that
 * [io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline]
 * itself can run conflict detection when handed a configuration directly
 * (already covered by `InboundPullSynchronizationPipelineTest`), but that
 * [DataLoomBuilder] is capable of constructing and threading one through at
 * all, which nothing exercised before this test existed.
 */
class DataLoomBuilderConflictDetectionTest {

    private val entity = EntityReference(type = EntityType("document"), id = EntityId("doc-001"))
    private val conflictDetectorId = ConflictDetectorId("test-detector")

    @Test
    fun conflictDetectionConfiguration_whenConfigured_detectsARealConflict() = runBlocking {
        val localChange = ChangeEvent(
            id = ChangeEventId("local-change"),
            entity = entity,
            operation = ChangeOperation.UPDATE,
        )
        val storage = ConflictCapableStorageProvider(localCandidate = localChange)
        val transport = FakeTransportProvider(pullResult = ProviderOperationResult.Success(inboundChanges()))
        val detector = FakeConflictDetector(conflictDetectorId, conflictDetected(localChange))

        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport)
            .defaultProviderBindings(bindings())
            .conflictDetectionConfiguration(
                DataLoomConflictDetectionSpec(
                    detectors = listOf(detector),
                    resolvers = emptyList(),
                    bindings = ConflictOrchestrationBindings(conflictDetectorId, resolverId = null),
                    unresolvedConflictStore = InMemoryUnresolvedConflictStore(),
                ),
            )
            .build()

        dataLoom.initialize()
        val result = dataLoom.synchronize(pullRequest())

        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(executed.result)
        assertEquals(1, detector.invokeCount)
        assertEquals(1, succeeded.summary.conflictsDetected)
    }

    @Test
    fun conflictDetectionConfiguration_whenNotConfigured_behaviorIsUnchanged() = runBlocking {
        val localChange = ChangeEvent(
            id = ChangeEventId("local-change"),
            entity = entity,
            operation = ChangeOperation.UPDATE,
        )
        // The very same conflict-capable storage as the positive test --
        // proving the difference is solely whether conflictDetectionConfiguration
        // was called, not a difference in what the storage provider can do.
        val storage = ConflictCapableStorageProvider(localCandidate = localChange)
        val transport = FakeTransportProvider(pullResult = ProviderOperationResult.Success(inboundChanges()))

        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport)
            .defaultProviderBindings(bindings())
            .build()

        dataLoom.initialize()
        val result = dataLoom.synchronize(pullRequest())

        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(executed.result)
        assertEquals(0, storage.readLocalConflictCandidateCallCount)
        assertEquals(0, succeeded.summary.conflictsDetected)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun inboundChanges(): PullChangesResult.Changes = PullChangesResult.Changes(
        changeSet = ChangeSet(
            id = ChangeSetId("remote-changes"),
            events = listOf(
                ChangeEvent(id = ChangeEventId("remote-change"), entity = entity, operation = ChangeOperation.UPDATE),
            ),
        ),
        hasMore = false,
    )

    private fun conflictDetected(localChange: ChangeEvent): ConflictDetectionResult.ConflictDetected =
        ConflictDetectionResult.ConflictDetected(
            SynchronizationConflict(
                id = ConflictId("conflict-1"),
                type = ConflictType.CONCURRENT_CHANGE,
                entity = entity,
                localChange = localChange,
                remoteChange = ChangeEvent(
                    id = ChangeEventId("remote-change"),
                    entity = entity,
                    operation = ChangeOperation.UPDATE,
                ),
            ),
        )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = object : DataLoomClock {
            override fun now() = DataLoomInstant(1_000_000L)
        },
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { io.dataloom.api.identifier.SynchronizationEventId("event-001") },
            queueEntryIds = generator { io.dataloom.api.identifier.QueueEntryId("queue-001") },
            queueLeaseIds = generator { io.dataloom.api.identifier.QueueLeaseId("lease-001") },
            conflictIds = generator { ConflictId("conflict-001") },
        ),
    )

    private fun <T> generator(block: () -> T): io.dataloom.api.identifier.IdentifierGenerator<T> =
        object : io.dataloom.api.identifier.IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private fun bindings(): SynchronizationProviderBindings = SynchronizationProviderBindings(
        storageProviderId = ProviderId("storage-conflict"),
        transportProviderId = ProviderId("transport-conflict"),
    )

    private fun pullRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("conflict-workflow"),
        sessionId = SynchronizationSessionId("conflict-session"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("conflict-execution"),
            correlationId = CorrelationId("conflict-correlation"),
        ),
    )

    private class FakeConflictDetector(
        override val id: ConflictDetectorId,
        private val result: ConflictDetectionResult,
    ) : ConflictDetector {
        var invokeCount = 0
            private set

        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
            invokeCount++
            return result
        }
    }

    /** In-memory [DurableStateStore] fake, mirroring the one `DurableUnresolvedConflictLogTest` uses. */
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

    private class ConflictCapableStorageProvider(
        private val localCandidate: ChangeEvent,
    ) : StorageProvider {
        var readLocalConflictCandidateCallCount: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-conflict"),
            name = ProviderName("Conflict-capable storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readLocalConflictCandidate(
            request: LocalConflictCandidateReadRequest,
        ): ProviderOperationResult<LocalConflictCandidateReadResult> {
            readLocalConflictCandidateCallCount++
            return ProviderOperationResult.Success(LocalConflictCandidateReadResult.Found(localCandidate))
        }
    }

    private class FakeTransportProvider(
        private val pullResult: ProviderOperationResult<PullChangesResult>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-conflict"),
            name = ProviderName("Conflict test transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<io.dataloom.api.synchronization.ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(TestConflictError())

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = pullResult
    }

    private data class TestConflictError(
        override val code: io.dataloom.api.error.ErrorCode = io.dataloom.api.error.ErrorCode("PUSH_UNUSED"),
        override val category: io.dataloom.api.error.ErrorCategory = io.dataloom.api.error.ErrorCategory.NETWORK,
        override val severity: io.dataloom.api.error.ErrorSeverity = io.dataloom.api.error.ErrorSeverity.ERROR,
        override val recoverability: io.dataloom.api.error.Recoverability =
            io.dataloom.api.error.Recoverability.RECOVERABLE,
        override val message: String = "not used in this test",
        override val cause: Throwable? = null,
    ) : io.dataloom.api.error.DataLoomError
}
