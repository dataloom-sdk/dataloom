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
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.StaleCachePolicy
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * Direct unit tests for [CacheFirstStrategyExecutor].
 *
 * Covers every branch [BuiltInSynchronizationStrategyEvaluator] can produce
 * for [CacheFirstStrategyProfile] that this executor is scoped to handle
 * directly: serve-only (fresh/stale, no refresh), serve-with-synchronous-refresh
 * (`requireDurableRefresh = false`), missing-cache remote fetch, and PUSH.
 * The durable-refresh branch (`requireDurableRefresh = true`, the profile
 * default) is verified to reject explicitly rather than silently misexecute.
 */
class CacheFirstStrategyExecutorTest {

    private val now = DataLoomInstant(epochMilliseconds = 11_000L)
    private val clock = FixedDataLoomClock(now)
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()
    private val runtimeDependencies = runtimeDependencies(clock)

    // -------------------------------------------------------------------------
    // Serve-only (no refresh)
    // -------------------------------------------------------------------------

    @Test
    fun freshCacheWithNoRefreshIsServedFromCache() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(refreshOnFreshHit = false),
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
    fun staleCacheWithServeStalePolicyIsServedFromCache() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(staleCachePolicy = StaleCachePolicy.SERVE_STALE),
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
    fun localStateMismatchWithEvidenceIsAContractError() = runTest {
        // Evidence said FRESH (that's how this operation set was admitted at
        // all), but the actual fallback provider reports nothing available --
        // a genuine runtime inconsistency, not a normal branch.
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
        )
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(refreshOnFreshHit = false),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals("DL-STRATEGY-CACHE-FIRST-LOCAL-STATE-MISMATCH", failed.error.code.value)
    }

    @Test
    fun missingFallbackProviderIsRejected() = runTest {
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(refreshOnFreshHit = false),
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
    // Serve with a synchronous (non-durable) refresh
    // -------------------------------------------------------------------------

    @Test
    fun freshCacheWithSynchronousRefreshServesAndRefreshes() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(inboundEventsReceived = 2),
            )
        }
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(refreshOnFreshHit = true, requireDurableRefresh = false),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val served = assertIs<StrategySynchronizationExecutionResult.ServedFromCache>(result)
        assertEquals(StrategyCacheState.FRESH, served.cacheState)
        val refresh = assertIs<StrategyTransportOutput.ProviderBacked>(served.refreshOutput)
        assertIs<SynchronizationResult.Succeeded>(refresh.result)
        assertEquals(1, pipeline.executeCalls)
    }

    @Test
    fun synchronousRefreshCancellationPropagatesRatherThanBecomingAServedResult() = runTest {
        // #102 acceptance: "cancellation ... cannot be hidden by fallback."
        // A real kotlinx.coroutines.CancellationException thrown by the
        // registered refresh pipeline must propagate out of execute(), not
        // be converted into a ServedFromCache result (which would otherwise
        // be plausible here — the cache is already available and could
        // "successfully" serve while silently swallowing the cancellation).
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val pipeline = FakePipeline(SynchronizationDirection.PULL) {
            throw CancellationException("refresh cancelled")
        }
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(refreshOnFreshHit = true, requireDurableRefresh = false),
            cacheState = StrategyCacheState.FRESH,
        )

        assertFailsWith<CancellationException> {
            executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
                request = request,
                evaluation = evaluationFor(request),
                providers = providerSet(FakeTransportProvider(), storage),
            )
        }
    }

    @Test
    fun staleCacheWithSynchronousRefreshFailurePropagatesTheFailure() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val refreshError = testError("REFRESH_UNAVAILABLE")
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Failed(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(),
                error = refreshError,
            )
        }
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(
                staleCachePolicy = StaleCachePolicy.SERVE_STALE_AND_REFRESH,
                requireDurableRefresh = false,
            ),
            cacheState = StrategyCacheState.STALE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        // The refresh failing means the whole operation fails -- cache-first
        // has no fallback-on-refresh-failure semantics of its own (unlike
        // remote-first's typed fallback allowlist); local state was already
        // evaluated as available, but the plan's remote leg still failed.
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(refreshError, failed.error)
    }

    @Test
    fun durableRefreshIsExplicitlyRejectedNotSilentlyMisexecuted() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(refreshOnFreshHit = true, requireDurableRefresh = true),
            cacheState = StrategyCacheState.FRESH,
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
    fun durableRefreshIsAdmittedAndStillServesLocalStateSynchronously() = runTest {
        // Unlike offline-first, cache-first's durable branch never also
        // carries a remote leg in the same plan -- so admitting the refresh
        // durably does not replace serving local state, it happens alongside it.
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val queue = FakeAdmissionQueueProvider()
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(refreshOnFreshHit = true, requireDurableRefresh = true),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor(durableQueueAdmitter = admitter(FakeEncoder())).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage, queue = queue),
        )
        val served = assertIs<StrategySynchronizationExecutionResult.ServedFromCache>(result)
        assertEquals(StrategyCacheState.FRESH, served.cacheState)
        assertEquals(QueueEntryId("cache-first-queue-entry"), served.durableQueueEntryId)
        assertEquals(1, storage.evaluateLocalFallbackCalls)
        assertEquals(1, queue.enqueueCalls)
    }

    @Test
    fun durableRefreshAdmissionFailurePropagatesAsFailed() = runTest {
        val providerError = testError("ENQUEUE_UNAVAILABLE")
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val queue = FakeAdmissionQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(providerError),
        )
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(refreshOnFreshHit = true, requireDurableRefresh = true),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor(durableQueueAdmitter = admitter(FakeEncoder())).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage, queue = queue),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(providerError, failed.error)
        // Admission failed before local state was ever served.
        assertEquals(0, storage.evaluateLocalFallbackCalls)
    }

    // -------------------------------------------------------------------------
    // Missing cache -- pure remote fetch, no local serve
    // -------------------------------------------------------------------------

    @Test
    fun missingCacheWithConnectivityFetchesRemoteWithoutServingLocal() = runTest {
        val storage = FakeFallbackStorageProvider()
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(inboundEventsReceived = 5),
            )
        }
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(),
            cacheState = StrategyCacheState.MISSING,
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
        assertEquals(0, storage.evaluateLocalFallbackCalls)
    }

    @Test
    fun missingCacheRemoteFetchFailurePropagates() = runTest {
        val error = testError("PULL_UNAVAILABLE")
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Failed(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(),
                error = error,
            )
        }
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = cacheFirstProfile(),
            cacheState = StrategyCacheState.MISSING,
            connectivity = StrategyConnectivity.AVAILABLE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), FakeFallbackStorageProvider()),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(error, failed.error)
    }

    // -------------------------------------------------------------------------
    // PUSH
    // -------------------------------------------------------------------------

    @Test
    fun pushWithConnectivitySucceeds() = runTest {
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(outboundEventsRead = 1, outboundEventsAccepted = 1),
            )
        }
        val request = cacheFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = cacheFirstProfile(),
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

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun executor(
        pipelineRegistry: SynchronizationPipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
        durableQueueAdmitter: StrategyDurableQueueAdmitter? = null,
    ): CacheFirstStrategyExecutor = CacheFirstStrategyExecutor(
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

    private fun cacheFirstProfile(
        staleCachePolicy: StaleCachePolicy = StaleCachePolicy.SERVE_STALE_AND_REFRESH,
        refreshOnFreshHit: Boolean = false,
        requireDurableRefresh: Boolean = true,
    ): CacheFirstStrategyProfile = CacheFirstStrategyProfile(
        id = StrategyProfileId("cache-first-profile"),
        configurationVersion = StrategyConfigurationVersion(1L),
        staleCachePolicy = staleCachePolicy,
        refreshOnFreshHit = refreshOnFreshHit,
        requireDurableRefresh = requireDurableRefresh,
    )

    private fun cacheFirstRequest(
        direction: SynchronizationDirection,
        profile: CacheFirstStrategyProfile,
        cacheState: StrategyCacheState = StrategyCacheState.NOT_EVALUATED,
        connectivity: StrategyConnectivity = StrategyConnectivity.AVAILABLE,
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("cache-first-workflow"),
            sessionId = SynchronizationSessionId("cache-first-session"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("cache-first-execution"),
                correlationId = CorrelationId("cache-first-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("cache-first-decision"),
        planId = StrategyPlanId("cache-first-plan"),
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

    private fun testError(code: String): DataLoomError = TestCacheFirstError(ErrorCode(code))

    private data class TestCacheFirstError(
        override val code: ErrorCode,
        override val message: String = "test cache-first failure",
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

    private class FakeTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cache-first-transport"),
            name = ProviderName("Cache First Transport"),
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
        ): ProviderOperationResult<io.dataloom.api.synchronization.ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(testErrorStatic("PUSH_UNUSED"))

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(testErrorStatic("PULL_UNUSED"))

        private fun testErrorStatic(code: String): DataLoomError = TestCacheFirstError(ErrorCode(code))
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
            id = ProviderId("cache-first-queue"),
            name = ProviderName("Cache First Queue"),
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

    private class FakeFallbackStorageProvider(
        private val fallbackResult: ProviderOperationResult<StrategyLocalFallbackResult> =
            ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
    ) : StrategyLocalFallbackProvider {
        var evaluateLocalFallbackCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cache-first-storage"),
            name = ProviderName("Cache First Storage"),
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
                    synchronizationEventIds = generator { SynchronizationEventId("cache-first-event") },
                    queueEntryIds = generator { QueueEntryId("cache-first-queue-entry") },
                    queueLeaseIds = generator { QueueLeaseId("cache-first-queue-lease") },
                    conflictIds = generator { ConflictId("cache-first-conflict") },
                ),
            )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }
    }
}
