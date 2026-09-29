package io.dataloom.runtime.facade

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictReason
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
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
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
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
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
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import io.dataloom.api.strategy.StrategyTransportOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Proves conflict detection configured through [DataLoomBuilder.conflictDetectionConfiguration]
 * reaches a conflict that arises **during a built-in strategy's own inbound-pull
 * execution path**, not just the baseline (non-strategy) `synchronize(SynchronizationRequest)`
 * path [DataLoomBuilderConflictDetectionTest] already proves.
 *
 * ## Why this test exists
 *
 * `DataLoomBuilder.buildStrategyPipelineRegistry` threads the exact same
 * [io.dataloom.runtime.execution.inbound.InboundPullConflictDetectionConfiguration]
 * built from [DataLoomConflictDetectionSpec] into the strategy engine's own
 * canonical `InboundPullSynchronizationPipeline` -- the same registry backs
 * both [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
 * and [io.dataloom.runtime.strategy.AcceptedStrategyPlanExecutionCoordinator].
 * [DataLoomConflictDetectionSpec]'s own KDoc documents this ("affects every
 * registered inbound-pull pipeline ... uniformly"), but nothing exercised it
 * end to end through a real [DataLoomBuilder] with both a conflict-detection
 * spec and a strategy profile configured together until this test existed.
 *
 * Remote-first and cache-first are used because they are the two strategies
 * with the most other end-to-end proof (see
 * `docs/status/dl-039b-strategy-decision-matrix.md`), and both reach the
 * shared pipeline through a distinct branch of their own executor:
 * remote-first's `executeProviderBackedPipeline` (AVAILABLE connectivity,
 * default `persistRemoteResult = true`) and cache-first's MISSING-cache
 * remote-fetch branch (no `SERVE_LOCAL`, so the executor's own `cacheState`
 * stays `null` and the pipeline result maps straight to `Executed`, the same
 * shape remote-first produces).
 */
class DataLoomBuilderStrategyConflictDetectionTest {

    private val entity = EntityReference(type = EntityType("document"), id = EntityId("strategy-doc-001"))

