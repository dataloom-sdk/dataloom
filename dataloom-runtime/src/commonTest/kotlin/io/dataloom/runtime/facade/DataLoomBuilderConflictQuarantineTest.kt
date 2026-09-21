package io.dataloom.runtime.facade

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationDecision
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationId
import io.dataloom.api.conflict.ConflictAdministrationAuthorizer
import io.dataloom.api.conflict.ConflictAdministrationCommandId
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetRequest
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetResult
import io.dataloom.api.conflict.ConflictAdministrationExecutionResult
import io.dataloom.api.conflict.ConflictAdministrationExecutor
import io.dataloom.api.conflict.ConflictAdministrationLoadResult
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationReason
import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.api.conflict.ConflictAdministrationStateStore
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineReleaseRequest
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.ConflictQuarantineStatus
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
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
import io.dataloom.runtime.conflict.ConflictQuarantineReleaseResult
import io.dataloom.runtime.conflict.ConflictResolverSelectionPolicy
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForEntityType
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * End-to-end proof of loop/non-convergence quarantine through
 * [DataLoomBuilder]: a real repeated-sync loop is stopped at the threshold,
 * blocks application and the checkpoint, is denied release by default, and
 * resumes after an authorized release. Also shows the slice-1 selection
 * policy keeps routing per entity type alongside quarantine.
 */
class DataLoomBuilderConflictQuarantineTest {

    private val invoice = EntityReference(EntityType("invoice"), EntityId("inv-1"))
    private val document = EntityReference(EntityType("document"), EntityId("doc-1"))
    private val detectorId = ConflictDetectorId("test-detector")
    private val loopResolverId = ConflictResolverId("test.loop-resolver")

    private fun local(entity: EntityReference) =
        ChangeEvent(ChangeEventId("local-${entity.id.value}"), entity, ChangeOperation.UPDATE)

    private fun remote(entity: EntityReference) =
        ChangeEvent(ChangeEventId("remote-${entity.id.value}"), entity, ChangeOperation.UPDATE)

