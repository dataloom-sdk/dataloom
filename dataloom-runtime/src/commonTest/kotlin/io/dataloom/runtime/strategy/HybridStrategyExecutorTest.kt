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
import io.dataloom.api.strategy.HybridSource
import io.dataloom.api.strategy.HybridStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
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
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Direct unit tests for [HybridStrategyExecutor].
 *
 * Covers every branch [BuiltInSynchronizationStrategyEvaluator] can produce
 * for [HybridStrategyProfile] that this executor is scoped to handle
 * directly: `REMOTE` selected (PUSH/PULL/BIDIRECTIONAL, persisting and
 * non-persisting), `LOCAL` selected (PULL/BIDIRECTIONAL serve, and the
 * transport-free PUSH branch this executor rejects), the shared
 * durable-work rejection, and the local-state-mismatch contract error.
 * Also verifies hybrid never reacts to a runtime remote failure by
 * improvising a fallback — unlike [RemoteFirstStrategyExecutor], the
 * fallback decision is entirely pre-computed by the evaluator.
 */
class HybridStrategyExecutorTest {

    private val now = DataLoomInstant(epochMilliseconds = 13_000L)
    private val clock = FixedDataLoomClock(now)
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()
    private val runtimeDependencies = runtimeDependencies(clock)

    // -------------------------------------------------------------------------
    // REMOTE selected
    // -------------------------------------------------------------------------

