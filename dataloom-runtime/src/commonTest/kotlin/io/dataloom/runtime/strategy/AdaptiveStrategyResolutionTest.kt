package io.dataloom.runtime.strategy

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.execution.StrategyProviderSet
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
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.strategy.AdaptiveStrategyProfile
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.HybridSource
import io.dataloom.api.strategy.HybridStrategyProfile
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
import io.dataloom.api.strategy.StrategyTransportOutput
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
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Direct tests proving [BuiltInSynchronizationStrategyEvaluator]'s adaptive
 * resolution executes correctly end-to-end through the concrete executors it
 * resolves to.
 *
 * There is no dedicated `AdaptiveStrategyExecutor` in this codebase, by
 * design: `BuiltInSynchronizationStrategyEvaluator.evaluateAdaptive` resolves
 * an [AdaptiveStrategyProfile] to one of its concrete candidates and
 * delegates straight to that candidate's own evaluation branch, so the
 * resulting [io.dataloom.api.strategy.StrategyExecutionPlan.effectiveStrategy]
 * is always one of the five concrete strategies
 * [StrategySynchronizationExecutionCoordinator] already dispatches. Adaptive
 * needed no new executor -- but it exposed a real bug in the executors that
 * DO exist:
 *
 * [StrategySynchronizationRequest.profile] is the profile the *caller*
 * originally submitted and is never replaced after evaluation. For a plain
 * concrete profile that's already correct. But when the caller submitted an
 * [AdaptiveStrategyProfile], `request.profile` stays the outer adaptive
 * profile even after resolution -- [RemoteFirstStrategyExecutor] and
 * [HybridStrategyExecutor] both read their own profile-specific fields
 * (`persistRemoteResult`, etc.) via an unconditional
 * `request.profile as ConcreteProfileType` cast, which throws
 * `ClassCastException` for any adaptive-resolved request reaching them.
 * [resolvedProfile] fixes this by resolving the actual selected candidate
 * from [io.dataloom.api.strategy.StrategyExecutionPlan.effectiveProfileId]
 * whenever `request.profile` is adaptive.
 */
class AdaptiveStrategyResolutionTest {

    private val now = DataLoomInstant(epochMilliseconds = 15_000L)
    private val clock = FixedDataLoomClock(now)
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()
    private val runtimeDependencies = runtimeDependencies(clock)