    /** Resolver whose decision the test can flip, counting invocations. */
    private class SwitchableResolver(override val id: ConflictResolverId) : ConflictResolver {
        var decision: ConflictResolutionDecision = ConflictResolutionDecision.Defer()
        var invocations = 0
            private set

        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
            invocations++
            return decision
        }
    }

    private class Fixture(
        val dataLoom: DataLoom,
        val storage: RecordingStorageProvider,
        val resolver: SwitchableResolver,
        val quarantineStore: InMemoryStore<ConflictQuarantineScope, ConflictQuarantineRecord>,
    )

    private fun fixture(
        threshold: Int = 3,
        withQuarantine: Boolean = true,
        releaseAuthorizer: ConflictAdministrationAuthorizer? = null,
        batch: List<EntityReference> = listOf(invoice),
        bindings: ConflictOrchestrationBindings? = null,
        extraResolvers: List<ConflictResolver> = emptyList(),
    ): Fixture {
        val storage = RecordingStorageProvider(
            mapOf(invoice.id.value to local(invoice), document.id.value to local(document)),
        )
        val transport = FakeTransportProvider(
            ProviderOperationResult.Success(
                PullChangesResult.Changes(
                    changeSet = ChangeSet(ChangeSetId("remote"), batch.map { remote(it) }),
                    hasMore = false,
                    nextCheckpoint = SynchronizationCheckpoint(
                        key = io.dataloom.api.identifier.CheckpointKey("selection-workflow"),
                        token = io.dataloom.api.identifier.CheckpointToken("next"),
                    ),
                ),
            ),
        )
        val resolver = SwitchableResolver(loopResolverId)
        val quarantineStore = InMemoryStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val quarantineSpec = DataLoomConflictQuarantineSpec(
            store = quarantineStore,
            policy = ConflictQuarantinePolicy(occurrenceThreshold = threshold),
        )
        val builder = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport)
            .defaultProviderBindings(providerBindings())
            .conflictDetectionConfiguration(
                DataLoomConflictDetectionSpec(
                    detectors = listOf(ConflictOnEveryPairDetector(detectorId)),
                    resolvers = listOf(resolver) + extraResolvers,
                    bindings = bindings ?: ConflictOrchestrationBindings(detectorId, loopResolverId),
                    unresolvedConflictStore = InMemoryStore<ConflictId, UnresolvedConflictRecord>(),
                    resolvedConflictDecisionStore = InMemoryStore<ConflictId, ResolvedConflictDecisionRecord>(),
                    quarantine = if (withQuarantine) quarantineSpec else null,
                ),
            )
        if (releaseAuthorizer != null) {
            builder.conflictAdministrationConfiguration(
                DataLoomConflictAdministrationSpec(
                    authorizer = releaseAuthorizer,
                    stateStore = UnusedCommandStore,
                    executor = UnusedExecutor,
                    unresolvedConflictStore = InMemoryStore<ConflictId, UnresolvedConflictRecord>(),
                    resolvedConflictDecisionStore = InMemoryStore<ConflictId, ResolvedConflictDecisionRecord>(),
                    quarantine = if (withQuarantine) quarantineSpec else null,
                ),
            )
        }
        return Fixture(builder.build(), storage, resolver, quarantineStore)
    }

    private suspend fun DataLoom.syncOnce(): SynchronizationResult {
        initialize()
        val executed = assertIs<SynchronizationExecutionResult.Executed>(synchronize(pullRequest()))
        return executed.result
    }

    private suspend fun DataLoom.syncCode(): String? =
        (syncOnce() as? SynchronizationResult.Failed)?.error?.code?.value

    private class OptedInAuthorizer : ConflictAdministrationAuthorizer {
        override suspend fun authorize(request: ConflictAdministrationRequest): ConflictAdministrationAuthorizationDecision =
            ConflictAdministrationAuthorizationDecision.Denied("NOT_USED")

        override suspend fun authorizeQuarantineRelease(
            request: ConflictQuarantineReleaseRequest,
        ): ConflictAdministrationAuthorizationDecision =
            ConflictAdministrationAuthorizationDecision.Authorized(ConflictAdministrationAuthorizationId("auth-ok"))
    }

    /** A pre-quarantine authorizer: it authorizes manual resolutions and never overrides release. */
    private class LegacyAuthorizer : ConflictAdministrationAuthorizer {
        override suspend fun authorize(request: ConflictAdministrationRequest): ConflictAdministrationAuthorizationDecision =
            ConflictAdministrationAuthorizationDecision.Authorized(ConflictAdministrationAuthorizationId("legacy"))
    }

    private fun release(command: String) = ConflictQuarantineReleaseRequest(
        commandId = ConflictAdministrationCommandId(command),
        entityType = invoice.type,
        entityId = invoice.id,
        principalId = ConflictAdministrationPrincipalId("operator"),
        requestedAt = DataLoomInstant(1L),
        reason = ConflictAdministrationReason("upstream fixed"),
    )

    // -------------------------------------------------------------------------

    @Test
    fun aRealSyncLoopIsQuarantinedAtTheThreshold_andBlocksApplicationAndCheckpoint() = runBlocking {
        val f = fixture(threshold = 3)

        val codes = List(5) { f.dataLoom.syncCode() }

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
        assertEquals(2, f.resolver.invocations, "the resolver must not run again once quarantined")
        assertTrue(f.storage.appliedEvents.isEmpty())
        assertEquals(0, f.storage.checkpointWrites)
        val record = assertNotNull(f.quarantineStore.state(ConflictQuarantineScope.of(invoice)))
        assertEquals(ConflictQuarantineStatus.QUARANTINED, record.status)
        assertEquals(3, record.occurrenceCount)
        assertEquals(loopResolverId, record.lastResolverId)
    }

    @Test
    fun releaseIsDeniedByDefault_thenAnAuthorizedReleaseResumesResolution() = runBlocking {
        val f = fixture(threshold = 2, releaseAuthorizer = LegacyAuthorizer())
        val admin = checkNotNull(f.dataLoom.conflictAdministration)

        f.dataLoom.syncCode()
        assertEquals("DL-CONFLICT-QUARANTINED", f.dataLoom.syncCode())

        val denied = assertIs<ConflictQuarantineReleaseResult.AuthorizationDenied>(admin.releaseQuarantine(release("cmd-1")))
        assertEquals("QUARANTINE_RELEASE_NOT_AUTHORIZED", denied.reasonCode)
        assertEquals("DL-CONFLICT-QUARANTINED", f.dataLoom.syncCode())
        assertEquals(1, f.resolver.invocations)
    }

    @Test
    fun anOptedInAuthorizerReleasesAndTheEntityIsResolvedAndAppliedAgain() = runBlocking {
        val f = fixture(threshold = 2, releaseAuthorizer = OptedInAuthorizer())
        val admin = checkNotNull(f.dataLoom.conflictAdministration)

        f.dataLoom.syncCode()
        assertEquals("DL-CONFLICT-QUARANTINED", f.dataLoom.syncCode())

        f.resolver.decision = ConflictResolutionDecision.UseRemote()
        assertIs<ConflictQuarantineReleaseResult.Released>(admin.releaseQuarantine(release("cmd-1")))
        assertIs<ConflictQuarantineReleaseResult.AlreadyReleased>(admin.releaseQuarantine(release("cmd-1")))

        assertEquals(null, f.dataLoom.syncCode(), "after release the conflict is resolved and applied")
        assertEquals(listOf(remote(invoice)), f.storage.appliedEvents)
        assertEquals(1, f.storage.checkpointWrites)
        assertEquals(2, f.resolver.invocations)
        assertEquals(1, f.quarantineStore.state(ConflictQuarantineScope.of(invoice))?.releaseCount)
    }

    @Test
    fun withoutTheOptInTheLoopIsNeverQuarantined() = runBlocking {
        val f = fixture(withQuarantine = false)

        val codes = List(12) { f.dataLoom.syncCode() }

        assertTrue(codes.all { it == "DL-CONFLICT-DECISION-DEFERRED" })
        assertEquals(12, f.resolver.invocations)
        assertTrue(f.quarantineStore.isEmpty())
    }

    @Test
    fun withoutQuarantineOnTheAdministrationSpec_releaseIsNotConfigured() = runBlocking {
        val f = fixture(withQuarantine = false, releaseAuthorizer = OptedInAuthorizer())
        val admin = checkNotNull(f.dataLoom.conflictAdministration)
        assertEquals(ConflictQuarantineReleaseResult.NotConfigured, admin.releaseQuarantine(release("cmd-1")))
    }

    @Test
    fun theSelectionPolicyStillRoutesPerEntityType_andQuarantineIsPerEntity() = runBlocking {
        val documentResolver = SwitchableResolver(ConflictResolverId("test.document-resolver")).apply {
            decision = ConflictResolutionDecision.UseRemote()
        }
        val f = fixture(
            threshold = 2,
            batch = listOf(invoice),
            extraResolvers = listOf(documentResolver),
            bindings = ConflictOrchestrationBindings(
                detectorId = detectorId,
                resolverId = null,
                resolverSelectionPolicy = ConflictResolverSelectionPolicy(
                    listOf(
                        ForEntityType(invoice.type, loopResolverId),
                        ForEntityType(document.type, documentResolver.id),
                    ),
                ),
            ),
        )

        // The invoice loops (Defer via the slice-1 entity-type rule) and is quarantined...
        assertEquals("DL-CONFLICT-DECISION-DEFERRED", f.dataLoom.syncCode())
        assertEquals("DL-CONFLICT-QUARANTINED", f.dataLoom.syncCode())
        assertEquals(1, f.resolver.invocations)
        assertEquals(0, documentResolver.invocations)
        assertEquals(loopResolverId, f.quarantineStore.state(ConflictQuarantineScope.of(invoice))?.lastResolverId)
        // ...and quarantine is keyed by entity, so another entity's record does not exist.
        assertEquals(null, f.quarantineStore.state(ConflictQuarantineScope.of(document)))
    }

    @Test
    fun quarantineRequiresTheDecisionStore_failFastAtSpecConstruction() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomConflictDetectionSpec(
                detectors = emptyList(),
                resolvers = emptyList(),
                bindings = ConflictOrchestrationBindings(detectorId, null),
                unresolvedConflictStore = InMemoryStore<ConflictId, UnresolvedConflictRecord>(),
                resolvedConflictDecisionStore = null,
                quarantine = DataLoomConflictQuarantineSpec(InMemoryStore()),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

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

    private fun providerBindings() = SynchronizationProviderBindings(
        storageProviderId = ProviderId("storage-quarantine"),
        transportProviderId = ProviderId("transport-quarantine"),
    )

    private fun pullRequest() = SynchronizationRequest(
        workflowId = WorkflowId("selection-workflow"),
        sessionId = SynchronizationSessionId("quarantine-session"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("quarantine-execution"),
            correlationId = CorrelationId("quarantine-correlation"),
        ),
    )

    /**
     * Reports a conflict with a fresh ID per detection, as a detector that mints IDs
     * does. (A repeated ID with a changed decision would hit the commit-once decision
     * log's non-convergence guard, which is unrelated to quarantine.)
     */
    private class ConflictOnEveryPairDetector(override val id: ConflictDetectorId) : ConflictDetector {
        private var detections = 0

        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(
                SynchronizationConflict(
                    id = ConflictId("conflict-${request.localChange.entity.id.value}-${++detections}"),
                    type = ConflictType.CONCURRENT_CHANGE,
                    entity = request.localChange.entity,
                    localChange = request.localChange,
                    remoteChange = request.remoteChange,
                ),
            )
    }

    class InMemoryStore<S : Any, R : Any> : DurableStateStore<S, R> {
        private val records = mutableMapOf<S, DurableStateRecord<R>>()

        fun state(scope: S): R? = records[scope]?.state

        fun isEmpty(): Boolean = records.isEmpty()

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

    private object UnusedCommandStore : ConflictAdministrationStateStore {
        override suspend fun load(
            commandId: ConflictAdministrationCommandId,
        ): ProviderOperationResult<ConflictAdministrationLoadResult> = error("not used by release")

        override suspend fun compareAndSet(
            request: ConflictAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<ConflictAdministrationCompareAndSetResult> = error("not used by release")
    }

    private object UnusedExecutor : ConflictAdministrationExecutor {
        override suspend fun execute(
            command: io.dataloom.api.conflict.AuthorizedConflictAdministrationCommand,
        ): ConflictAdministrationExecutionResult = error("not used by release")
    }

    class RecordingStorageProvider(
        private val localCandidates: Map<String, ChangeEvent>,
    ) : StorageProvider {
        val appliedEvents = mutableListOf<ChangeEvent>()
        var checkpointWrites = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-quarantine"),
            name = ProviderName("Quarantine storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> {
            appliedEvents += request.changeSet.events
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> {
            checkpointWrites++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readLocalConflictCandidate(
            request: LocalConflictCandidateReadRequest,
        ): ProviderOperationResult<LocalConflictCandidateReadResult> {
            val local = localCandidates[request.entity.id.value]
            return ProviderOperationResult.Success(
                if (local == null) LocalConflictCandidateReadResult.NotFound else LocalConflictCandidateReadResult.Found(local),
            )
        }
    }

    private class FakeTransportProvider(
        private val pullResult: ProviderOperationResult<PullChangesResult>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-quarantine"),
            name = ProviderName("Quarantine transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<io.dataloom.api.synchronization.ChangeSetAcknowledgement> =
            error("push is not used by this pull-only test")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            pullResult
    }
}
