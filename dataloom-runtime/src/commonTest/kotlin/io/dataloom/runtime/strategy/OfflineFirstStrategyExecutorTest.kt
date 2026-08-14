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
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
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
import io.dataloom.api.strategy.StrategyReconciliationProvider
import io.dataloom.api.strategy.StrategyReconciliationRequest
import io.dataloom.api.strategy.StrategyReconciliationResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * Direct unit tests for [OfflineFirstStrategyExecutor].
 *
 * Covers every branch [BuiltInSynchronizationStrategyEvaluator] can produce
 * for [OfflineFirstStrategyProfile] with `requireDurableQueue = false`: PUSH,
 * PULL with local data served, PULL local-state mismatch, missing local
 * fallback provider, and reconciliation (success, missing provider, failure).
 * The durable-queue branch (`requireDurableQueue = true`, the profile
 * default) is verified to reject explicitly rather than silently misexecute.
 */
class OfflineFirstStrategyExecutorTest {

    private val now = DataLoomInstant(epochMilliseconds = 21_000L)
    private val clock = FixedDataLoomClock(now)
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()
    private val runtimeDependencies = runtimeDependencies(clock)

    // -------------------------------------------------------------------------
    // Durable-queue branch: explicit rejection, not silent misexecution
    // -------------------------------------------------------------------------