    @Test
    fun adaptiveResolvedToRemoteFirstExecutesThroughTheRealCandidateProfile() = runTest {
        val candidate = RemoteFirstStrategyProfile(
            id = StrategyProfileId("remote-candidate"),
            configurationVersion = StrategyConfigurationVersion(1L),
            persistRemoteResult = false,
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(1L),
            candidates = listOf(candidate),
        )
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val request = adaptiveRequest(
            direction = SynchronizationDirection.PULL,
            profile = adaptive,
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val evaluation = evaluator.evaluate(request.evaluationRequest())
        assertEquals(BuiltInSynchronizationStrategy.ADAPTIVE, evaluation.plan.requestedStrategy)
        assertEquals(BuiltInSynchronizationStrategy.REMOTE_FIRST, evaluation.plan.effectiveStrategy)
        assertEquals(candidate.id, evaluation.plan.effectiveProfileId)

        // Before the resolvedProfile() fix, RemoteFirstStrategyExecutor's
        // unconditional `request.profile as RemoteFirstStrategyProfile` cast
        // threw ClassCastException here, since request.profile is still the
        // outer AdaptiveStrategyProfile -- persistRemoteResult = false on the
        // resolved candidate proves the fix reads the REAL candidate's
        // fields, not just that the cast no longer throws.
        val result = RemoteFirstStrategyExecutor(
            clock = clock,
            runtimeDependencies = runtimeDependencies,
            pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
            lifecycleEventEmitter = null,
        ).execute(
            request = request,
            evaluation = evaluation,
            providers = providerSet(transport, storage = null),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun adaptiveResolvedToHybridExecutesThroughTheRealCandidateProfile() = runTest {
        val candidate = HybridStrategyProfile(
            id = StrategyProfileId("hybrid-candidate"),
            configurationVersion = StrategyConfigurationVersion(1L),
            primarySource = HybridSource.REMOTE,
            fallbackSource = HybridSource.LOCAL,
            persistRemoteResult = false,
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(1L),
            candidates = listOf(candidate),
        )
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val request = adaptiveRequest(
            direction = SynchronizationDirection.PULL,
            profile = adaptive,
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val evaluation = evaluator.evaluate(request.evaluationRequest())
        assertEquals(BuiltInSynchronizationStrategy.HYBRID, evaluation.plan.effectiveStrategy)
        assertEquals(candidate.id, evaluation.plan.effectiveProfileId)

        // Same ClassCastException risk as remote-first before the fix --
        // HybridStrategyExecutor also casts request.profile unconditionally.
        val result = HybridStrategyExecutor(
            clock = clock,
            runtimeDependencies = runtimeDependencies,
            pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
            lifecycleEventEmitter = null,
        ).execute(
            request = request,
            evaluation = evaluation,
            providers = providerSet(transport, storage = null),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun adaptiveResolvedToCacheFirstExecutesThroughTheRealCandidateProfile() = runTest {
        // Sanity check for a candidate whose executor never casts
        // request.profile at all (every profile-specific decision is baked
        // into evaluation.plan.operations already) -- was never affected by
        // the bug, included so the adaptive execution path has coverage for
        // every strategy family, not only the two that needed a fix.
        val candidate = io.dataloom.api.strategy.CacheFirstStrategyProfile(
            id = StrategyProfileId("cache-candidate"),
            configurationVersion = StrategyConfigurationVersion(1L),
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(1L),
            candidates = listOf(candidate),
        )
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(inboundEventsReceived = 1),
            )
        }
        val request = adaptiveRequest(
            direction = SynchronizationDirection.PULL,
            profile = adaptive,
            connectivity = StrategyConnectivity.AVAILABLE,
            cacheState = StrategyCacheState.MISSING,
        )
        val evaluation = evaluator.evaluate(request.evaluationRequest())
        assertEquals(BuiltInSynchronizationStrategy.CACHE_FIRST, evaluation.plan.effectiveStrategy)

        val result = CacheFirstStrategyExecutor(
            clock = clock,
            runtimeDependencies = runtimeDependencies,
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            lifecycleEventEmitter = null,
        ).execute(
            request = request,
            evaluation = evaluation,
            providers = providerSet(FakeTransportProvider(), storage = FakePlainStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
    }

    @Test
    fun adaptiveResolvedToOfflineFirstExecutesThroughTheRealCandidateProfile() = runTest {
        // Another sanity check for a candidate whose executor never casts
        // request.profile.
        val candidate = io.dataloom.api.strategy.OfflineFirstStrategyProfile(
            id = StrategyProfileId("offline-candidate"),
            configurationVersion = StrategyConfigurationVersion(1L),
            requireDurableQueue = false,
            reconcileWhenOnline = false,
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(1L),
            candidates = listOf(candidate),
        )
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(inboundEventsReceived = 1),
            )
        }
        val request = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("adaptive-workflow"),
                sessionId = SynchronizationSessionId("adaptive-session"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("adaptive-execution"),
                    correlationId = CorrelationId("adaptive-correlation"),
                ),
            ),
            decisionId = StrategyDecisionId("adaptive-decision"),
            planId = StrategyPlanId("adaptive-plan"),
            profile = adaptive,
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                hasPendingLocalChanges = true,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )
        val evaluation = evaluator.evaluate(request.evaluationRequest())
        assertEquals(BuiltInSynchronizationStrategy.OFFLINE_FIRST, evaluation.plan.effectiveStrategy)

        val result = OfflineFirstStrategyExecutor(
            clock = clock,
            runtimeDependencies = runtimeDependencies,
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            lifecycleEventEmitter = null,
        ).execute(
            request = request,
            evaluation = evaluation,
            providers = providerSet(FakeTransportProvider(), storage = FakePlainStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
    }

    @Test
    fun adaptiveResolvedToNetworkOnlyExecutesThroughTheRealCandidateProfile() = runTest {
        // Another sanity check for a candidate whose executor never casts
        // request.profile -- it casts request.input instead, which is
        // already guarded at the coordinator level (INCOMPATIBLE_INPUT)
        // before any executor is reached, so it was never at risk the same
        // way request.profile was.
        val candidate = io.dataloom.api.strategy.NetworkOnlyStrategyProfile(
            id = StrategyProfileId("network-candidate"),
            configurationVersion = StrategyConfigurationVersion(1L),
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(1L),
            candidates = listOf(candidate),
        )
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val request = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("adaptive-workflow"),
                sessionId = SynchronizationSessionId("adaptive-session"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("adaptive-execution"),
                    correlationId = CorrelationId("adaptive-correlation"),
                ),
            ),
            decisionId = StrategyDecisionId("adaptive-decision"),
            planId = StrategyPlanId("adaptive-plan"),
            profile = adaptive,
            evidence = StrategyRuntimeEvidence(connectivity = StrategyConnectivity.AVAILABLE),
            input = io.dataloom.api.strategy.StrategyOperationInput.DirectTransport(),
        )
        val evaluation = evaluator.evaluate(request.evaluationRequest())
        assertEquals(BuiltInSynchronizationStrategy.NETWORK_ONLY, evaluation.plan.effectiveStrategy)

        val result = NetworkOnlyStrategyExecutor(clock).execute(
            request = request,
            evaluation = evaluation,
            providers = providerSet(transport, storage = null),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertEquals(1, transport.pullCalls)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun adaptiveRequest(
        direction: SynchronizationDirection,
        profile: AdaptiveStrategyProfile,
        cacheState: StrategyCacheState = StrategyCacheState.NOT_EVALUATED,
        connectivity: StrategyConnectivity = StrategyConnectivity.AVAILABLE,
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("adaptive-workflow"),
            sessionId = SynchronizationSessionId("adaptive-session"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("adaptive-execution"),
                correlationId = CorrelationId("adaptive-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("adaptive-decision"),
        planId = StrategyPlanId("adaptive-plan"),
        profile = profile,
        evidence = StrategyRuntimeEvidence(connectivity = connectivity, cacheState = cacheState),
        input = StrategyOperationInput.ProviderBacked,
    )

    private fun providerSet(
        transport: TransportProvider,
        storage: StorageProvider?,
    ): StrategyProviderSet = object : StrategyProviderSet {
        override val storageProvider: StorageProvider? = storage
        override val transportProvider: TransportProvider = transport
        override val schedulerProvider: SchedulerProvider? = null
        override val connectivityProvider: ConnectivityProvider? = null
        override val queueProvider: QueueProvider? = null
    }

    private fun testError(code: String): DataLoomError = TestAdaptiveError(ErrorCode(code))

    private data class TestAdaptiveError(
        override val code: ErrorCode,
        override val message: String = "test adaptive failure",
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class FakePipeline(
        override val direction: SynchronizationDirection,
        private val handler: suspend (SynchronizationExecutionContext) -> SynchronizationResult,
    ) : SynchronizationPipeline {
        var executeCalls: Int = 0
            private set

        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            executeCalls++
            return handler(context)
        }
    }

    private class FakeTransportProvider(
        private val pushResult: ProviderOperationResult<io.dataloom.api.synchronization.ChangeSetAcknowledgement>? = null,
        private val pullResult: ProviderOperationResult<PullChangesResult>? = null,
    ) : TransportProvider {
        var pushCalls: Int = 0
            private set
        var pullCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("adaptive-transport"),
            name = ProviderName("Adaptive Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<io.dataloom.api.synchronization.ChangeSetAcknowledgement> {
            pushCalls++
            return requireNotNull(pushResult) { "Test did not configure a pushChanges result." }
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            return requireNotNull(pullResult) { "Test did not configure a pullChanges result." }
        }
    }

    /** Bare [StorageProvider] with no strategy capabilities -- just satisfies non-null context wiring. */
    private class FakePlainStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("adaptive-storage"),
            name = ProviderName("Adaptive Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

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
    }

    private companion object {
        fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies =
            RuntimeDependencies(
                clock = clock,
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds = generator { SynchronizationEventId("adaptive-event") },
                    queueEntryIds = generator { QueueEntryId("adaptive-queue-entry") },
                    queueLeaseIds = generator { QueueLeaseId("adaptive-queue-lease") },
                    conflictIds = generator { ConflictId("adaptive-conflict") },
                ),
            )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }
    }
}
