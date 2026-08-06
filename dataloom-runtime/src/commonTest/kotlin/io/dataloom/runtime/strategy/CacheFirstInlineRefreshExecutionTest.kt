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
        val storage = RecordingCacheStorage(
            calls = calls,
            cacheAccessResult = available(freshEvidence()),
        )
        val transport = RecordingTransport(
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
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            served.evaluation.plan.operations,
        )
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), refresh.completedOperations)
        assertEquals(listOf("cacheAccess", "readCheckpoint", "pull"), calls)
        assertEquals(0, storage.outboundCalls)
        assertEquals(0, transport.pushCalls)
    }

    @Test
    fun policyAllowedStaleCacheRemainsVisibleDuringRefresh() = runTest {
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(staleEvidence()),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(
                cacheState = StrategyCacheState.STALE,
                profile = cacheProfile(
                    staleCachePolicy = StaleCachePolicy.SERVE_STALE_AND_REFRESH,
                ),
            ),
            bindings = fixture.bindings,
        )

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        assertEquals(StrategyCacheState.STALE, served.evaluatedCacheState)
        assertEquals(StrategyCacheState.STALE, served.freshness.cacheState)
        assertTrue(storage.lastCacheRequest?.allowStale == true)
        assertIs<StrategyCacheInlineRefreshResult.Completed>(served.refresh)
    }

    @Test
    fun providerUnavailableCacheStopsBeforeRemoteRefresh() = runTest {
        val storage = RecordingCacheStorage(
            cacheAccessResult = success(
                StrategyCacheAccessResult.Unavailable(StrategyCacheState.MISSING),
            ),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val unavailable = assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(result)
        assertEquals(
            StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE,
            unavailable.reason,
        )
        assertEquals(0, storage.readCheckpointCalls)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun freshToStaleDriftStopsBeforeRemoteRefresh() = runTest {
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(staleEvidence()),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val unavailable = assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(result)
        assertEquals(StrategyCacheUnavailableReason.FRESHNESS_DOWNGRADED, unavailable.reason)
        assertEquals(0, storage.readCheckpointCalls)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun cacheProviderFailureStopsBeforeTransport() = runTest {
        val failure = InlineRefreshFailure(
            code = ErrorCode("INLINE_CACHE_ACCESS_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val storage = RecordingCacheStorage(
            cacheAccessResult = ProviderOperationResult.Failure(failure),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(failure, failed.error)
        assertEquals(false, failed.transportAttempted)
        assertEquals(0, storage.readCheckpointCalls)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun checkpointFailurePreservesServedCacheWithoutTransportAttempt() = runTest {
        val failure = InlineRefreshFailure(
            code = ErrorCode("INLINE_REFRESH_CHECKPOINT_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(freshEvidence()),
            readCheckpointResult = ProviderOperationResult.Failure(failure),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        val refresh = assertIs<StrategyCacheInlineRefreshResult.Failed>(served.refresh)
        assertSame(failure, refresh.error)
        assertEquals(false, refresh.transportAttempted)
        assertTrue(refresh.completedOperations.isEmpty())
        assertNull(refresh.remoteOutcome)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun remoteFailurePreservesServedCacheAndTypedRemoteOutcome() = runTest {
        val failure = InlineRefreshRemoteFailure()
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(freshEvidence()),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(ProviderOperationResult.Failure(failure)),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        val refresh = assertIs<StrategyCacheInlineRefreshResult.Failed>(served.refresh)
        assertSame(failure, refresh.error)
        assertEquals(true, refresh.transportAttempted)
        assertTrue(refresh.completedOperations.isEmpty())
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, refresh.remoteOutcome)
        assertEquals(StrategyCacheState.FRESH, served.freshness.cacheState)
    }

    @Test
    fun persistenceFailurePreservesCompletedPullEvidence() = runTest {
        val failure = InlineRefreshFailure(
            code = ErrorCode("INLINE_REFRESH_WRITE_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val checkpoint = checkpoint("inline-refresh-next")
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(freshEvidence()),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Failure(failure)),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(
                success(
                    PullChangesResult.Changes(
                        changeSet = changeSet("inline-refresh-set-1", "event-1"),
                        hasMore = false,
                        nextCheckpoint = checkpoint,
                    ),
                ),
            ),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        val refresh = assertIs<StrategyCacheInlineRefreshResult.Failed>(served.refresh)
        assertSame(failure, refresh.error)
        assertEquals(true, refresh.transportAttempted)
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), refresh.completedOperations)
        assertNull(refresh.remoteOutcome)
        assertEquals(1, storage.applyCalls)
        assertEquals(1, storage.writeCheckpointCalls)
    }

    @Test
    fun multiBatchRefreshPreservesEveryCompletedPull() = runTest {
        val checkpoint = checkpoint("inline-refresh-page-1")
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(freshEvidence()),
            writeCheckpointResults = mutableListOf(success(Unit)),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(
                success(
                    PullChangesResult.Changes(
                        changeSet = changeSet("inline-refresh-set-1", "event-1"),
                        hasMore = true,
                        nextCheckpoint = checkpoint,
                    ),
                ),
                success(
                    PullChangesResult.Changes(
                        changeSet = changeSet("inline-refresh-set-2", "event-2"),
                        hasMore = false,
                    ),
                ),
            ),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        val refresh = assertIs<StrategyCacheInlineRefreshResult.Completed>(served.refresh)
        assertEquals(
            listOf(
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PULL_REMOTE,
            ),
            refresh.completedOperations,
        )
        assertEquals(2, transport.pullCalls)
        assertEquals(2, storage.applyCalls)
    }

    @Test
    fun batchLimitReturnsPartialRefreshWithoutHidingLocalCache() = runTest {
        val checkpoint = checkpoint("inline-refresh-partial")
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(freshEvidence()),
            writeCheckpointResults = mutableListOf(success(Unit)),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(
                success(
                    PullChangesResult.Changes(
                        changeSet = changeSet("inline-refresh-partial-set", "event-1"),
                        hasMore = true,
                        nextCheckpoint = checkpoint,
                    ),
                ),
            ),
        )
        val fixture = fixture(
            storage = storage,
            transport = transport,
            inboundConfiguration = InboundPullPipelineConfiguration(
                maxBatchesPerExecution = 1,
            ),
        )

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        val refresh = assertIs<StrategyCacheInlineRefreshResult.PartiallySucceeded>(
            served.refresh,
        )
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), refresh.completedOperations)
        assertEquals(StrategyCacheState.FRESH, served.freshness.cacheState)
    }

    @Test
    fun explicitPipelineCancellationPreservesLocalCacheAndPullEvidence() = runTest {
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(freshEvidence()),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(
            storage = storage,
            transport = transport,
            pipeline = CancellingPullPipeline,
        )

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        val refresh = assertIs<StrategyCacheInlineRefreshResult.Cancelled>(served.refresh)
        assertEquals(true, refresh.transportAttempted)
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), refresh.completedOperations)
    }

    @Test
    fun adaptiveSelectionCanExecuteConcreteInlineRefresh() = runTest {
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(freshEvidence()),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)
        val cache = cacheProfile()
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("inline-refresh-adaptive"),
            configurationVersion = StrategyConfigurationVersion(4),
            candidates = listOf(cache),
        )

        val result = fixture.coordinator.execute(
            request = request(profile = adaptive),
            bindings = fixture.bindings,
        )

        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(result)
        assertEquals(BuiltInSynchronizationStrategy.ADAPTIVE, served.evaluation.plan.requestedStrategy)
        assertEquals(BuiltInSynchronizationStrategy.CACHE_FIRST, served.evaluation.plan.effectiveStrategy)
        assertEquals(cache.id, served.evaluation.plan.effectiveProfileId)
    }

    @Test
    fun durableAndBidirectionalRefreshPlansRemainRejectedBeforeProviders() = runTest {
        val storage = RecordingCacheStorage(
            cacheAccessResult = available(freshEvidence()),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(success(PullChangesResult.NoChanges())),
        )
        val fixture = fixture(storage, transport)

        val durable = fixture.coordinator.execute(
            request = request(
                profile = cacheProfile(requireDurableRefresh = true),
            ),
            bindings = fixture.bindings,
        )
        val bidirectional = fixture.coordinator.execute(
            request = request(direction = SynchronizationDirection.BIDIRECTIONAL),
            bindings = fixture.bindings,
        )

        assertEquals(
            StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(durable).reason,
        )
        assertEquals(
            StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(bidirectional).reason,
        )
        assertEquals(0, storage.cacheAccessCalls)
        assertEquals(0, storage.readCheckpointCalls)
        assertEquals(0, transport.pullCalls)
    }

    private suspend fun fixture(
        storage: RecordingCacheStorage,
        transport: RecordingTransport,
        inboundConfiguration: InboundPullPipelineConfiguration =
            InboundPullPipelineConfiguration(),
        pipeline: SynchronizationPipeline =
            InboundPullSynchronizationPipeline(inboundConfiguration),
    ): Fixture {
        val registry = ProviderRegistry(listOf(storage, transport))
        val lifecycle = ProviderLifecycleCoordinator(
            registry = registry,
            context = ProviderInitializationContext(),
        )
        lifecycle.initialize()
        val dependencies = runtimeDependencies()
        return Fixture(
            coordinator = StrategySynchronizationExecutionCoordinator(
                lifecycleCoordinator = lifecycle,
                evaluator = BuiltInSynchronizationStrategyEvaluator(),
                providerResolver = StrategyProviderResolver(registry),
                clock = dependencies.clock,
                runtimeDependencies = dependencies,
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
    ): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("inline-refresh-workflow"),
                sessionId = SynchronizationSessionId("inline-refresh-session"),
                direction = direction,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("inline-refresh-execution"),
                    correlationId = CorrelationId("inline-refresh-correlation"),
                ),
            ),
            decisionId = StrategyDecisionId("inline-refresh-decision"),
            planId = StrategyPlanId("inline-refresh-plan"),
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
    ): CacheFirstStrategyProfile =
        CacheFirstStrategyProfile(
            id = StrategyProfileId("inline-refresh-profile"),
            configurationVersion = StrategyConfigurationVersion(1),
            staleCachePolicy = staleCachePolicy,
            refreshOnFreshHit = true,
            requireDurableRefresh = requireDurableRefresh,
        )

    private class RecordingCacheStorage(
        private val calls: MutableList<String> = mutableListOf(),
        private val cacheAccessResult: ProviderOperationResult<StrategyCacheAccessResult>,
        private val readCheckpointResult: ProviderOperationResult<SynchronizationCheckpoint?> =
            success(null),
        private val applyResults: MutableList<ProviderOperationResult<Unit>> = mutableListOf(),
        private val writeCheckpointResults: MutableList<ProviderOperationResult<Unit>> =
            mutableListOf(),
    ) : StrategyCacheAccessProvider {
        override val descriptor: ProviderDescriptor = descriptor(
            id = "inline-refresh-storage",
            type = ProviderType.STORAGE,
        )

        var cacheAccessCalls: Int = 0
            private set
        var readCheckpointCalls: Int = 0
            private set
        var applyCalls: Int = 0
            private set
        var writeCheckpointCalls: Int = 0
            private set
        var outboundCalls: Int = 0
            private set
        var lastCacheRequest: StrategyCacheAccessRequest? = null
            private set

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun evaluateCacheAccess(
            request: StrategyCacheAccessRequest,
        ): ProviderOperationResult<StrategyCacheAccessResult> {
            cacheAccessCalls++
            lastCacheRequest = request
            calls += "cacheAccess"
            return cacheAccessResult
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            outboundCalls++
            return success(OutboundChangeReadResult.NoChanges)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyCalls++
            calls += "apply"
            return if (applyResults.isEmpty()) success(Unit) else applyResults.removeAt(0)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            readCheckpointCalls++
            calls += "readCheckpoint"
            return readCheckpointResult
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            writeCheckpointCalls++
            calls += "writeCheckpoint"
            return if (writeCheckpointResults.isEmpty()) {
                success(Unit)
            } else {
                writeCheckpointResults.removeAt(0)
            }
        }
    }

    private class RecordingTransport(
        private val calls: MutableList<String> = mutableListOf(),
        private val pullResults: MutableList<ProviderOperationResult<PullChangesResult>>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = descriptor(
            id = "inline-refresh-transport",
            type = ProviderType.TRANSPORT,
        )

        var pullCalls: Int = 0
            private set
        var pushCalls: Int = 0
            private set

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            error("Inline cache PULL refresh must not invoke pushChanges.")
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            calls += "pull"
            check(pullResults.isNotEmpty()) { "No scripted inline refresh pull result." }
            return pullResults.removeAt(0)
        }
    }

    private object CancellingPullPipeline : SynchronizationPipeline {
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

    private data class InlineRefreshFailure(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Inline refresh provider failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class InlineRefreshRemoteFailure(
        override val remoteOutcome: StrategyRemoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
        override val code: ErrorCode = ErrorCode("INLINE_REFRESH_REMOTE_UNAVAILABLE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Inline refresh remote unavailable.",
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private companion object {
        fun freshEvidence(): StrategyCacheFreshnessEvidence =
            StrategyCacheFreshnessEvidence(
                cacheState = StrategyCacheState.FRESH,
                observedAt = DataLoomInstant(10_000L),
                validUntil = DataLoomInstant(20_000L),
            )

        fun staleEvidence(): StrategyCacheFreshnessEvidence =
            StrategyCacheFreshnessEvidence(
                cacheState = StrategyCacheState.STALE,
                observedAt = DataLoomInstant(1_000L),
                validUntil = DataLoomInstant(2_000L),
            )

        fun available(
            evidence: StrategyCacheFreshnessEvidence,
        ): ProviderOperationResult<StrategyCacheAccessResult> =
            success(StrategyCacheAccessResult.Available(evidence))

        fun checkpoint(token: String): SynchronizationCheckpoint =
            SynchronizationCheckpoint(
                key = CheckpointKey("inline-refresh-workflow"),
                token = CheckpointToken(token),
            )

        fun changeSet(id: String, eventId: String): ChangeSet =
            ChangeSet(
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

        fun runtimeDependencies(): RuntimeDependencies =
            RuntimeDependencies(
                clock = FixedClock(DataLoomInstant(30_000L)),
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds =
                        fixedGenerator(SynchronizationEventId("inline-refresh-event")),
                    queueEntryIds = fixedGenerator(QueueEntryId("inline-refresh-entry")),
                    queueLeaseIds = fixedGenerator(QueueLeaseId("inline-refresh-lease")),
                    conflictIds = fixedGenerator(ConflictId("inline-refresh-conflict")),
                ),
            )

        fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = value
            }

        fun descriptor(id: String, type: ProviderType): ProviderDescriptor =
            ProviderDescriptor(
                id = ProviderId(id),
                name = ProviderName(id),
                type = type,
                version = ProviderVersion("1.0.0"),
            )

        fun <T> success(value: T): ProviderOperationResult<T> =
            ProviderOperationResult.Success(value)
    }
}