    @Test
    fun remotePrimaryPullSucceeds() = runTest {
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(inboundEventsReceived = 3),
            )
        }
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(primarySource = HybridSource.REMOTE),
            cacheState = StrategyCacheState.NOT_EVALUATED,
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), FakeFallbackStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
        assertEquals(1, pipeline.executeCalls)
    }

    @Test
    fun remotePrimaryPushSucceeds() = runTest {
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(outboundEventsRead = 1, outboundEventsAccepted = 1),
            )
        }
        val request = hybridRequest(
            direction = SynchronizationDirection.PUSH,
            profile = hybridProfile(primarySource = HybridSource.REMOTE),
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), FakeFallbackStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
        assertEquals(1, pipeline.executeCalls)
    }

    @Test
    fun remotePrimaryBidirectionalSucceeds() = runTest {
        val pipeline = FakePipeline(SynchronizationDirection.BIDIRECTIONAL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(
                    inboundEventsReceived = 1,
                    outboundEventsRead = 1,
                    outboundEventsAccepted = 1,
                ),
            )
        }
        val request = hybridRequest(
            direction = SynchronizationDirection.BIDIRECTIONAL,
            profile = hybridProfile(primarySource = HybridSource.REMOTE),
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), FakeFallbackStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
        assertEquals(1, pipeline.executeCalls)
    }

    @Test
    fun remoteFailurePropagatesWithoutReactiveFallback() = runTest {
        // Local state IS available (FRESH), but hybrid's REMOTE-selected plan
        // never carries a fallbackPlan -- unlike RemoteFirstStrategyExecutor,
        // a remote failure here is just a failure, not a trigger to try local.
        val error = testError("REMOTE_UNAVAILABLE")
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Failed(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(),
                error = error,
            )
        }
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(primarySource = HybridSource.REMOTE),
            cacheState = StrategyCacheState.FRESH,
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(error, failed.error)
        assertEquals(0, storage.evaluateLocalFallbackCalls)
    }

    @Test
    fun durableRefreshFallbackIsExplicitlyRejectedNotSilentlyMisexecuted() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(
                primarySource = HybridSource.REMOTE,
                reconcileAfterFallback = true,
            ),
            cacheState = StrategyCacheState.STALE,
            connectivity = StrategyConnectivity.UNAVAILABLE,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED,
            rejected.reason,
        )
        assertEquals(0, storage.evaluateLocalFallbackCalls)
    }

    @Test
    fun durableRefreshFallbackIsAdmittedAndStillServesLocalStateSynchronously() = runTest {
        // PULL: LOCAL-as-fallback-from-REMOTE always carries SERVE_LOCAL
        // alongside ENQUEUE_DURABLE_WORK -- admission does not replace
        // serving local state here, unlike offline-first's PUSH branch.
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val queue = FakeAdmissionQueueProvider()
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(
                primarySource = HybridSource.REMOTE,
                reconcileAfterFallback = true,
            ),
            cacheState = StrategyCacheState.STALE,
            connectivity = StrategyConnectivity.UNAVAILABLE,
        )
        val result = executor(durableQueueAdmitter = admitter(FakeEncoder())).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage, queue = queue),
        )
        val served = assertIs<StrategySynchronizationExecutionResult.ServedFromCache>(result)
        assertEquals(StrategyCacheState.STALE, served.cacheState)
        assertEquals(QueueEntryId("hybrid-queue-entry"), served.durableQueueEntryId)
        assertEquals(1, storage.evaluateLocalFallbackCalls)
        assertEquals(1, queue.enqueueCalls)
    }

    @Test
    fun durableRefreshFallbackAdmissionFailurePropagatesAsFailed() = runTest {
        val providerError = testError("ENQUEUE_UNAVAILABLE")
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val queue = FakeAdmissionQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(providerError),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(
                primarySource = HybridSource.REMOTE,
                reconcileAfterFallback = true,
            ),
            cacheState = StrategyCacheState.STALE,
            connectivity = StrategyConnectivity.UNAVAILABLE,
        )
        val result = executor(durableQueueAdmitter = admitter(FakeEncoder())).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage, queue = queue),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(providerError, failed.error)
        assertEquals(0, storage.evaluateLocalFallbackCalls)
    }

    @Test
    fun durableRefreshFallbackForPushIsDurablyEnqueuedWithoutServingLocal() = runTest {
        // PUSH: LOCAL-as-fallback-from-REMOTE carries only READ_LOCAL --
        // never SERVE_LOCAL -- so admission is the entire outcome, same
        // short-circuit shape as offline-first's durable branch.
        val storage = FakeFallbackStorageProvider()
        val queue = FakeAdmissionQueueProvider()
        val request = hybridRequest(
            direction = SynchronizationDirection.PUSH,
            profile = hybridProfile(
                primarySource = HybridSource.REMOTE,
                reconcileAfterFallback = true,
            ),
            cacheState = StrategyCacheState.STALE,
            connectivity = StrategyConnectivity.UNAVAILABLE,
        )
        val result = executor(durableQueueAdmitter = admitter(FakeEncoder())).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage, queue = queue),
        )
        val enqueued = assertIs<StrategySynchronizationExecutionResult.DurablyEnqueued>(result)
        assertEquals(QueueEntryId("hybrid-queue-entry"), enqueued.queueEntryId)
        assertEquals(1, queue.enqueueCalls)
        assertEquals(0, storage.evaluateLocalFallbackCalls)
    }

    // -------------------------------------------------------------------------
    // REMOTE selected, non-persisting
    // -------------------------------------------------------------------------

    @Test
    fun persistRemoteResultFalsePullUsesTransportOnly() = runTest {
        val pipeline = FakePipeline(SynchronizationDirection.PULL) {
            error("Non-persisting PULL must not run the registered pipeline.")
        }
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(
                primarySource = HybridSource.REMOTE,
                persistRemoteResult = false,
            ),
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, FakeFallbackStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertEquals(1, transport.pullCalls)
        assertEquals(0, pipeline.executeCalls)
    }

    @Test
    fun persistRemoteResultFalsePullFailurePropagates() = runTest {
        val error = testError("TRANSPORT_ONLY_PULL_FAILED")
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Failure(error),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(
                primarySource = HybridSource.REMOTE,
                persistRemoteResult = false,
            ),
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, FakeFallbackStorageProvider()),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(error, failed.error)
    }

    @Test
    fun persistRemoteResultFalseBidirectionalUsesNonPersistingPath() = runTest {
        val pushPipeline = FakePipeline(SynchronizationDirection.PUSH) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(outboundEventsRead = 1, outboundEventsAccepted = 1),
            )
        }
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.BIDIRECTIONAL,
            profile = hybridProfile(
                primarySource = HybridSource.REMOTE,
                persistRemoteResult = false,
            ),
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pushPipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, FakeFallbackStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.RemoteFirstBidirectional>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.pushResult)
        assertEquals(1, pushPipeline.executeCalls)
        assertEquals(1, transport.pullCalls)
    }

    // -------------------------------------------------------------------------
    // LOCAL selected
    // -------------------------------------------------------------------------

    @Test
    fun localPrimaryPullServesFromCache() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(primarySource = HybridSource.LOCAL),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val served = assertIs<StrategySynchronizationExecutionResult.ServedFromCache>(result)
        assertEquals(StrategyCacheState.FRESH, served.cacheState)
        assertNull(served.refreshOutput)
        assertEquals(1, storage.evaluateLocalFallbackCalls)
    }

    @Test
    fun localPrimaryBidirectionalServesFromCache() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.BIDIRECTIONAL,
            profile = hybridProfile(primarySource = HybridSource.LOCAL),
            cacheState = StrategyCacheState.STALE,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val served = assertIs<StrategySynchronizationExecutionResult.ServedFromCache>(result)
        assertEquals(StrategyCacheState.STALE, served.cacheState)
        assertNull(served.refreshOutput)
    }

    @Test
    fun localPrimaryPushIsRejectedAsTransportFree() = runTest {
        // LOCAL primary selected for a PUSH direction produces a plan whose
        // only operation is READ_LOCAL -- nothing to serve, nothing to push.
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.PUSH,
            profile = hybridProfile(primarySource = HybridSource.LOCAL),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.HYBRID_LOCAL_PUSH_NOT_YET_SUPPORTED,
            rejected.reason,
        )
        assertEquals(0, storage.evaluateLocalFallbackCalls)
    }

    @Test
    fun localStateMismatchWithEvidenceIsAContractError() = runTest {
        // Evidence said FRESH (that's how this operation set was admitted at
        // all), but the actual fallback provider reports nothing available --
        // a genuine runtime inconsistency, not a normal branch.
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
        )
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(primarySource = HybridSource.LOCAL),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals("DL-STRATEGY-HYBRID-LOCAL-STATE-MISMATCH", failed.error.code.value)
    }

    @Test
    fun missingFallbackProviderIsRejectedWhenServingLocal() = runTest {
        val request = hybridRequest(
            direction = SynchronizationDirection.PULL,
            profile = hybridProfile(primarySource = HybridSource.LOCAL),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage = null),
        )
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
            rejected.reason,
        )
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun executor(
        pipelineRegistry: SynchronizationPipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
        durableQueueAdmitter: StrategyDurableQueueAdmitter? = null,
    ): HybridStrategyExecutor = HybridStrategyExecutor(
        clock = clock,
        runtimeDependencies = runtimeDependencies,
        pipelineRegistry = pipelineRegistry,
        lifecycleEventEmitter = null,
        durableQueueAdmitter = durableQueueAdmitter,
    )

    private fun admitter(
        encoder: io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder?,
    ): StrategyDurableQueueAdmitter = StrategyDurableQueueAdmitter(
        clock = clock,
        runtimeDependencies = runtimeDependencies,
        encoder = encoder,
    )

    private fun hybridProfile(
        primarySource: HybridSource,
        persistRemoteResult: Boolean = true,
        reconcileAfterFallback: Boolean = true,
    ): HybridStrategyProfile = HybridStrategyProfile(
        id = StrategyProfileId("hybrid-profile"),
        configurationVersion = StrategyConfigurationVersion(1L),
        primarySource = primarySource,
        fallbackSource = if (primarySource == HybridSource.REMOTE) HybridSource.LOCAL else HybridSource.REMOTE,
        persistRemoteResult = persistRemoteResult,
        reconcileAfterFallback = reconcileAfterFallback,
    )

    private fun hybridRequest(
        direction: SynchronizationDirection,
        profile: HybridStrategyProfile,
        cacheState: StrategyCacheState = StrategyCacheState.NOT_EVALUATED,
        connectivity: StrategyConnectivity = StrategyConnectivity.AVAILABLE,
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("hybrid-workflow"),
            sessionId = SynchronizationSessionId("hybrid-session"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("hybrid-execution"),
                correlationId = CorrelationId("hybrid-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("hybrid-decision"),
        planId = StrategyPlanId("hybrid-plan"),
        profile = profile,
        evidence = StrategyRuntimeEvidence(connectivity = connectivity, cacheState = cacheState),
        input = StrategyOperationInput.ProviderBacked,
    )

    private fun evaluationFor(request: StrategySynchronizationRequest) =
        evaluator.evaluate(request.evaluationRequest())

    private fun providerSet(
        transport: TransportProvider,
        storage: StorageProvider?,
        queue: QueueProvider? = null,
    ): StrategyProviderSet = object : StrategyProviderSet {
        override val storageProvider: StorageProvider? = storage
        override val transportProvider: TransportProvider = transport
        override val schedulerProvider: SchedulerProvider? = null
        override val connectivityProvider: ConnectivityProvider? = null
        override val queueProvider: QueueProvider? = queue
    }

    private fun testError(code: String): DataLoomError = TestHybridError(ErrorCode(code))

    private data class TestHybridError(
        override val code: ErrorCode,
        override val message: String = "test hybrid failure",
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class FakeEncoder : io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder {
        override fun encode(
            submission: io.dataloom.runtime.submission.QueuedSynchronizationSubmission,
        ): io.dataloom.runtime.submission.QueuedSynchronizationWorkEncodingResult =
            io.dataloom.runtime.submission.QueuedSynchronizationWorkEncodingResult.Encoded(
                io.dataloom.api.queue.QueueEnqueueRequest(
                    entry = io.dataloom.api.queue.QueueEntry(
                        id = submission.queueEntryId,
                        synchronizationRequest = submission.work.request,
                        state = io.dataloom.api.queue.QueueEntryState.PENDING,
                        enqueuedAt = submission.availableAt,
                        availableAt = submission.availableAt,
                        workflowTimeoutState = submission.workflowTimeoutState,
                        strategyDecision = submission.work.strategyDecision,
                        strategyPlan = submission.work.strategyPlan,
                    ),
                ),
            )
    }

    private class FakeAdmissionQueueProvider(
        private val enqueueResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    ) : QueueProvider {
        var enqueueCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("hybrid-queue"),
            name = ProviderName("Hybrid Queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(
            request: io.dataloom.api.queue.QueueEnqueueRequest,
        ): ProviderOperationResult<Unit> {
            enqueueCalls++
            return enqueueResult
        }

        override suspend fun acquire(
            request: io.dataloom.api.queue.QueueAcquireRequest,
        ): ProviderOperationResult<io.dataloom.api.queue.QueueAcquireResult> =
            ProviderOperationResult.Success(io.dataloom.api.queue.QueueAcquireResult.NoEntries)

        override suspend fun complete(
            request: io.dataloom.api.queue.QueueCompletionRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun reschedule(
            request: io.dataloom.api.queue.QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun defer(
            request: io.dataloom.api.queue.QueueDeferralRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun fail(
            request: io.dataloom.api.queue.QueueFailureRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun cancel(
            request: io.dataloom.api.queue.QueueCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun recoverExpiredLeases(
            request: io.dataloom.api.queue.ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<io.dataloom.api.queue.ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(
                io.dataloom.api.queue.ExpiredLeaseRecoveryResult(recoveredEntries = 0),
            )
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
            id = ProviderId("hybrid-transport"),
            name = ProviderName("Hybrid Transport"),
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

    private class FakeFallbackStorageProvider(
        private val fallbackResult: ProviderOperationResult<StrategyLocalFallbackResult> =
            ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
    ) : StrategyLocalFallbackProvider {
        var evaluateLocalFallbackCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("hybrid-storage"),
            name = ProviderName("Hybrid Storage"),
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

        override suspend fun evaluateLocalFallback(
            request: StrategyLocalFallbackRequest,
        ): ProviderOperationResult<StrategyLocalFallbackResult> {
            evaluateLocalFallbackCalls++
            return fallbackResult
        }
    }

    private companion object {
        fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies =
            RuntimeDependencies(
                clock = clock,
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds = generator { SynchronizationEventId("hybrid-event") },
                    queueEntryIds = generator { QueueEntryId("hybrid-queue-entry") },
                    queueLeaseIds = generator { QueueLeaseId("hybrid-queue-lease") },
                    conflictIds = generator { ConflictId("hybrid-conflict") },
                ),
            )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }
    }
}
