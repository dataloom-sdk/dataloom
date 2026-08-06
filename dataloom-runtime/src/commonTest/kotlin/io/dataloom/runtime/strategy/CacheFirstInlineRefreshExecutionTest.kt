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
    fun freshCacheIsVerifiedBeforeCanonicalNoChangeRefresh() = runTest {
        val calls = mutableListOf<String>()
        val storage = TestStorage(calls, available(freshEvidence()))
        val transport = TestTransport(
            calls,
            mutableListOf(success(PullChangesResult.NoChanges())),
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
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            served.evaluation.plan.operations,
        )
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), refresh.completedOperations)
        assertEquals(listOf("cache", "checkpoint", "pull"), calls)
    }

    @Test
    fun allowedStaleCacheIsPreservedWhileRefreshRuns() = runTest {
        val storage = TestStorage(cacheResult = available(staleEvidence()))
        val transport = TestTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request(
                cacheState = StrategyCacheState.STALE,
                profile = cacheProfile(
                    staleCachePolicy = StaleCachePolicy.SERVE_STALE_AND_REFRESH,
                ),
            ),
            fixture.bindings,
        )

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        assertEquals(StrategyCacheState.STALE, served.evaluatedCacheState)
        assertEquals(StrategyCacheState.STALE, served.freshness.cacheState)
        assertTrue(storage.lastCacheRequest?.allowStale == true)
        assertIs<StrategyCacheInlineRefreshResult.Completed>(served.refresh)
    }

    @Test
    fun unavailableDriftAndProviderFailureStopBeforeTransport() = runTest {
        suspend fun execute(
            cacheResult: ProviderOperationResult<StrategyCacheAccessResult>,
        ): Pair<StrategySynchronizationExecutionResult, TestTransport> {
            val storage = TestStorage(cacheResult = cacheResult)
            val transport = TestTransport(
                pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
            )
            val fixture = fixture(storage, transport)
            return fixture.coordinator.execute(request(), fixture.bindings) to transport
        }

        val (unavailableResult, unavailableTransport) = execute(
            success(StrategyCacheAccessResult.Unavailable(StrategyCacheState.MISSING)),
        )
        val unavailable =
            assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(unavailableResult)
        assertEquals(
            StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE,
            unavailable.reason,
        )
        assertEquals(0, unavailableTransport.pullCalls)

        val (driftResult, driftTransport) = execute(available(staleEvidence()))
        val drift = assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(driftResult)
        assertEquals(StrategyCacheUnavailableReason.FRESHNESS_DOWNGRADED, drift.reason)
        assertEquals(0, driftTransport.pullCalls)

        val cacheFailure = Failure(ErrorCode("INLINE_CACHE_FAILED"), ErrorCategory.STORAGE)
        val (failedResult, failedTransport) = execute(
            ProviderOperationResult.Failure(cacheFailure),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(failedResult)
        assertSame(cacheFailure, failed.error)
        assertEquals(false, failed.transportAttempted)
        assertEquals(0, failedTransport.pullCalls)
    }

    @Test
    fun refreshFailureKeepsLocalCacheAndTruthfulOperationEvidence() = runTest {
        val checkpointFailure = Failure(
            ErrorCode("INLINE_CHECKPOINT_FAILED"),
            ErrorCategory.STORAGE,
        )
        val beforeTransportStorage = TestStorage(
            cacheResult = available(freshEvidence()),
            readCheckpointResult = ProviderOperationResult.Failure(checkpointFailure),
        )
        val beforeTransport = TestTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val beforeFixture = fixture(beforeTransportStorage, beforeTransport)

        val beforeResult = beforeFixture.coordinator.execute(request(), beforeFixture.bindings)
        val beforeServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(beforeResult)
        val beforeRefresh = assertIs<StrategyCacheInlineRefreshResult.Failed>(
            beforeServed.refresh,
        )
        assertSame(checkpointFailure, beforeRefresh.error)
        assertEquals(false, beforeRefresh.transportAttempted)
        assertTrue(beforeRefresh.completedOperations.isEmpty())
        assertNull(beforeRefresh.remoteOutcome)
        assertEquals(0, beforeTransport.pullCalls)

        val remoteFailure = RemoteFailure()
        val remoteStorage = TestStorage(cacheResult = available(freshEvidence()))
        val remoteTransport = TestTransport(
            pullResults = mutableListOf(ProviderOperationResult.Failure(remoteFailure)),
        )
        val remoteFixture = fixture(remoteStorage, remoteTransport)

        val remoteResult = remoteFixture.coordinator.execute(request(), remoteFixture.bindings)
        val remoteServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(remoteResult)
        val remoteRefresh = assertIs<StrategyCacheInlineRefreshResult.Failed>(
            remoteServed.refresh,
        )
        assertSame(remoteFailure, remoteRefresh.error)
        assertEquals(true, remoteRefresh.transportAttempted)
        assertTrue(remoteRefresh.completedOperations.isEmpty())
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, remoteRefresh.remoteOutcome)
    }

    @Test
    fun persistenceFailureAndPagedSuccessPreserveCompletedPulls() = runTest {
        val writeFailure = Failure(
            ErrorCode("INLINE_WRITE_FAILED"),
            ErrorCategory.STORAGE,
        )
        val failingStorage = TestStorage(
            cacheResult = available(freshEvidence()),
            writeResults = mutableListOf(ProviderOperationResult.Failure(writeFailure)),
        )
        val failingTransport = TestTransport(
            pullResults = mutableListOf(
                success(
                    PullChangesResult.Changes(
                        changeSet("set-fail", "event-fail"),
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
        assertSame(writeFailure, failingRefresh.error)
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
                        changeSet("set-1", "event-1"),
                        hasMore = true,
                        nextCheckpoint = checkpoint("page-1"),
                    ),
                ),
                success(
                    PullChangesResult.Changes(
                        changeSet("set-2", "event-2"),
                        hasMore = false,
                    ),
                ),
            ),
        )
        val pagedFixture = fixture(pagedStorage, pagedTransport)

        val pagedResult = pagedFixture.coordinator.execute(request(), pagedFixture.bindings)
        val pagedServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(pagedResult)
        val completed = assertIs<StrategyCacheInlineRefreshResult.Completed>(
            pagedServed.refresh,
        )
        assertEquals(
            listOf(
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PULL_REMOTE,
            ),
            completed.completedOperations,
        )
    }

    @Test
    fun batchLimitAndExplicitCancellationRemainSeparateFromLocalCache() = runTest {
        val partialStorage = TestStorage(
            cacheResult = available(freshEvidence()),
            writeResults = mutableListOf(success(Unit)),
        )
        val partialTransport = TestTransport(
            pullResults = mutableListOf(
                success(
                    PullChangesResult.Changes(
                        changeSet("partial-set", "partial-event"),
                        hasMore = true,
                        nextCheckpoint = checkpoint("partial"),
                    ),
                ),
            ),
        )
        val partialFixture = fixture(
            partialStorage,
            partialTransport,
            InboundPullSynchronizationPipeline(
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
        val partial = assertIs<StrategyCacheInlineRefreshResult.PartiallySucceeded>(
            partialServed.refresh,
        )
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), partial.completedOperations)

        val cancelledStorage = TestStorage(cacheResult = available(freshEvidence()))
        val cancelledTransport = TestTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val cancelledFixture = fixture(
            cancelledStorage,
            cancelledTransport,
            ExplicitCancelledPullPipeline,
        )

        val cancelledResult = cancelledFixture.coordinator.execute(
            request(),
            cancelledFixture.bindings,
        )
        val cancelledServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(
            cancelledResult,
        )
        val cancelled = assertIs<StrategyCacheInlineRefreshResult.Cancelled>(
            cancelledServed.refresh,
        )
        assertEquals(true, cancelled.transportAttempted)
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), cancelled.completedOperations)
    }

    @Test
    fun adaptiveWorksWhileDurableAndBidirectionalRefreshStayFailClosed() = runTest {
        val storage = TestStorage(cacheResult = available(freshEvidence()))
        val transport = TestTransport(
            pullResults = mutableListOf(
                success(PullChangesResult.NoChanges()),
                success(PullChangesResult.NoChanges()),
            ),
        )
        val fixture = fixture(storage, transport)
        val concrete = cacheProfile()
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("inline-adaptive"),
            configurationVersion = StrategyConfigurationVersion(3),
            candidates = listOf(concrete),
        )

        val adaptiveResult = fixture.coordinator.execute(
            request(profile = adaptive),
            fixture.bindings,
        )
        val adaptiveServed = assertIs<StrategyCacheServedWithInlineRefreshResult>(
            adaptiveResult,
        )
        assertEquals(
            BuiltInSynchronizationStrategy.ADAPTIVE,
            adaptiveServed.evaluation.plan.requestedStrategy,
        )
        assertEquals(concrete.id, adaptiveServed.evaluation.plan.effectiveProfileId)

        val callsAfterAdaptive = storage.cacheCalls to transport.pullCalls
        val durable = fixture.coordinator.execute(
            request(profile = cacheProfile(requireDurableRefresh = true)),
            fixture.bindings,
        )
        val bidirectional = fixture.coordinator.execute(
            request(direction = SynchronizationDirection.BIDIRECTIONAL),
            fixture.bindings,
        )
        assertEquals(
            StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(durable).reason,
        )
        assertEquals(
            StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(bidirectional).reason,
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
            registry,
            ProviderInitializationContext(),
        )
        lifecycle.initialize()
        val dependencies = dependencies()
        return Fixture(
            coordinator = StrategySynchronizationExecutionCoordinator(
                lifecycle,
                BuiltInSynchronizationStrategyEvaluator(),
                StrategyProviderResolver(registry),
                dependencies.clock,
                dependencies,
                SynchronizationPipelineRegistry(listOf(pipeline)),
                null,
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
            WorkflowId("inline-workflow"),
            SynchronizationSessionId("inline-session"),
            direction,
            SynchronizationMode.DELTA,
            ExecutionContext(
                ExecutionId("inline-execution"),
                CorrelationId("inline-correlation"),
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
        var cacheCalls = 0
        var lastCacheRequest: StrategyCacheAccessRequest? = null
        var applyCalls = 0
        var writeCalls = 0

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

        override suspend fun readOutboundChanges(request: OutboundChangeReadRequest) =
            success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest):
            ProviderOperationResult<Unit> {
            applyCalls++
            calls += "apply"
            return success(Unit)
        }

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
            writeCalls++
            calls += "write"
            return if (writeResults.isEmpty()) success(Unit) else writeResults.removeAt(0)
        }
    }

    private class TestTransport(
        private val calls: MutableList<String> = mutableListOf(),
        private val pullResults: MutableList<ProviderOperationResult<PullChangesResult>>,
    ) : TransportProvider {
        override val descriptor = descriptor("inline-transport", ProviderType.TRANSPORT)
        var pullCalls = 0

        override suspend fun initialize(context: ProviderInitializationContext) = success(Unit)
        override suspend fun health() = success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        override suspend fun close() = success(Unit)
        override suspend fun pushChanges(request: PushChangesRequest):
            ProviderOperationResult<ChangeSetAcknowledgement> =
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
        override val direction = SynchronizationDirection.PULL

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            context.providers.transportProvider.pullChanges(
                PullChangesRequest(request = context.request),
            )
            return SynchronizationResult.Cancelled(
                context.request,
                context.runtimeDependencies.clock.now(),
                SynchronizationSummary(),
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
        override fun now() = instant
    }

    private companion object {
        fun freshEvidence() = StrategyCacheFreshnessEvidence(
            StrategyCacheState.FRESH,
            DataLoomInstant(10_000L),
            DataLoomInstant(20_000L),
        )

        fun staleEvidence() = StrategyCacheFreshnessEvidence(
            StrategyCacheState.STALE,
            DataLoomInstant(20_000L),
            DataLoomInstant(10_000L),
        )

        fun available(evidence: StrategyCacheFreshnessEvidence) =
            success<StrategyCacheAccessResult>(StrategyCacheAccessResult.Available(evidence))

        fun checkpoint(token: String) = SynchronizationCheckpoint(
            CheckpointKey("inline-workflow"),
            CheckpointToken(token),
        )

        fun changeSet(id: String, eventId: String) = ChangeSet(
            ChangeSetId(id),
            listOf(
                ChangeEvent(
                    ChangeEventId(eventId),
                    EntityReference(EntityType("Order"), EntityId("entity-$eventId")),
                    ChangeOperation.UPDATE,
                ),
            ),
        )

        fun dependencies() = RuntimeDependencies(
            FixedClock(DataLoomInstant(30_000L)),
            RuntimeIdentifierGenerators(
                fixed(SynchronizationEventId("inline-event")),
                fixed(QueueEntryId("inline-entry")),
                fixed(QueueLeaseId("inline-lease")),
                fixed(ConflictId("inline-conflict")),
            ),
        )

        fun descriptor(id: String, type: ProviderType) = ProviderDescriptor(
            ProviderId(id),
            ProviderName(id),
            type,
            ProviderVersion("1.0.0"),
        )

        fun <T> fixed(value: T): IdentifierGenerator<T> = object : IdentifierGenerator<T> {
            override fun generate() = value
        }

        fun <T> success(value: T): ProviderOperationResult<T> =
            ProviderOperationResult.Success(value)
    }
}