    @Test
    fun remoteFirstProviderBackedPullConflictDuringInboundIsDetectedAndRecordedUnresolved() = runTest {
        val conflictDetectorId = ConflictDetectorId("remote-first-strategy-detector")
        val localChange = ChangeEvent(
            id = ChangeEventId("remote-first-local-change"),
            entity = entity,
            operation = ChangeOperation.UPDATE,
        )
        val storage = ConflictCapableStrategyStorageProvider(
            id = ProviderId("remote-first-strategy-storage"),
            localCandidate = localChange,
        )
        val transport = FakeStrategyTransportProvider(
            id = ProviderId("remote-first-strategy-transport"),
            pullResult = ProviderOperationResult.Success(inboundChanges("remote-first-remote-change")),
        )
        val detector = FakeConflictDetector(
            conflictDetectorId,
            conflictDetected(ConflictId("remote-first-conflict-1"), localChange, "remote-first-remote-change"),
        )
        val unresolvedStore = InMemoryUnresolvedConflictStore()
        val bindings = StrategyProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
        )

        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(bindings)
            .conflictDetectionConfiguration(
                DataLoomConflictDetectionSpec(
                    detectors = listOf(detector),
                    resolvers = emptyList(),
                    bindings = ConflictOrchestrationBindings(conflictDetectorId, resolverId = null),
                    unresolvedConflictStore = unresolvedStore,
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val request = StrategySynchronizationRequest(
            request = synchronizationRequest("remote-first-conflict"),
            decisionId = StrategyDecisionId("remote-first-conflict-decision"),
            planId = StrategyPlanId("remote-first-conflict-plan"),
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("remote-first-conflict-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
            ),
            evidence = StrategyRuntimeEvidence(connectivity = StrategyConnectivity.AVAILABLE),
            input = StrategyOperationInput.ProviderBacked,
        )

        val result = dataLoom.synchronize(request, bindings)

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val providerBacked = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(providerBacked.result)
        assertEquals(1, detector.invokeCount)
        assertEquals(1, succeeded.summary.conflictsDetected)
        assertEquals(1, storage.readLocalConflictCandidateCallCount)

        val recorded = DurableUnresolvedConflictLog(unresolvedStore).current(ConflictId("remote-first-conflict-1"))
        val record = assertIs<ProviderOperationResult.Success<UnresolvedConflictRecord?>>(recorded).value
        assertEquals(UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED, record?.reason)
    }

    @Test
    fun cacheFirstMissingCacheRemoteFetchConflictDuringInboundIsDetectedAndRecordedUnresolved() = runTest {
        val conflictDetectorId = ConflictDetectorId("cache-first-strategy-detector")
        val localChange = ChangeEvent(
            id = ChangeEventId("cache-first-local-change"),
            entity = entity,
            operation = ChangeOperation.UPDATE,
        )
        val storage = ConflictCapableStrategyStorageProvider(
            id = ProviderId("cache-first-strategy-storage"),
            localCandidate = localChange,
        )
        val transport = FakeStrategyTransportProvider(
            id = ProviderId("cache-first-strategy-transport"),
            pullResult = ProviderOperationResult.Success(inboundChanges("cache-first-remote-change")),
        )
        val detector = FakeConflictDetector(
            conflictDetectorId,
            conflictDetected(ConflictId("cache-first-conflict-1"), localChange, "cache-first-remote-change"),
        )
        val unresolvedStore = InMemoryUnresolvedConflictStore()
        val bindings = StrategyProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
        )

        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(bindings)
            .conflictDetectionConfiguration(
                DataLoomConflictDetectionSpec(
                    detectors = listOf(detector),
                    resolvers = emptyList(),
                    bindings = ConflictOrchestrationBindings(conflictDetectorId, resolverId = null),
                    unresolvedConflictStore = unresolvedStore,
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val request = StrategySynchronizationRequest(
            request = synchronizationRequest("cache-first-conflict"),
            decisionId = StrategyDecisionId("cache-first-conflict-decision"),
            planId = StrategyPlanId("cache-first-conflict-plan"),
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("cache-first-conflict-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        val result = dataLoom.synchronize(request, bindings)

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val providerBacked = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(providerBacked.result)
        assertEquals(1, detector.invokeCount)
        assertEquals(1, succeeded.summary.conflictsDetected)
        assertEquals(1, storage.readLocalConflictCandidateCallCount)

        val recorded = DurableUnresolvedConflictLog(unresolvedStore).current(ConflictId("cache-first-conflict-1"))
        val record = assertIs<ProviderOperationResult.Success<UnresolvedConflictRecord?>>(recorded).value
        assertEquals(UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED, record?.reason)
    }

    /**
     * Negative control mirroring
     * [DataLoomBuilderConflictDetectionTest.conflictDetectionConfiguration_whenNotConfigured_behaviorIsUnchanged]:
     * the exact same conflict-capable storage and strategy wiring, but with
     * no [DataLoomBuilder.conflictDetectionConfiguration] call, proves the
     * strategy facade does not silently run conflict detection on its own,
     * and does not silently skip it once configured either -- the only
     * difference from the positive test above is whether
     * `conflictDetectionConfiguration` was called.
     */
    @Test
    fun remoteFirstProviderBackedPullWithoutConflictDetectionConfigurationIsUnaffected() = runTest {
        val localChange = ChangeEvent(
            id = ChangeEventId("remote-first-local-change-unconfigured"),
            entity = entity,
            operation = ChangeOperation.UPDATE,
        )
        val storage = ConflictCapableStrategyStorageProvider(
            id = ProviderId("remote-first-strategy-storage-unconfigured"),
            localCandidate = localChange,
        )
        val transport = FakeStrategyTransportProvider(
            id = ProviderId("remote-first-strategy-transport-unconfigured"),
            pullResult = ProviderOperationResult.Success(inboundChanges("remote-first-remote-change-unconfigured")),
        )
        val bindings = StrategyProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
        )

        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(bindings)
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val request = StrategySynchronizationRequest(
            request = synchronizationRequest("remote-first-conflict-unconfigured"),
            decisionId = StrategyDecisionId("remote-first-conflict-unconfigured-decision"),
            planId = StrategyPlanId("remote-first-conflict-unconfigured-plan"),
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("remote-first-conflict-unconfigured-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
            ),
            evidence = StrategyRuntimeEvidence(connectivity = StrategyConnectivity.AVAILABLE),
            input = StrategyOperationInput.ProviderBacked,
        )

        val result = dataLoom.synchronize(request, bindings)

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val providerBacked = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(providerBacked.result)
        assertEquals(0, succeeded.summary.conflictsDetected)
        assertEquals(0, storage.readLocalConflictCandidateCallCount)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun inboundChanges(remoteChangeIdValue: String): PullChangesResult.Changes = PullChangesResult.Changes(
        changeSet = ChangeSet(
            id = ChangeSetId("$remoteChangeIdValue-set"),
            events = listOf(
                ChangeEvent(id = ChangeEventId(remoteChangeIdValue), entity = entity, operation = ChangeOperation.UPDATE),
            ),
        ),
        hasMore = false,
    )

    private fun conflictDetected(
        conflictId: ConflictId,
        localChange: ChangeEvent,
        remoteChangeIdValue: String,
    ): ConflictDetectionResult.ConflictDetected =
        ConflictDetectionResult.ConflictDetected(
            SynchronizationConflict(
                id = conflictId,
                type = ConflictType.CONCURRENT_CHANGE,
                entity = entity,
                localChange = localChange,
                remoteChange = ChangeEvent(
                    id = ChangeEventId(remoteChangeIdValue),
                    entity = entity,
                    operation = ChangeOperation.UPDATE,
                ),
            ),
        )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = object : DataLoomClock {
            override fun now() = DataLoomInstant(2_000_000L)
        },
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { io.dataloom.api.identifier.SynchronizationEventId("strategy-conflict-event") },
            queueEntryIds = generator { io.dataloom.api.identifier.QueueEntryId("strategy-conflict-queue") },
            queueLeaseIds = generator { io.dataloom.api.identifier.QueueLeaseId("strategy-conflict-lease") },
            conflictIds = generator { ConflictId("strategy-conflict-unused") },
        ),
    )

    private fun <T> generator(block: () -> T): io.dataloom.api.identifier.IdentifierGenerator<T> =
        object : io.dataloom.api.identifier.IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private fun synchronizationRequest(suffix: String): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("strategy-$suffix-workflow"),
        sessionId = SynchronizationSessionId("strategy-$suffix-session"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("strategy-$suffix-execution"),
            correlationId = CorrelationId("strategy-$suffix-correlation"),
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

    /** In-memory [DurableStateStore] fake, mirroring [DataLoomBuilderConflictDetectionTest]'s own. */
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

    private class ConflictCapableStrategyStorageProvider(
        id: ProviderId,
        private val localCandidate: ChangeEvent,
    ) : StorageProvider {
        var readLocalConflictCandidateCallCount: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = id,
            name = ProviderName("Conflict-capable strategy storage"),
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

    private class FakeStrategyTransportProvider(
        id: ProviderId,
        private val pullResult: ProviderOperationResult<PullChangesResult>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = id,
            name = ProviderName("Strategy conflict test transport"),
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
