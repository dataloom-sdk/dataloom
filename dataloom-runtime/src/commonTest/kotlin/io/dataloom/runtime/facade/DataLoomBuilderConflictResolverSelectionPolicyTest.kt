package io.dataloom.runtime.facade

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
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
import io.dataloom.runtime.conflict.ConflictResolverSelectionPolicy
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForEntityType
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/**
 * End-to-end proof that a [io.dataloom.runtime.conflict.ConflictResolverSelectionPolicy]
 * supplied through [DataLoomConflictDetectionSpec] reaches the real inbound
 * pull pipeline: two entity types conflict in ONE sync, each is resolved by
 * the resolver its entity-type rule names, and the resolved decisions are
 * genuinely applied (or omitted) and durably recorded per resolver.
 *
 * The two resolvers are the documented built-ins with opposite effects
 * (`dataloom.builtin.server-wins` keeps the remote event,
 * `dataloom.builtin.client-wins` omits it), so which resolver ran is
 * observable in what storage actually received, not only in bookkeeping.
 */
class DataLoomBuilderConflictResolverSelectionPolicyTest {

    private val invoiceEntity = EntityReference(EntityType("invoice"), EntityId("inv-1"))
    private val documentEntity = EntityReference(EntityType("document"), EntityId("doc-1"))
    private val detectorId = ConflictDetectorId("test-detector")
    private val serverWins = ConflictResolverId("dataloom.builtin.server-wins")
    private val clientWins = ConflictResolverId("dataloom.builtin.client-wins")

    private fun localChange(entity: EntityReference) =
        ChangeEvent(ChangeEventId("local-${entity.id.value}"), entity, ChangeOperation.UPDATE)

    private fun remoteChange(entity: EntityReference) =
        ChangeEvent(ChangeEventId("remote-${entity.id.value}"), entity, ChangeOperation.UPDATE)

    private fun conflictId(entity: EntityReference) = ConflictId("conflict-${entity.id.value}")

    @Test
    fun entityTypeRules_selectDifferentResolversForTwoEntityTypesInOneSync() = runBlocking {
        val storage = storage()
        val resolvedStore = InMemoryStore<ConflictId, ResolvedConflictDecisionRecord>()

        val dataLoom = build(
            storage = storage,
            resolvedStore = resolvedStore,
            bindings = ConflictOrchestrationBindings(
                detectorId = detectorId,
                resolverId = null,
                resolverSelectionPolicy = ConflictResolverSelectionPolicy(
                    listOf(
                        ForEntityType(invoiceEntity.type, serverWins),
                        ForEntityType(documentEntity.type, clientWins),
                    ),
                ),
            ),
        )

        val summary = synchronize(dataLoom)

        assertEquals(2L, summary.conflictsDetected)
        // invoice -> server-wins -> remote event applied; document -> client-wins -> omitted.
        assertEquals(1L, summary.inboundEventsApplied)
        val applied = storage.appliedEvents
        assertEquals(listOf(remoteChange(invoiceEntity)), applied)

        val invoiceRecord = assertNotNull(resolvedStore.state(conflictId(invoiceEntity)))
        assertEquals(serverWins, invoiceRecord.resolverId)
        assertEquals(ResolvedConflictDecisionKind.USE_REMOTE, invoiceRecord.decisionKind)
        val documentRecord = assertNotNull(resolvedStore.state(conflictId(documentEntity)))
        assertEquals(clientWins, documentRecord.resolverId)
        assertEquals(ResolvedConflictDecisionKind.USE_LOCAL, documentRecord.decisionKind)
    }

    @Test
    fun globalDefault_handlesEntityTypesWithoutARule() = runBlocking {
        val storage = storage()
        val resolvedStore = InMemoryStore<ConflictId, ResolvedConflictDecisionRecord>()

        val dataLoom = build(
            storage = storage,
            resolvedStore = resolvedStore,
            bindings = ConflictOrchestrationBindings(
                detectorId = detectorId,
                resolverId = serverWins,
                // Only documents are overridden; invoices fall through to the global default.
                resolverSelectionPolicy = ConflictResolverSelectionPolicy(
                    listOf(ForEntityType(documentEntity.type, clientWins)),
                ),
            ),
        )

        val summary = synchronize(dataLoom)

        assertEquals(1L, summary.inboundEventsApplied)
        assertEquals(listOf(remoteChange(invoiceEntity)), storage.appliedEvents)
        assertEquals(serverWins, assertNotNull(resolvedStore.state(conflictId(invoiceEntity))).resolverId)
        assertEquals(clientWins, assertNotNull(resolvedStore.state(conflictId(documentEntity))).resolverId)
    }