    @Test
    fun durableQueueIsExplicitlyRejectedNotSilentlyMisexecuted() = runTest {
        val storage = FakeOfflineFirstStorageProvider()
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = offlineFirstProfile(requireDurableQueue = true, reconcileWhenOnline = false),
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED, rejected.reason)
        assertEquals(0, storage.evaluateLocalFallbackCalls)
        assertEquals(0, storage.reconcileStrategyCalls)
    }

    @Test
    fun durableQueueIsAdmittedAndShortCircuitsTheSynchronousRemoteAttempt() = runTest {
        // requireDurableQueue = true + connectivity available means the plan
        // carries ENQUEUE_DURABLE_WORK alongside a full remote leg and
        // RECONCILE. Running both would risk a duplicate remote call once the
        // durably admitted continuation is later processed by a queue worker,
        // so admission replaces the synchronous attempt entirely.
        val storage = FakeOfflineFirstStorageProvider()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(outboundEventsRead = 1, outboundEventsAccepted = 1),
            )
        }
        val queue = FakeAdmissionQueueProvider()
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = offlineFirstProfile(requireDurableQueue = true, reconcileWhenOnline = true),
        )
        val result = executor(
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            durableQueueAdmitter = admitter(FakeEncoder()),
        ).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage, queue = queue),
        )
        val enqueued = assertIs<StrategySynchronizationExecutionResult.DurablyEnqueued>(result)
        assertEquals(QueueEntryId("offline-first-queue-entry"), enqueued.queueEntryId)
        assertEquals(1, queue.enqueueCalls)
        assertEquals(0, pipeline.executeCalls)
        assertEquals(0, storage.evaluateLocalFallbackCalls)
        assertEquals(0, storage.reconcileStrategyCalls)
    }

    @Test
    fun durableQueueAdmissionFailurePropagatesAsFailed() = runTest {
        val providerError = testError("ENQUEUE_UNAVAILABLE")
        val queue = FakeAdmissionQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(providerError),
        )
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = offlineFirstProfile(requireDurableQueue = true, reconcileWhenOnline = false),
        )
        val result = executor(durableQueueAdmitter = admitter(FakeEncoder())).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), FakeOfflineFirstStorageProvider(), queue = queue),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(providerError, failed.error)
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
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = false),
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), FakeOfflineFirstStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
        assertEquals(1, pipeline.executeCalls)
    }

    // -------------------------------------------------------------------------
    // PULL with local data served
    // -------------------------------------------------------------------------

    @Test
    fun pullWithLocalDataServesLocalThenSynchronizes() = runTest {
        val storage = FakeOfflineFirstStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(inboundEventsReceived = 3),
            )
        }
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = false),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
        assertEquals(1, storage.evaluateLocalFallbackCalls)
        assertEquals(1, pipeline.executeCalls)
    }

    @Test
    fun remoteSynchronizationCancellationPropagatesRatherThanBecomingAResult() = runTest {
        // #102 acceptance: "cancellation ... cannot be hidden by fallback."
        // Every offline-first plan always carries a remote leg (the evaluator
        // only reaches EXECUTE when connectivity is available), so a real
        // cancellation here must propagate out of execute() rather than be
        // converted into any Executed/Failed result — plausible here
        // specifically because local state was already served successfully
        // and could mask the remote leg's cancellation as if it never ran.
        val storage = FakeOfflineFirstStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val pipeline = FakePipeline(SynchronizationDirection.PULL) {
            throw CancellationException("remote synchronization cancelled")
        }
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = false),
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
    fun pullLocalStateMismatchWithEvidenceIsAContractError() = runTest {
        // Evidence said FRESH (that's how SERVE_LOCAL was admitted at all),
        // but the actual fallback provider reports nothing available -- a
        // genuine runtime inconsistency, not a normal branch.
        val storage = FakeOfflineFirstStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
        )
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = false),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals("DL-STRATEGY-OFFLINE-FIRST-LOCAL-STATE-MISMATCH", failed.error.code.value)
    }

    @Test
    fun missingFallbackProviderIsRejected() = runTest {
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = false),
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

    @Test
    fun remoteFailurePropagatesAndSkipsReconciliation() = runTest {
        val error = testError("PUSH_UNAVAILABLE")
        val storage = FakeOfflineFirstStorageProvider()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { context ->
            SynchronizationResult.Failed(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(),
                error = error,
            )
        }
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = true),
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(error, failed.error)
        assertEquals(0, storage.reconcileStrategyCalls)
    }

    // -------------------------------------------------------------------------
    // Reconciliation (profile.reconcileWhenOnline, the default)
    // -------------------------------------------------------------------------

    @Test
    fun reconciliationRunsAfterASuccessfulSynchronizationAndSucceeds() = runTest {
        val storage = FakeOfflineFirstStorageProvider(
            reconciliationResult = ProviderOperationResult.Success(StrategyReconciliationResult.Applied),
        )
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(outboundEventsRead = 1, outboundEventsAccepted = 1),
            )
        }
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = true),
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(1, storage.reconcileStrategyCalls)
        val evidence = storage.lastReconciliationRequest?.completedOperations
        assertEquals(false, evidence?.contains(io.dataloom.api.strategy.StrategyOperation.RECONCILE))
    }

    @Test
    fun reconciliationWithoutAConfiguredProviderIsRejected() = runTest {
        // A plain StorageProvider that does NOT implement StrategyReconciliationProvider.
        val storage = FakePlainStorageProvider()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(outboundEventsRead = 1, outboundEventsAccepted = 1),
            )
        }
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = true),
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.RECONCILIATION_PROVIDER_NOT_CONFIGURED,
            rejected.reason,
        )
    }

    @Test
    fun reconciliationFailurePropagatesAsAFailure() = runTest {
        val reconciliationError = testError("RECONCILE_UNAVAILABLE")
        val storage = FakeOfflineFirstStorageProvider(
            reconciliationResult = ProviderOperationResult.Failure(reconciliationError),
        )
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(outboundEventsRead = 1, outboundEventsAccepted = 1),
            )
        }
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.PUSH,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = true),
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(reconciliationError, failed.error)
    }

    // -------------------------------------------------------------------------
    // BIDIRECTIONAL
    // -------------------------------------------------------------------------

    @Test
    fun bidirectionalWithLocalDataServesAndSynchronizesBothWays() = runTest {
        val storage = FakeOfflineFirstStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val pipeline = FakePipeline(SynchronizationDirection.BIDIRECTIONAL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(inboundEventsReceived = 1, outboundEventsAccepted = 1, outboundEventsRead = 1),
            )
        }
        val request = offlineFirstRequest(
            direction = SynchronizationDirection.BIDIRECTIONAL,
            profile = offlineFirstProfile(requireDurableQueue = false, reconcileWhenOnline = false),
            cacheState = StrategyCacheState.STALE,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(1, storage.evaluateLocalFallbackCalls)
        assertEquals(1, pipeline.executeCalls)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun executor(
        pipelineRegistry: SynchronizationPipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
        durableQueueAdmitter: StrategyDurableQueueAdmitter? = null,
    ): OfflineFirstStrategyExecutor = OfflineFirstStrategyExecutor(
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

    private fun offlineFirstProfile(
        requireDurableQueue: Boolean = true,
        reconcileWhenOnline: Boolean = true,
    ): OfflineFirstStrategyProfile = OfflineFirstStrategyProfile(
        id = StrategyProfileId("offline-first-profile"),
        configurationVersion = StrategyConfigurationVersion(1L),
        requireDurableQueue = requireDurableQueue,
        reconcileWhenOnline = reconcileWhenOnline,
    )

    private fun offlineFirstRequest(
        direction: SynchronizationDirection,
        profile: OfflineFirstStrategyProfile,
        cacheState: StrategyCacheState = StrategyCacheState.NOT_EVALUATED,
        connectivity: StrategyConnectivity = StrategyConnectivity.AVAILABLE,
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("offline-first-workflow"),
            sessionId = SynchronizationSessionId("offline-first-session"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("offline-first-execution"),
                correlationId = CorrelationId("offline-first-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("offline-first-decision"),
        planId = StrategyPlanId("offline-first-plan"),
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

    private fun testError(code: String): DataLoomError = TestOfflineFirstError(ErrorCode(code))

    private data class TestOfflineFirstError(
        override val code: ErrorCode,
        override val message: String = "test offline-first failure",
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
            id = ProviderId("offline-first-transport"),
            name = ProviderName("Offline First Transport"),
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
            ProviderOperationResult.Failure(TestOfflineFirstError(ErrorCode("PUSH_UNUSED")))

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(TestOfflineFirstError(ErrorCode("PULL_UNUSED")))
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
            id = ProviderId("offline-first-queue"),
            name = ProviderName("Offline First Queue"),
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

    /** Plain [StorageProvider] with none of the optional strategy capabilities. */
    private open class FakePlainStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("offline-first-storage"),
            name = ProviderName("Offline First Storage"),
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

    /** Implements both optional strategy storage capabilities offline-first needs. */
    private class FakeOfflineFirstStorageProvider(
        private val fallbackResult: ProviderOperationResult<StrategyLocalFallbackResult> =
            ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
        private val reconciliationResult: ProviderOperationResult<StrategyReconciliationResult> =
            ProviderOperationResult.Success(StrategyReconciliationResult.Applied),
    ) : FakePlainStorageProvider(), StrategyLocalFallbackProvider, StrategyReconciliationProvider {
        var evaluateLocalFallbackCalls: Int = 0
            private set
        var reconcileStrategyCalls: Int = 0
            private set
        var lastReconciliationRequest: StrategyReconciliationRequest? = null
            private set

        override suspend fun evaluateLocalFallback(
            request: StrategyLocalFallbackRequest,
        ): ProviderOperationResult<StrategyLocalFallbackResult> {
            evaluateLocalFallbackCalls++
            return fallbackResult
        }

        override suspend fun reconcileStrategy(
            request: StrategyReconciliationRequest,
        ): ProviderOperationResult<StrategyReconciliationResult> {
            reconcileStrategyCalls++
            lastReconciliationRequest = request
            return reconciliationResult
        }
    }

    private companion object {
        fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies =
            RuntimeDependencies(
                clock = clock,
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds = generator { SynchronizationEventId("offline-first-event") },
                    queueEntryIds = generator { QueueEntryId("offline-first-queue-entry") },
                    queueLeaseIds = generator { QueueLeaseId("offline-first-queue-lease") },
                    conflictIds = generator { ConflictId("offline-first-conflict") },
                ),
            )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }
    }
}
