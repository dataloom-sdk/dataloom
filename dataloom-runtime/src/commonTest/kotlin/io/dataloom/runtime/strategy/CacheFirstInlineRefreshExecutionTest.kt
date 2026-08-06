package io.dataloom.runtime.strategy

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
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
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.strategy.AdaptiveStrategyProfile
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StaleCachePolicy
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.SynchronizationStrategyProfile
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.inbound.InboundPullPipelineConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CacheFirstInlineRefreshExecutionTest {

    @Test
    fun cacheIsVerifiedBeforeNoChangeRefresh() = runTest {
        val calls = mutableListOf<String>()
        val storage = TestStorage(calls = calls, cacheResult = available(freshEvidence()))
        val transport = TestTransport(
            calls = calls,
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        val refresh = assertIs<StrategyCacheInlineRefreshResult.Completed>(served.refresh)
        assertEquals(StrategyDataOrigin.LOCAL, served.dataOrigin)
        assertEquals(StrategyCacheState.FRESH, served.freshness.cacheState)
        assertEquals(StrategyDisposition.SERVE_AND_REFRESH, served.evaluation.plan.disposition)
        assertEquals(
            listOf(
                StrategyOperation.SERVE_LOCAL,
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            served.evaluation.plan.operations,
        )
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), refresh.completedOperations)
        assertEquals(listOf("cache", "checkpoint", "pull"), calls)
    }

    @Test
    fun staleHitAndCacheBoundaryFailuresRemainTruthful() = runTest {
        val staleStorage = TestStorage(cacheResult = available(staleEvidence()))
        val staleTransport = noChangeTransport()
        val staleFixture = fixture(staleStorage, staleTransport)

        val staleResult = staleFixture.coordinator.execute(
            request = request(
                profile = cacheProfile(
                    staleCachePolicy = StaleCachePolicy.SERVE_STALE_AND_REFRESH,
                ),
                cacheState = StrategyCacheState.STALE,
            ),
            bindings = staleFixture.bindings,
        )
        val staleServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(staleResult)
        assertEquals(StrategyCacheState.STALE, staleServed.freshness.cacheState)
        assertTrue(staleStorage.lastCacheRequest?.allowStale == true)

        suspend fun executeCacheBoundary(
            cacheResult: ProviderOperationResult<StrategyCacheAccessResult>,
        ): Pair<StrategySynchronizationExecutionResult, TestTransport> {
            val storage = TestStorage(cacheResult = cacheResult)
            val transport = noChangeTransport()
            val current = fixture(storage, transport)
            return current.coordinator.execute(request(), current.bindings) to transport
        }

        val (unavailableResult, unavailableTransport) = executeCacheBoundary(
            success(StrategyCacheAccessResult.Unavailable(StrategyCacheState.MISSING)),
        )
        val unavailable = assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(
            unavailableResult,
        )
        assertEquals(
            StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE,
            unavailable.reason,
        )
        assertEquals(0, unavailableTransport.pullCalls)

        val (driftResult, driftTransport) = executeCacheBoundary(available(staleEvidence()))
        val drift = assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(driftResult)
        assertEquals(StrategyCacheUnavailableReason.FRESHNESS_DOWNGRADED, drift.reason)
        assertEquals(0, driftTransport.pullCalls)

        val cacheError = Failure(
            code = ErrorCode("INLINE_CACHE_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val (failedResult, failedTransport) = executeCacheBoundary(
            ProviderOperationResult.Failure(cacheError),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(failedResult)
        assertSame(cacheError, failed.error)
        assertEquals(false, failed.transportAttempted)
        assertEquals(0, failedTransport.pullCalls)
    }

    @Test
    fun checkpointAndRemoteFailuresPreserveLocalCache() = runTest {
        val checkpointError = Failure(
            code = ErrorCode("INLINE_CHECKPOINT_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val checkpointStorage = TestStorage(
            cacheResult = available(freshEvidence()),
            readCheckpointResult = ProviderOperationResult.Failure(checkpointError),
        )
        val checkpointTransport = noChangeTransport()
        val checkpointFixture = fixture(checkpointStorage, checkpointTransport)

        val checkpointResult = checkpointFixture.coordinator.execute(
            request(),
            checkpointFixture.bindings,
        )
        val checkpointServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(
            checkpointResult,
        )
        val checkpointRefresh = assertIs<StrategyCacheInlineRefreshResult.Failed>(
            checkpointServed.refresh,
        )
        assertSame(checkpointError, checkpointRefresh.error)
        assertEquals(false, checkpointRefresh.transportAttempted)
        assertTrue(checkpointRefresh.completedOperations.isEmpty())
        assertEquals(0, checkpointTransport.pullCalls)

        val remoteError = RemoteFailure()
        val remoteStorage = TestStorage(cacheResult = available(freshEvidence()))
        val remoteTransport = TestTransport(
            pullResults = mutableListOf(ProviderOperationResult.Failure(remoteError)),
        )
        val remoteFixture = fixture(remoteStorage, remoteTransport)

        val remoteResult = remoteFixture.coordinator.execute(request(), remoteFixture.bindings)
        val remoteServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(remoteResult)
        val remoteRefresh = assertIs<StrategyCacheInlineRefreshResult.Failed>(
            remoteServed.refresh,
        )
        assertSame(remoteError, remoteRefresh.error)
        assertEquals(true, remoteRefresh.transportAttempted)
        assertTrue(remoteRefresh.completedOperations.isEmpty())
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, remoteRefresh.remoteOutcome)
    }

    @Test
    fun persistenceFailureAndPagedSuccessPreservePullEvidence() = runTest {
        val writeError = Failure(
            code = ErrorCode("INLINE_WRITE_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val failingStorage = TestStorage(
            cacheResult = available(freshEvidence()),
            writeResults = mutableListOf(ProviderOperationResult.Failure(writeError)),
        )
        val failingTransport = TestTransport(
            pullResults = mutableListOf(
                success(
                    PullChangesResult.Changes(
                        changeSet = changeSet("set-fail", "event-fail"),
                        hasMore = false,
                        nextCheckpoint = checkpoint("write-fail"),
                    ),
                ),
            ),
        )
        val failingFixture = fixture(failingStorage, failingTransport)
        val failingResult = failingFixture.coordinator.execute(
            request(),
            failingFixture.bindings,
        )
        val failingServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(
            failingResult,
        )
        val failingRefresh = assertIs<StrategyCacheInlineRefreshResult.Failed>(
            failingServed.refresh,
        )
        assertSame(writeError, failingRefresh.error)
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), failingRefresh.completedOperations)
        assertNull(failingRefresh.remoteOutcome)

        val pagedStorage = TestStorage(
            cacheResult = available(freshEvidence()),
            writeResults = mutableListOf(success(Unit)),
        )
        val pagedTransport = TestTransport(
            pullResults = mutableListOf(
                success(
                    PullChangesResult.Changes(
                        changeSet = changeSet("set-1", "event-1"),
                        hasMore = true,
                        nextCheckpoint = checkpoint("page-1"),
                    ),
                ),
                success(
                    PullChangesResult.Changes(
                        changeSet = changeSet("set-2", "event-2"),
                        hasMore = false,
                    ),
                ),
            ),
        )
        val pagedFixture = fixture(pagedStorage, pagedTransport)
        val pagedResult = pagedFixture.coordinator.execute(request(), pagedFixture.bindings)
        val pagedServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(pagedResult)
        val pagedRefresh = assertIs<StrategyCacheInlineRefreshResult.Completed>(
            pagedServed.refresh,
        )
        assertEquals(
            listOf(
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PULL_REMOTE,
            ),
            pagedRefresh.completedOperations,
        )
    }

    @Test
    fun partialAndCancelledRefreshesRemainSeparateFromLocalCache() = runTest {
        val partialStorage = TestStorage(
            cacheResult = available(freshEvidence()),
            writeResults = mutableListOf(success(Unit)),
        )
        val partialTransport = TestTransport(
            pullResults = mutableListOf(
                success(
                    PullChangesResult.Changes(
                        changeSet = changeSet("partial-set", "partial-event"),
                        hasMore = true,
                        nextCheckpoint = checkpoint("partial"),
                    ),
                ),
            ),
        )
        val partialFixture = fixture(
            storage = partialStorage,
            transport = partialTransport,
            pipeline = InboundPullSynchronizationPipeline(
                InboundPullPipelineConfiguration(maxBatchesPerExecution = 1),
            ),
        )
        val partialResult = partialFixture.coordinator.execute(
            request(),
            partialFixture.bindings,
        )
        val partialServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(
            partialResult,
        )
        val partialRefresh = assertIs<StrategyCacheInlineRefreshResult.PartiallySucceeded>(
            partialServed.refresh,
        )
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), partialRefresh.completedOperations)

        val cancelledStorage = TestStorage(cacheResult = available(freshEvidence()))
        val cancelledTransport = noChangeTransport()
        val cancelledFixture = fixture(
            storage = cancelledStorage,
            transport = cancelledTransport,
            pipeline = ExplicitCancelledPullPipeline,
        )
        val cancelledResult = cancelledFixture.coordinator.execute(
            request(),
            cancelledFixture.bindings,
        )
        val cancelledServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(
            cancelledResult,
        )
        val cancelledRefresh = assertIs<StrategyCacheInlineRefreshResult.Cancelled>(
            cancelledServed.refresh,
        )
        assertEquals(true, cancelledRefresh.transportAttempted)
        assertEquals(
            listOf(StrategyOperation.PULL_REMOTE),
            cancelledRefresh.completedOperations,
        )
    }

    @Test
    fun adaptiveWorksWhileDurableInputAndBidirectionalValidationRemainExplicit() = runTest {
        val storage = TestStorage(cacheResult = available(freshEvidence()))
        val transport = TestTransport(
            pullResults = mutableListOf(
                success(PullChangesResult.NoChanges()),
                success(PullChangesResult.NoChanges()),
            ),
        )
        val fixture = fixture(storage, transport)
        val concreteProfile = cacheProfile()
        val adaptiveProfile = AdaptiveStrategyProfile(
            id = StrategyProfileId("inline-adaptive"),
            configurationVersion = StrategyConfigurationVersion(3),
            candidates = listOf(concreteProfile),
        )

        val adaptiveResult = fixture.coordinator.execute(
            request = request(profile = adaptiveProfile),
            bindings = fixture.bindings,
        )
        val adaptiveServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(
            adaptiveResult,
        )
        assertEquals(
            BuiltInSynchronizationStrategy.ADAPTIVE,
            adaptiveServed.evaluation.plan.requestedStrategy,
        )
        assertEquals(
            BuiltInSynchronizationStrategy.CACHE_FIRST,
            adaptiveServed.evaluation.plan.effectiveStrategy,
        )

        val callsAfterAdaptive = storage.cacheCalls to transport.pullCalls
        val durableResult = fixture.coordinator.execute(
            request = request(profile = cacheProfile(requireDurableRefresh = true)),
            bindings = fixture.bindings,
        )
        val bidirectionalResult = fixture.coordinator.execute(
            request = request(direction = SynchronizationDirection.BIDIRECTIONAL),
            bindings = fixture.bindings,
        )
        assertEquals(
            StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(durableResult).reason,
        )
        assertEquals(
            StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(bidirectionalResult).reason,
        )
        assertEquals(callsAfterAdaptive, storage.cacheCalls to transport.pullCalls)
    }

    private suspend fun fixture(
        storage: TestStorage,
        transport: TestTransport,
        pipeline: SynchronizationPipeline =
            InboundPullSynchronizationPipeline(InboundPullPipelineConfiguration()),
    ): Fixture {
        val registry = ProviderRegistry(listOf(storage, transport))
        val lifecycle = ProviderLifecycleCoordinator(
            registry = registry,
            context = ProviderInitializationContext(),
        )
        lifecycle.initialize()
        val runtimeDependencies = dependencies()
        return Fixture(
            coordinator = StrategySynchronizationExecutionCoordinator(
                lifecycleCoordinator = lifecycle,
                evaluator = BuiltInSynchronizationStrategyEvaluator(),
                providerResolver = StrategyProviderResolver(registry),
                clock = runtimeDependencies.clock,
                runtimeDependencies = runtimeDependencies,
                pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
                lifecycleEventEmitter = null,
            ),
            bindings = StrategyProviderBindings(
                storageProviderId = storage.descriptor.id,
                transportProviderId = transport.descriptor.id,
            ),
        )
    }

    private fun request(
        profile: SynchronizationStrategyProfile = cacheProfile(),
        cacheState: StrategyCacheState = StrategyCacheState.FRESH,
        direction: SynchronizationDirection = SynchronizationDirection.PULL,
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("inline-workflow"),
            sessionId = SynchronizationSessionId("inline-session"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("inline-execution"),
                correlationId = CorrelationId("inline-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("inline-decision"),
        planId = StrategyPlanId("inline-plan"),
        profile = profile,
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            cacheState = cacheState,
            storageHealth = StrategyProviderHealth.HEALTHY,
            transportHealth = StrategyProviderHealth.HEALTHY,
        ),
    )

    private fun cacheProfile(
        staleCachePolicy: StaleCachePolicy = StaleCachePolicy.REJECT,
        requireDurableRefresh: Boolean = false,
    ): CacheFirstStrategyProfile = CacheFirstStrategyProfile(
        id = StrategyProfileId("inline-profile"),
        configurationVersion = StrategyConfigurationVersion(1),
        staleCachePolicy = staleCachePolicy,
        refreshOnFreshHit = true,
        requireDurableRefresh = requireDurableRefresh,
    )

    private class TestStorage(
        private val calls: MutableList<String> = mutableListOf(),
        private val cacheResult: ProviderOperationResult<StrategyCacheAccessResult>,
        private val readCheckpointResult: ProviderOperationResult<SynchronizationCheckpoint?> =
            success(null),
        private val writeResults: MutableList<ProviderOperationResult<Unit>> = mutableListOf(),
    ) : StrategyCacheAccessProvider {
        override val descriptor = descriptor("inline-storage", ProviderType.STORAGE)
        var cacheCalls: Int = 0
            private set
        var lastCacheRequest: StrategyCacheAccessRequest? = null
            private set

        override suspend fun initialize(context: ProviderInitializationContext) = success(Unit)
        override suspend fun health() = success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        override suspend fun close() = success(Unit)

        override suspend fun evaluateCacheAccess(
            request: StrategyCacheAccessRequest,
        ): ProviderOperationResult<StrategyCacheAccessResult> {
            cacheCalls++
            lastCacheRequest = request
            calls += "cache"
            return cacheResult
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ) = success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ) = success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ) = success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            calls += "checkpoint"
            return readCheckpointResult
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            calls += "write"
            return if (writeResults.isEmpty()) success(Unit) else writeResults.removeAt(0)
        }
    }

    private class TestTransport(
        private val calls: MutableList<String> = mutableListOf(),
        private val pullResults: MutableList<ProviderOperationResult<PullChangesResult>>,
    ) : TransportProvider {
        override val descriptor = descriptor("inline-transport", ProviderType.TRANSPORT)
        var pullCalls: Int = 0
            private set

        override suspend fun initialize(context: ProviderInitializationContext) = success(Unit)
        override suspend fun health() = success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        override suspend fun close() = success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            error("Inline PULL refresh must not push.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            calls += "pull"
            check(pullResults.isNotEmpty()) { "Missing scripted pull result." }
            return pullResults.removeAt(0)
        }
    }

    private object ExplicitCancelledPullPipeline : SynchronizationPipeline {
        override val direction: SynchronizationDirection = SynchronizationDirection.PULL

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            context.providers.transportProvider.pullChanges(
                PullChangesRequest(request = context.request),
            )
            return SynchronizationResult.Cancelled(
                request = context.request,
                completedAt = context.runtimeDependencies.clock.now(),
                summary = SynchronizationSummary(),
            )
        }
    }

    private data class Fixture(
        val coordinator: StrategySynchronizationExecutionCoordinator,
        val bindings: StrategyProviderBindings,
    )

    private data class Failure(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Inline refresh failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class RemoteFailure(
        override val remoteOutcome: StrategyRemoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
        override val code: ErrorCode = ErrorCode("INLINE_REMOTE_UNAVAILABLE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Inline remote unavailable.",
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private companion object {
        fun freshEvidence() = StrategyCacheFreshnessEvidence(
            cacheState = StrategyCacheState.FRESH,
            observedAt = DataLoomInstant(10_000L),
            validUntil = DataLoomInstant(20_000L),
        )

        fun staleEvidence() = StrategyCacheFreshnessEvidence(
            cacheState = StrategyCacheState.STALE,
            observedAt = DataLoomInstant(20_000L),
            validUntil = DataLoomInstant(10_000L),
        )

        fun available(
            evidence: StrategyCacheFreshnessEvidence,
        ): ProviderOperationResult<StrategyCacheAccessResult> =
            success(StrategyCacheAccessResult.Available(evidence))

        fun checkpoint(token: String) = SynchronizationCheckpoint(
            key = CheckpointKey("inline-workflow"),
            token = CheckpointToken(token),
        )

        fun changeSet(id: String, eventId: String) = ChangeSet(
            id = ChangeSetId(id),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId(eventId),
                    entity = EntityReference(
                        type = EntityType("Order"),
                        id = EntityId("entity-$eventId"),
                    ),
                    operation = ChangeOperation.UPDATE,
                ),
            ),
        )

        fun dependencies() = RuntimeDependencies(
            clock = FixedClock(DataLoomInstant(30_000L)),
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds = fixed(SynchronizationEventId("inline-event")),
                queueEntryIds = fixed(QueueEntryId("inline-entry")),
                queueLeaseIds = fixed(QueueLeaseId("inline-lease")),
                conflictIds = fixed(ConflictId("inline-conflict")),
            ),
        )

        fun descriptor(id: String, type: ProviderType) = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName(id),
            type = type,
            version = ProviderVersion("1.0.0"),
        )

        fun noChangeTransport() = TestTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )

        fun <T> fixed(value: T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = value
            }

        fun <T> success(value: T): ProviderOperationResult<T> =
            ProviderOperationResult.Success(value)
    }
}
