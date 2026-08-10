package io.dataloom.runtime.strategy

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
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
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRemoteOutcome
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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Direct unit tests for [RemoteFirstStrategyExecutor].
 *
 * Previously this class had zero test coverage anywhere in the suite — the
 * only existing exercise of remote-first strategy execution was two
 * provider-protection-focused tests in `DataLoomBuilderProtectedStrategyTest`
 * covering a single fallback scenario. This file covers the SERVE_LOCAL fast
 * path, the transport-only (non-persisting) PULL branch, the non-persisting
 * BIDIRECTIONAL branch, and the provider-backed pipeline branch, directly
 * against the executor.
 *
 * Real pipelines are out of scope here (they have their own coverage
 * elsewhere); a minimal [FakePipeline] test double stands in so these tests
 * isolate the executor's own dispatch and result-mapping logic rather than
 * the pipeline internals.
 */
class RemoteFirstStrategyExecutorTest {

    private val now = DataLoomInstant(epochMilliseconds = 7_000L)
    private val clock = FixedDataLoomClock(now)
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()
    private val runtimeDependencies = runtimeDependencies(clock)

    private val changeSet = ChangeSet(
        id = ChangeSetId("remote-first-change-set"),
        events = listOf(
            ChangeEvent(
                id = ChangeEventId("remote-first-event-1"),
                entity = EntityReference(
                    type = EntityType("remote-first-entity"),
                    id = EntityId("remote-first-entity-1"),
                ),
                operation = ChangeOperation.CREATE,
            ),
        ),
    )

    // -------------------------------------------------------------------------
    // SERVE_LOCAL fast path (connectivity/transport unavailable, cache available)
    // -------------------------------------------------------------------------

