package io.dataloom.runtime.strategy

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
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
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.SynchronizationStrategyProfile
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CacheFirstServingExecutionTest {

    @Test
    fun freshCacheIsServedWithProviderObservedEvidence() = runTest {
        val freshness = freshEvidence()
        val storage = RecordingCacheStorage(success(StrategyCacheAccessResult.Available(freshness)))
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(cacheState = StrategyCacheState.FRESH),
            bindings = fixture.bindings,
        )

        val served = assertIs<StrategySynchronizationExecutionResult.CacheServed>(result)
        assertEquals(StrategyCacheState.FRESH, served.evaluatedCacheState)
        assertEquals(freshness, served.freshness)
        assertEquals(StrategyDataOrigin.LOCAL, served.dataOrigin)
        assertEquals(1, storage.cacheAccessCalls)
        assertEquals(false, storage.lastRequest?.allowStale)
        assertEquals(0, storage.storageOperationCalls)
    }

    @Test
    fun policyAllowedStaleCacheIsServed() = runTest {
        val freshness = staleEvidence()
        val storage = RecordingCacheStorage(success(StrategyCacheAccessResult.Available(freshness)))
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(
                cacheState = StrategyCacheState.STALE,
                stalePolicy = StaleCachePolicy.SERVE_STALE,
            ),
            bindings = fixture.bindings,
        )

        val served = assertIs<StrategySynchronizationExecutionResult.CacheServed>(result)
        assertEquals(StrategyCacheState.STALE, served.evaluatedCacheState)
        assertEquals(StrategyCacheState.STALE, served.freshness.cacheState)
        assertEquals(true, storage.lastRequest?.allowStale)
        assertEquals(1, storage.cacheAccessCalls)
    }

    @Test
    fun freshnessImprovementFromStaleToFreshIsSafeToServe() = runTest {
        val storage = RecordingCacheStorage(
            success(StrategyCacheAccessResult.Available(freshEvidence())),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(
                cacheState = StrategyCacheState.STALE,
                stalePolicy = StaleCachePolicy.SERVE_STALE,
            ),
            bindings = fixture.bindings,
        )

        val served = assertIs<StrategySynchronizationExecutionResult.CacheServed>(result)
        assertEquals(StrategyCacheState.STALE, served.evaluatedCacheState)
        assertEquals(StrategyCacheState.FRESH, served.freshness.cacheState)
    }

    @Test
    fun freshnessDowngradeFailsClosedWithoutRemoteFallback() = runTest {
        val freshness = staleEvidence()
        val storage = RecordingCacheStorage(success(StrategyCacheAccessResult.Available(freshness)))
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(cacheState = StrategyCacheState.FRESH),
            bindings = fixture.bindings,
        )

        val unavailable =
            assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(result)
        assertEquals(
            StrategyCacheUnavailableReason.FRESHNESS_DOWNGRADED,
            unavailable.reason,
        )
        assertEquals(StrategyCacheState.FRESH, unavailable.evaluatedCacheState)
        assertEquals(StrategyCacheState.STALE, unavailable.providerCacheState)
        assertEquals(freshness, unavailable.providerFreshness)
        assertEquals(StrategyDataOrigin.NONE, unavailable.dataOrigin)
        assertEquals(1, storage.cacheAccessCalls)
        assertEquals(0, storage.storageOperationCalls)
    }

    @Test
    fun providerUnavailableStateIsReturnedWithoutStrategySwitch() = runTest {
        val storage = RecordingCacheStorage(
            success(StrategyCacheAccessResult.Unavailable(StrategyCacheState.MISSING)),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(cacheState = StrategyCacheState.FRESH),
            bindings = fixture.bindings,
        )

        val unavailable =
            assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(result)
        assertEquals(
            StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE,
            unavailable.reason,
        )
        assertEquals(StrategyCacheState.MISSING, unavailable.providerCacheState)
        assertNull(unavailable.providerFreshness)
        assertEquals(1, storage.cacheAccessCalls)
    }

    @Test
    fun providerFailureRemainsFailureWithoutTransportAttempt() = runTest {
        val failure = CacheAccessFailure()
        val storage = RecordingCacheStorage(ProviderOperationResult.Failure(failure))
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(cacheState = StrategyCacheState.FRESH),
            bindings = fixture.bindings,
        )

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(failure, failed.error)
        assertEquals(false, failed.transportAttempted)
        assertEquals(1, storage.cacheAccessCalls)
    }

    @Test
    fun adaptiveSelectionPreservesConcreteCacheServingExecution() = runTest {
        val storage = RecordingCacheStorage(
            success(StrategyCacheAccessResult.Available(freshEvidence())),
        )
        val fixture = fixture(storage)
        val cacheProfile = cacheProfile()
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive-profile"),
            configurationVersion = StrategyConfigurationVersion(4),
            candidates = listOf(cacheProfile),
        )

        val result = fixture.coordinator.execute(
            request = request(
                cacheState = StrategyCacheState.FRESH,
                profile = adaptive,
            ),
            bindings = fixture.bindings,
        )

        val served = assertIs<StrategySynchronizationExecutionResult.CacheServed>(result)
        assertEquals(
            BuiltInSynchronizationStrategy.ADAPTIVE,
            served.evaluation.plan.requestedStrategy,
        )
        assertEquals(
            BuiltInSynchronizationStrategy.CACHE_FIRST,
            served.evaluation.plan.effectiveStrategy,
        )
        assertEquals(cacheProfile.id, served.evaluation.plan.effectiveProfileId)
        assertEquals(1, storage.cacheAccessCalls)
    }

    @Test
    fun refreshPlanIsRejectedBeforeProviderInvocation() = runTest {
        val storage = RecordingCacheStorage(
            success(StrategyCacheAccessResult.Available(freshEvidence())),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(
                cacheState = StrategyCacheState.FRESH,
                refreshOnFreshHit = true,
                requireDurableRefresh = false,
            ),
            bindings = fixture.bindings,
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.UNSUPPORTED_PLAN, rejected.reason)
        assertEquals(0, storage.cacheAccessCalls)
        assertEquals(0, storage.storageOperationCalls)
    }

    private suspend fun fixture(storage: RecordingCacheStorage): Fixture {
        val registry = ProviderRegistry(listOf(storage))
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
                pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
                lifecycleEventEmitter = null,
            ),
            bindings = StrategyProviderBindings(
                storageProviderId = storage.descriptor.id,
            ),
        )
    }

    private fun request(
        cacheState: StrategyCacheState,
        stalePolicy: StaleCachePolicy = StaleCachePolicy.SERVE_STALE,
        refreshOnFreshHit: Boolean = false,
        requireDurableRefresh: Boolean = true,
        profile: SynchronizationStrategyProfile = cacheProfile(
            stalePolicy = stalePolicy,
            refreshOnFreshHit = refreshOnFreshHit,
            requireDurableRefresh = requireDurableRefresh,
        ),
    ): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("workflow-1"),
                sessionId = SynchronizationSessionId("session-1"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("execution-1"),
                    correlationId = CorrelationId("correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("decision-1"),
            planId = StrategyPlanId("plan-1"),
            profile = profile,
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = cacheState,
                storageHealth = StrategyProviderHealth.HEALTHY,
                transportHealth = StrategyProviderHealth.HEALTHY,
                queueHealth = StrategyProviderHealth.HEALTHY,
                isBackgroundExecutionAvailable = true,
            ),
        )

    private fun cacheProfile(
        stalePolicy: StaleCachePolicy = StaleCachePolicy.SERVE_STALE,
        refreshOnFreshHit: Boolean = false,
        requireDurableRefresh: Boolean = true,
    ): CacheFirstStrategyProfile = CacheFirstStrategyProfile(
        id = StrategyProfileId("cache-profile"),
        configurationVersion = StrategyConfigurationVersion(3),
        staleCachePolicy = stalePolicy,
        refreshOnFreshHit = refreshOnFreshHit,
        requireDurableRefresh = requireDurableRefresh,
    )

    private fun freshEvidence(): StrategyCacheFreshnessEvidence =
        StrategyCacheFreshnessEvidence(
            cacheState = StrategyCacheState.FRESH,
            observedAt = DataLoomInstant(1_000L),
            validUntil = DataLoomInstant(2_000L),
        )

    private fun staleEvidence(): StrategyCacheFreshnessEvidence =
        StrategyCacheFreshnessEvidence(
            cacheState = StrategyCacheState.STALE,
            observedAt = DataLoomInstant(2_000L),
            validUntil = DataLoomInstant(2_000L),
        )

    private fun runtimeDependencies(): RuntimeDependencies =
        RuntimeDependencies(
            clock = FixedClock(DataLoomInstant(9_000L)),
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds = fixedGenerator(SynchronizationEventId("event-1")),
                queueEntryIds = fixedGenerator(QueueEntryId("entry-1")),
                queueLeaseIds = fixedGenerator(QueueLeaseId("lease-1")),
                conflictIds = fixedGenerator(ConflictId("conflict-1")),
            ),
        )

    private fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = value
        }

    private data class Fixture(
        val coordinator: StrategySynchronizationExecutionCoordinator,
        val bindings: StrategyProviderBindings,
    )

    private class RecordingCacheStorage(
        private val cacheResult: ProviderOperationResult<StrategyCacheAccessResult>,
    ) : StrategyCacheAccessProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cache-storage"),
            name = ProviderName("Cache Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        var cacheAccessCalls: Int = 0
        var storageOperationCalls: Int = 0
        var lastRequest: StrategyCacheAccessRequest? = null

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(
                ProviderHealth(ProviderHealthStatus.HEALTHY),
            )

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun evaluateCacheAccess(
            request: StrategyCacheAccessRequest,
        ): ProviderOperationResult<StrategyCacheAccessResult> {
            cacheAccessCalls++
            lastRequest = request
            return cacheResult
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> = unexpectedStorageCall()

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = unexpectedStorageCall()

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = unexpectedStorageCall()

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = unexpectedStorageCall()

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = unexpectedStorageCall()

        private fun <T> unexpectedStorageCall(): T {
            storageOperationCalls++
            error("Ordinary storage operations must not run during cache verification.")
        }
    }

    private data class CacheAccessFailure(
        override val code: ErrorCode = ErrorCode("CACHE_ACCESS_FAILED"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Cache access failed.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private fun <T> success(value: T): ProviderOperationResult<T> =
        ProviderOperationResult.Success(value)
}