    @Test
    fun withoutAPolicy_oneResolverHandlesBothEntityTypes() = runBlocking {
        val storage = storage()
        val resolvedStore = InMemoryStore<ConflictId, ResolvedConflictDecisionRecord>()

        // Same wiring as the policy tests but with the legacy two-argument
        // bindings: one resolver for every conflict, exactly as before.
        val dataLoom = build(
            storage = storage,
            resolvedStore = resolvedStore,
            bindings = ConflictOrchestrationBindings(detectorId, clientWins),
        )

        val summary = synchronize(dataLoom)

        assertEquals(2L, summary.conflictsDetected)
        assertEquals(0L, summary.inboundEventsApplied)
        assertEquals(emptyList(), storage.appliedEvents)
        assertEquals(clientWins, assertNotNull(resolvedStore.state(conflictId(invoiceEntity))).resolverId)
        assertEquals(clientWins, assertNotNull(resolvedStore.state(conflictId(documentEntity))).resolverId)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun storage() = RecordingStorageProvider(
        localCandidates = mapOf(
            invoiceEntity.id.value to localChange(invoiceEntity),
            documentEntity.id.value to localChange(documentEntity),
        ),
    )

    private suspend fun synchronize(dataLoom: DataLoom): io.dataloom.api.synchronization.SynchronizationSummary {
        dataLoom.initialize()
        val result = dataLoom.synchronize(pullRequest())
        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        return assertIs<SynchronizationResult.Succeeded>(executed.result).summary
    }

    private fun build(
        storage: RecordingStorageProvider,
        resolvedStore: DurableStateStore<ConflictId, ResolvedConflictDecisionRecord>,
        bindings: ConflictOrchestrationBindings,
    ): DataLoom {
        val transport = FakeTransportProvider(
            ProviderOperationResult.Success(
                PullChangesResult.Changes(
                    changeSet = ChangeSet(
                        id = ChangeSetId("remote-changes"),
                        events = listOf(remoteChange(invoiceEntity), remoteChange(documentEntity)),
                    ),
                    hasMore = false,
                ),
            ),
        )
        return DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport)
            .defaultProviderBindings(providerBindings())
            .conflictDetectionConfiguration(
                DataLoomConflictDetectionSpec(
                    detectors = listOf(ConflictOnEveryPairDetector(detectorId)),
                    resolvers = emptyList(),
                    bindings = bindings,
                    unresolvedConflictStore = InMemoryStore<ConflictId, UnresolvedConflictRecord>(),
                    resolvedConflictDecisionStore = resolvedStore,
                ),
            )
            .build()
    }

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

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private fun providerBindings(): SynchronizationProviderBindings = SynchronizationProviderBindings(
        storageProviderId = ProviderId("storage-selection"),
        transportProviderId = ProviderId("transport-selection"),
    )

    private fun pullRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("selection-workflow"),
        sessionId = SynchronizationSessionId("selection-session"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("selection-execution"),
            correlationId = CorrelationId("selection-correlation"),
        ),
    )

    /** Reports a conflict, with an ID derived from the entity, for every local/remote pair. */
    private class ConflictOnEveryPairDetector(override val id: ConflictDetectorId) : ConflictDetector {
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(
                SynchronizationConflict(
                    id = ConflictId("conflict-${request.localChange.entity.id.value}"),
                    type = ConflictType.CONCURRENT_CHANGE,
                    entity = request.localChange.entity,
                    localChange = request.localChange,
                    remoteChange = request.remoteChange,
                ),
            )
    }

    private class InMemoryStore<S : Any, R : Any> : DurableStateStore<S, R> {
        private val records = mutableMapOf<S, DurableStateRecord<R>>()

        fun state(scope: S): R? = records[scope]?.state

        override suspend fun load(scope: S): ProviderOperationResult<DurableStateLoadResult<R>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<S, R>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<R>> {
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

    private class RecordingStorageProvider(
        private val localCandidates: Map<String, ChangeEvent>,
    ) : StorageProvider {
        val appliedEvents = mutableListOf<ChangeEvent>()

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-selection"),
            name = ProviderName("Selection-policy storage"),
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
        ): ProviderOperationResult<Unit> {
            appliedEvents += request.changeSet.events
            return ProviderOperationResult.Success(Unit)
        }

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
            val local = localCandidates[request.entity.id.value]
            return ProviderOperationResult.Success(
                if (local == null) {
                    LocalConflictCandidateReadResult.NotFound
                } else {
                    LocalConflictCandidateReadResult.Found(local)
                },
            )
        }
    }

    private class FakeTransportProvider(
        private val pullResult: ProviderOperationResult<PullChangesResult>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-selection"),
            name = ProviderName("Selection-policy transport"),
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
            error("push is not used by this pull-only test")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = pullResult
    }
}