    @Test
    fun serveLocalFastPathWithAvailableCacheActivatesFallbackWithoutAttemptingRemote() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val transport = FakeTransportProvider()
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE)),
            connectivity = StrategyConnectivity.UNAVAILABLE,
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, storage),
        )
        val activated = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, activated.remoteOutcome)
        assertTrue(!activated.remoteAttempted)
        assertNull(activated.primaryError)
        assertEquals(StrategyCacheState.FRESH, activated.cacheState)
        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), activated.completedOperations)
        assertEquals(1, storage.evaluateLocalFallbackCalls)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, transport.pushCalls)
    }

    @Test
    fun serveLocalFastPathWithNoLocalDataReturnsFallbackUnavailable() = runTest {
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
        )
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE)),
            connectivity = StrategyConnectivity.UNAVAILABLE,
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), storage),
        )
        val unavailable = assertIs<StrategySynchronizationExecutionResult.FallbackUnavailable>(result)
        assertTrue(!unavailable.remoteAttempted)
        assertIs<StrategyLocalFallbackResult.Unavailable>(unavailable.localResult)
    }

    @Test
    fun serveLocalFastPathWithoutFallbackProviderIsRejected() = runTest {
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE)),
            connectivity = StrategyConnectivity.UNAVAILABLE,
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
    // Transport-only PULL (non-persisting)
    // -------------------------------------------------------------------------

    @Test
    fun transportOnlyPullSucceeds() = runTest {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(persistRemoteResult = false),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, FakeFallbackStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertIs<PullChangesResult.NoChanges>(output.result)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun transportOnlyPullFailureActivatesAllowlistedFallback() = runTest {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Failure(
                RemoteFailure(StrategyRemoteOutcome.UNAVAILABLE),
            ),
        )
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(
                fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                persistRemoteResult = false,
            ),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, storage),
        )
        val activated = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertTrue(activated.remoteAttempted)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, activated.remoteOutcome)
        assertIs<RemoteFailure>(activated.primaryError)
    }

    @Test
    fun transportOnlyPullFailureWithoutMatchingFallbackFails() = runTest {
        val error = RemoteFailure(StrategyRemoteOutcome.UNAVAILABLE)
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Failure(error),
        )
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(persistRemoteResult = false),
        )
        val result = executor().execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, storage = null),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(error, failed.error)
        assertTrue(failed.transportAttempted)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, failed.remoteOutcome)
        assertTrue(!failed.fallbackAttempted)
    }

    // -------------------------------------------------------------------------
    // Non-persisting BIDIRECTIONAL
    // -------------------------------------------------------------------------

    @Test
    fun nonPersistingBidirectionalWithBothOperationsSucceedingReturnsCombinedOutput() = runTest {
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(
                io.dataloom.api.synchronization.ChangeSetAcknowledgement(
                    changeSetId = changeSet.id,
                    events = changeSet.events.map {
                        io.dataloom.api.synchronization.ChangeEventAcknowledgement(
                            it.id,
                            io.dataloom.api.synchronization.ChangeAcknowledgementStatus.ACCEPTED,
                        )
                    },
                ),
            ),
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.BIDIRECTIONAL,
            profile = remoteFirstProfile(persistRemoteResult = false),
        )
        val pipelineRegistry = SynchronizationPipelineRegistry(
            listOf(pushPipelineDelegatingToTransport()),
        )
        val result = executor(pipelineRegistry).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, FakeFallbackStorageProvider()),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.RemoteFirstBidirectional>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.pushResult)
        assertIs<PullChangesResult.NoChanges>(output.pullResult)
        assertEquals(1, transport.pushCalls)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun nonPersistingBidirectionalWhenPushFailsDoesNotAttemptPullOrFallback() = runTest {
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Failure(testError("PUSH_UNAVAILABLE")),
        )
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.BIDIRECTIONAL,
            profile = remoteFirstProfile(
                fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                persistRemoteResult = false,
            ),
        )
        val pipelineRegistry = SynchronizationPipelineRegistry(
            listOf(pushPipelineDelegatingToTransport()),
        )
        val result = executor(pipelineRegistry).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, FakeFallbackStorageProvider()),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertTrue(!failed.fallbackAttempted)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun nonPersistingBidirectionalWhenPullFailsAfterSuccessfulPushActivatesFallback() = runTest {
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(
                io.dataloom.api.synchronization.ChangeSetAcknowledgement(
                    changeSetId = changeSet.id,
                    events = changeSet.events.map {
                        io.dataloom.api.synchronization.ChangeEventAcknowledgement(
                            it.id,
                            io.dataloom.api.synchronization.ChangeAcknowledgementStatus.ACCEPTED,
                        )
                    },
                ),
            ),
            pullResult = ProviderOperationResult.Failure(
                RemoteFailure(StrategyRemoteOutcome.UNAVAILABLE),
            ),
        )
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.BIDIRECTIONAL,
            profile = remoteFirstProfile(
                fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                persistRemoteResult = false,
            ),
            cacheState = StrategyCacheState.FRESH,
        )
        val pipelineRegistry = SynchronizationPipelineRegistry(
            listOf(pushPipelineDelegatingToTransport()),
        )
        val result = executor(pipelineRegistry).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, storage),
        )
        val activated = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertTrue(activated.remoteAttempted)
        assertEquals(
            listOf(StrategyOperation.PUSH_REMOTE, StrategyOperation.SERVE_LOCAL),
            activated.completedOperations,
        )
        val partial = assertIs<StrategyTransportOutput.ProviderBacked>(activated.partialOutput)
        assertIs<SynchronizationResult.Succeeded>(partial.result)
    }

    // -------------------------------------------------------------------------
    // Provider-backed pipeline (persisting)
    // -------------------------------------------------------------------------

    @Test
    fun providerBackedPipelineSuccessIsExecuted() = runTest {
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(inboundEventsReceived = 3),
            )
        }
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(),
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
    fun providerBackedPipelineRemotePullFailureActivatesFallback() = runTest {
        val remoteError = RemoteFailure(StrategyRemoteOutcome.UNAVAILABLE)
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            val pulled = context.providers.transportProvider.pullChanges(
                PullChangesRequest(request = context.request),
            )
            check(pulled is ProviderOperationResult.Failure)
            SynchronizationResult.Failed(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(),
                error = pulled.error,
            )
        }
        val storage = FakeFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Failure(remoteError),
        )
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE)),
            cacheState = StrategyCacheState.FRESH,
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(transport, storage),
        )
        val activated = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertTrue(activated.remoteAttempted)
        assertEquals(remoteError, activated.primaryError)
        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), activated.completedOperations)
        val partial = assertIs<StrategyTransportOutput.ProviderBacked>(activated.partialOutput)
        assertIs<SynchronizationResult.Failed>(partial.result)
    }

    @Test
    fun providerBackedPipelineUnrelatedFailureDoesNotActivateFallback() = runTest {
        val storageError = testError("STORAGE_UNAVAILABLE")
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Failed(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(),
                error = storageError,
            )
        }
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(),
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), FakeFallbackStorageProvider()),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(storageError, failed.error)
        assertTrue(!failed.transportAttempted)
    }

    @Test
    fun providerBackedPipelineCancellationIsPreserved() = runTest {
        val pipeline = FakePipeline(SynchronizationDirection.PULL) { context ->
            SynchronizationResult.Cancelled(
                request = context.request,
                completedAt = now,
                summary = SynchronizationSummary(),
            )
        }
        val request = remoteFirstRequest(
            direction = SynchronizationDirection.PULL,
            profile = remoteFirstProfile(),
        )
        val result = executor(SynchronizationPipelineRegistry(listOf(pipeline))).execute(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(FakeTransportProvider(), FakeFallbackStorageProvider()),
        )
        val cancelled = assertIs<StrategySynchronizationExecutionResult.Cancelled>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(cancelled.output)
        assertIs<SynchronizationResult.Cancelled>(output.result)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun executor(
        pipelineRegistry: SynchronizationPipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
    ): RemoteFirstStrategyExecutor = RemoteFirstStrategyExecutor(
        clock = clock,
        runtimeDependencies = runtimeDependencies,
        pipelineRegistry = pipelineRegistry,
        lifecycleEventEmitter = null,
    )

    private fun pushPipelineDelegatingToTransport(): SynchronizationPipeline =
        FakePipeline(SynchronizationDirection.PUSH) { context ->
            val pushed = context.providers.transportProvider.pushChanges(
                PushChangesRequest(request = context.request, changeSet = changeSet),
            )
            when (pushed) {
                is ProviderOperationResult.Success -> SynchronizationResult.Succeeded(
                    request = context.request,
                    completedAt = now,
                    summary = SynchronizationSummary(outboundEventsRead = 1, outboundEventsAccepted = 1),
                )
                is ProviderOperationResult.Failure -> SynchronizationResult.Failed(
                    request = context.request,
                    completedAt = now,
                    summary = SynchronizationSummary(),
                    error = pushed.error,
                )
            }
        }

    private fun remoteFirstProfile(
        fallbackOn: Set<StrategyRemoteOutcome> = emptySet(),
        persistRemoteResult: Boolean = true,
    ): RemoteFirstStrategyProfile = RemoteFirstStrategyProfile(
        id = StrategyProfileId("remote-first-profile"),
        configurationVersion = io.dataloom.api.strategy.StrategyConfigurationVersion(1L),
        fallbackOn = fallbackOn,
        persistRemoteResult = persistRemoteResult,
    )

    private fun remoteFirstRequest(
        direction: SynchronizationDirection,
        profile: RemoteFirstStrategyProfile,
        connectivity: StrategyConnectivity = StrategyConnectivity.AVAILABLE,
        transportHealth: StrategyProviderHealth = StrategyProviderHealth.HEALTHY,
        cacheState: StrategyCacheState = StrategyCacheState.NOT_EVALUATED,
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("remote-first-workflow"),
            sessionId = SynchronizationSessionId("remote-first-session"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("remote-first-execution"),
                correlationId = CorrelationId("remote-first-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("remote-first-decision"),
        planId = StrategyPlanId("remote-first-plan"),
        profile = profile,
        evidence = StrategyRuntimeEvidence(
            connectivity = connectivity,
            cacheState = cacheState,
            transportHealth = transportHealth,
        ),
        input = StrategyOperationInput.ProviderBacked,
    )

    private fun evaluationFor(request: StrategySynchronizationRequest) =
        evaluator.evaluate(request.evaluationRequest())

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

    private fun testError(code: String): DataLoomError = TestRemoteError(ErrorCode(code))

    private data class TestRemoteError(
        override val code: ErrorCode,
        override val message: String = "test remote failure",
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class RemoteFailure(
        override val remoteOutcome: StrategyRemoteOutcome,
        override val code: ErrorCode = ErrorCode("REMOTE_UNAVAILABLE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Remote dependency unavailable.",
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError

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
            id = ProviderId("remote-first-transport"),
            name = ProviderName("Remote First Transport"),
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
            id = ProviderId("remote-first-storage"),
            name = ProviderName("Remote First Storage"),
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
                    synchronizationEventIds = generator {
                        SynchronizationEventId("remote-first-event")
                    },
                    queueEntryIds = generator {
                        QueueEntryId("remote-first-queue-entry")
                    },
                    queueLeaseIds = generator {
                        QueueLeaseId("remote-first-queue-lease")
                    },
                    conflictIds = generator {
                        ConflictId("remote-first-conflict")
                    },
                ),
            )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }
    }
}
