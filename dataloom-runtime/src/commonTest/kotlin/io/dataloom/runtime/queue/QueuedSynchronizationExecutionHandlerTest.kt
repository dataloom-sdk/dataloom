package io.dataloom.runtime.queue

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
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.core.provider.SynchronizationProviderResolver
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationExecutionCoordinator
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deterministic common tests for [QueuedSynchronizationExecutionHandler].
 *
 * All fakes are stateless or deterministically stateful. No real queue
 * provider, real network, real database, filesystem, Thread.sleep, arbitrary
 * delay, Android APIs, JVM-only APIs, reflection, ServiceLoader, system clock,
 * random identifiers, or production credentials are used.
 *
 * Suspend functions are exercised using [kotlin.coroutines.startCoroutine]
 * primitives from the Kotlin standard library, without requiring
 * kotlinx.coroutines.
 */
class QueuedSynchronizationExecutionHandlerTest {

    // =========================================================================
    // runSuspend helper
    // =========================================================================

    private object Pending

    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) {
                        rawResult = result.getOrNull()
                    } else {
                        thrown = result.exceptionOrNull()
                    }
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }

    // =========================================================================
    // Shared test fixtures
    // =========================================================================

    private val t0 = DataLoomInstant(epochMilliseconds = 1_000_000L)
    private val t1 = DataLoomInstant(epochMilliseconds = 2_000_000L)
    private val handlerClock = FixedClock(t0)
    private val retryOp = RetryOperation("sync.queued")
    private val initContext = ProviderInitializationContext()

    private val sampleContext = ExecutionContext(
        executionId = ExecutionId("exec-001"),
        correlationId = CorrelationId("corr-001"),
    )

    private val sampleRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = sampleContext,
    )

    // Empty summary uses all defaults.
    private val emptySummary = SynchronizationSummary()

    // =========================================================================
    // Fake clock (FixedDataLoomClock cannot be used in dataloom-runtime tests)
    // =========================================================================

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    // =========================================================================
    // Fake errors
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class FakeResolutionError(
        override val code: ErrorCode = ErrorCode("DL-RESOLVE-001"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Work resolution failed.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Fake StorageProvider
    // =========================================================================

    private class FakeStorageProvider(id: String = "storage-primary") : StorageProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Fake Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    // =========================================================================
    // Fake TransportProvider
    // =========================================================================

    private class FakeTransportProvider(id: String = "transport-prod") : TransportProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Fake Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }

    // =========================================================================
    // Fake SynchronizationPipeline
    // =========================================================================

    private class ControlledPipeline(
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        private val resultFn: (SynchronizationExecutionContext) -> SynchronizationResult,
    ) : SynchronizationPipeline {
        var executeCallCount: Int = 0

        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            executeCallCount++
            return resultFn(context)
        }
    }

    // =========================================================================
    // Fake RetryPolicy
    // =========================================================================

    private class AlwaysRetryPolicy(
        private val delay: SchedulingDelay = SchedulingDelay(1000L),
    ) : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("always-retry")
        val capturedRequests: MutableList<RetryEvaluationRequest> = mutableListOf()

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            capturedRequests.add(request)
            return RetryDecision.Retry(delay = delay)
        }
    }

    private class AlwaysStopPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("always-stop")

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeStorage(id: String = "storage-primary") = FakeStorageProvider(id)
    private fun makeTransport(id: String = "transport-prod") = FakeTransportProvider(id)

    private fun makeRegistry(vararg providers: DataLoomProvider): ProviderRegistry =
        ProviderRegistry(providers.toList())

    private fun makeResolver(vararg providers: DataLoomProvider): SynchronizationProviderResolver =
        SynchronizationProviderResolver(makeRegistry(*providers))

    private fun makeRuntimeDeps(clock: DataLoomClock = handlerClock): RuntimeDependencies {
        val ids = RuntimeIdentifierGenerators(
            synchronizationEventIds = object : IdentifierGenerator<SynchronizationEventId> {
                override fun generate() = SynchronizationEventId("event-001")
            },
            queueEntryIds = object : IdentifierGenerator<QueueEntryId> {
                override fun generate() = QueueEntryId("entry-001")
            },
            queueLeaseIds = object : IdentifierGenerator<QueueLeaseId> {
                override fun generate() = QueueLeaseId("lease-001")
            },
            conflictIds = object : IdentifierGenerator<ConflictId> {
                override fun generate() = ConflictId("conflict-001")
            },
        )
        return RuntimeDependencies(clock = clock, identifiers = ids)
    }

    private fun makeInitializedLifecycle(
        storage: FakeStorageProvider = makeStorage(),
        transport: FakeTransportProvider = makeTransport(),
    ): ProviderLifecycleCoordinator {
        val registry = makeRegistry(storage, transport)
        val coordinator = ProviderLifecycleCoordinator(registry, initContext)
        runSuspend { coordinator.initialize() }
        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, coordinator.state)
        return coordinator
    }

    private fun makeBindings(
        storage: FakeStorageProvider = makeStorage(),
        transport: FakeTransportProvider = makeTransport(),
    ) = SynchronizationProviderBindings(
        storageProviderId = storage.descriptor.id,
        transportProviderId = transport.descriptor.id,
    )

    /**
     * Builds a fully wired [SynchronizationExecutionCoordinator] with the
     * given pipeline, and returns it together with the matching bindings.
     */
    private fun makeCoordinatorWithPipeline(
        pipeline: ControlledPipeline,
    ): Pair<SynchronizationExecutionCoordinator, SynchronizationProviderBindings> {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedLifecycle(storage, transport)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDeps(),
        )
        val bindings = makeBindings(storage, transport)
        return coordinator to bindings
    }

    /** Coordinator that always returns [result] from the pipeline. */
    private fun coordinatorReturning(
        result: SynchronizationResult,
    ): Pair<SynchronizationExecutionCoordinator, SynchronizationProviderBindings> =
        makeCoordinatorWithPipeline(ControlledPipeline { result })

    private fun makeRetryEvaluator(
        policy: RetryPolicy = AlwaysStopPolicy(),
        clock: DataLoomClock = handlerClock,
    ) = SynchronizationRetryEvaluator(retryPolicy = policy, clock = clock)

    /**
     * Builds a [QueuedSynchronizationExecutionHandler] that resolves the
     * given [bindings] for any entry and uses the coordinator as-is.
     */
    private fun makeHandler(
        coordinator: SynchronizationExecutionCoordinator,
        bindings: SynchronizationProviderBindings,
        retryPolicy: RetryPolicy = AlwaysStopPolicy(),
        clock: DataLoomClock = handlerClock,
    ): QueuedSynchronizationExecutionHandler {
        val resolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(request = entry.synchronizationRequest, bindings = bindings),
            )
        }
        return QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(policy = retryPolicy, clock = clock),
            retryOperation = retryOp,
        )
    }

    private fun makeLeasedEntry(
        request: SynchronizationRequest = sampleRequest,
        retryAttempt: RetryAttempt? = null,
        strategyDecision: PersistedStrategyDecision? = null,
    ): QueueEntry {
        val lease = QueueLease(
            id = QueueLeaseId("lease-001"),
            consumerId = QueueConsumerId("consumer-001"),
            acquiredAt = t0,
            expiresAt = t1,
        )
        return QueueEntry(
            id = QueueEntryId("entry-001"),
            synchronizationRequest = request,
            state = QueueEntryState.LEASED,
            enqueuedAt = t0,
            availableAt = t0,
            lease = lease,
            retryAttempt = retryAttempt,
            strategyDecision = strategyDecision,
        )
    }

    private fun strategyDecision(
        version: Long,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-queued-1"),
        planId = StrategyPlanId("plan-queued-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(version),
        disposition = StrategyDisposition.DEFER,
    )

    private fun makeSucceededResult() = SynchronizationResult.Succeeded(
        request = sampleRequest,
        completedAt = t1,
        summary = emptySummary,
    )

    private fun makeSkippedResult() = SynchronizationResult.Skipped(
        request = sampleRequest,
        completedAt = t1,
        summary = emptySummary,
        reason = SynchronizationSkipReason.NO_CHANGES,
    )

    private fun makeCancelledResult() = SynchronizationResult.Cancelled(
        request = sampleRequest,
        completedAt = t1,
        summary = emptySummary,
    )

    private fun makeFailedResult(error: DataLoomError = FakeError()) = SynchronizationResult.Failed(
        request = sampleRequest,
        completedAt = t1,
        summary = emptySummary,
        error = error,
    )

    private fun makePartialResult(error: DataLoomError = FakeError()) =
        SynchronizationResult.PartiallySucceeded(
            request = sampleRequest,
            completedAt = t1,
            summary = emptySummary,
            errors = listOf(error),
        )

    // =========================================================================
    // Work resolution rejection → Failed outcome
    // =========================================================================

    @Test
    fun `resolver Rejected returns Failed outcome`() {
        val resolver = QueuedSynchronizationWorkResolver { _ ->
            QueuedSynchronizationWorkResolution.Rejected(error = FakeResolutionError())
        }
        val (coordinator, _) = coordinatorReturning(makeSucceededResult())
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
    }

    @Test
    fun `resolver Rejected preserves the exact canonical error`() {
        val rejectionError = FakeResolutionError()
        val resolver = QueuedSynchronizationWorkResolver { _ ->
            QueuedSynchronizationWorkResolution.Rejected(error = rejectionError)
        }
        val (coordinator, _) = coordinatorReturning(makeSucceededResult())
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Failed
        assertEquals(rejectionError, outcome.error)
    }

    @Test
    fun `resolver Rejected uses FAILED disposition`() {
        val resolver = QueuedSynchronizationWorkResolver { _ ->
            QueuedSynchronizationWorkResolution.Rejected(error = FakeResolutionError())
        }
        val (coordinator, _) = coordinatorReturning(makeSucceededResult())
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Failed
        assertEquals(QueueFailureDisposition.FAILED, outcome.disposition)
    }

    @Test
    fun `changed resolved strategy decision fails before pipeline invocation`() {
        val pipeline = ControlledPipeline { makeSucceededResult() }
        val (coordinator, bindings) = makeCoordinatorWithPipeline(pipeline)
        val resolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = entry.synchronizationRequest,
                    bindings = bindings,
                    strategyDecision = strategyDecision(version = 4L),
                ),
            )
        }
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )

        val outcome = runSuspend {
            handler.execute(
                makeLeasedEntry(strategyDecision = strategyDecision(version = 3L)),
            )
        }

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals("DL-Q-STRATEGY-DECISION-MISMATCH", failed.error.code.value)
        assertEquals(ErrorCategory.CONFIGURATION, failed.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failed.error.recoverability)
        assertEquals(0, pipeline.executeCallCount)
    }

    // =========================================================================
    // Coordinator structural rejection → Failed outcome
    // =========================================================================

    @Test
    fun `coordinator Rejected due to uninitialized providers returns Failed outcome`() {
        val storage = makeStorage()
        val transport = makeTransport()
        // Lifecycle is NOT initialized — will cause Rejected(PROVIDERS_NOT_INITIALIZED)
        val lifecycle = ProviderLifecycleCoordinator(makeRegistry(storage, transport), initContext)
        assertEquals(ProviderLifecycleCoordinatorState.NOT_INITIALIZED, lifecycle.state)

        val pipeline = ControlledPipeline { makeSucceededResult() }
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDeps(),
        )
        val bindings = makeBindings(storage, transport)
        val work = QueuedSynchronizationWork(request = sampleRequest, bindings = bindings)
        val resolver = QueuedSynchronizationWorkResolver { _ ->
            QueuedSynchronizationWorkResolution.Resolved(work)
        }
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )

        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
    }

    @Test
    fun `coordinator Rejected uses FAILED disposition`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = ProviderLifecycleCoordinator(makeRegistry(storage, transport), initContext)
        val pipeline = ControlledPipeline { makeSucceededResult() }
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDeps(),
        )
        val bindings = makeBindings(storage, transport)
        val work = QueuedSynchronizationWork(request = sampleRequest, bindings = bindings)
        val resolver = QueuedSynchronizationWorkResolver { _ ->
            QueuedSynchronizationWorkResolution.Resolved(work)
        }
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )

        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Failed
        assertEquals(QueueFailureDisposition.FAILED, outcome.disposition)
    }

    // =========================================================================
    // Succeeded result → Completed outcome
    // =========================================================================

    @Test
    fun `Succeeded result returns Completed outcome`() {
        val (coordinator, bindings) = coordinatorReturning(makeSucceededResult())
        val handler = makeHandler(coordinator = coordinator, bindings = bindings)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Completed>(outcome)
    }

    @Test
    fun `Succeeded result completedAt equals result completedAt`() {
        val (coordinator, bindings) = coordinatorReturning(makeSucceededResult())
        val handler = makeHandler(coordinator = coordinator, bindings = bindings)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Completed
        // completedAt is taken from SynchronizationResult.completedAt, not from the clock
        assertEquals(t1, outcome.completedAt)
    }

    // =========================================================================
    // Skipped result → Completed outcome
    // =========================================================================

    @Test
    fun `Skipped result returns Completed outcome`() {
        val (coordinator, bindings) = coordinatorReturning(makeSkippedResult())
        val handler = makeHandler(coordinator = coordinator, bindings = bindings)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Completed>(outcome)
    }

    // =========================================================================
    // Cancelled result → Cancelled outcome
    // =========================================================================

    @Test
    fun `Cancelled result returns Cancelled outcome`() {
        val (coordinator, bindings) = coordinatorReturning(makeCancelledResult())
        val handler = makeHandler(coordinator = coordinator, bindings = bindings)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Cancelled>(outcome)
    }

    @Test
    fun `Cancelled result preserves request execution context`() {
        val (coordinator, bindings) = coordinatorReturning(makeCancelledResult())
        val handler = makeHandler(coordinator = coordinator, bindings = bindings)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Cancelled
        assertEquals(sampleContext, outcome.context)
    }

    // =========================================================================
    // Failed result with retry policy → Reschedule outcome
    // =========================================================================

    @Test
    fun `Failed result with retry decision returns Reschedule outcome`() {
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysRetryPolicy(delay = SchedulingDelay(2000L)),
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Reschedule>(outcome)
    }

    @Test
    fun `Reschedule retry attempt is 1 when entry has no prior retries`() {
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysRetryPolicy(),
        )
        val entry = makeLeasedEntry(retryAttempt = null)
        val outcome = runSuspend { handler.execute(entry) }
        outcome as QueueEntryExecutionOutcome.Reschedule
        assertEquals(RetryAttempt(1), outcome.retryAttempt)
    }

    @Test
    fun `Reschedule retry attempt is entry attempt plus 1`() {
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysRetryPolicy(),
        )
        val entry = makeLeasedEntry(retryAttempt = RetryAttempt(2))
        val outcome = runSuspend { handler.execute(entry) }
        outcome as QueueEntryExecutionOutcome.Reschedule
        assertEquals(RetryAttempt(3), outcome.retryAttempt)
    }

    @Test
    fun `Reschedule availableAt is clock now plus delay`() {
        val delay = SchedulingDelay(3000L)
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysRetryPolicy(delay = delay),
            clock = handlerClock,
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Reschedule
        val expectedMillis = t0.epochMilliseconds + delay.milliseconds
        assertEquals(expectedMillis, outcome.availableAt.epochMilliseconds)
    }

    @Test
    fun `Reschedule preserves the canonical error`() {
        val error = FakeError()
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult(error = error))
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysRetryPolicy(),
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Reschedule
        assertEquals(error, outcome.error)
    }

    @Test
    fun `retry policy receives exact retry attempt number`() {
        val policy = AlwaysRetryPolicy()
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = policy,
        )
        val entry = makeLeasedEntry(retryAttempt = RetryAttempt(1))
        runSuspend { handler.execute(entry) }
        // Entry retryAttempt is 1 → next attempt is 2
        assertEquals(1, policy.capturedRequests.size)
        assertEquals(RetryAttempt(2), policy.capturedRequests[0].attempt)
    }

    @Test
    fun `retry policy receives the configured retry operation`() {
        val policy = AlwaysRetryPolicy()
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = policy,
        )
        runSuspend { handler.execute(makeLeasedEntry()) }
        assertEquals(retryOp, policy.capturedRequests[0].operation)
    }

    // =========================================================================
    // Failed result with stop policy → Failed outcome
    // =========================================================================

    @Test
    fun `Failed result with stop decision returns Failed outcome`() {
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysStopPolicy(),
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
    }

    @Test
    fun `Failed result with stop decision uses FAILED disposition`() {
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysStopPolicy(),
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Failed
        assertEquals(QueueFailureDisposition.FAILED, outcome.disposition)
    }

    @Test
    fun `Failed result with stop decision preserves canonical error`() {
        val error = FakeError()
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult(error = error))
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysStopPolicy(),
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Failed
        assertEquals(error, outcome.error)
    }

    // =========================================================================
    // PartiallySucceeded → Reschedule or Failed
    // =========================================================================

    @Test
    fun `PartiallySucceeded with retry decision returns Reschedule outcome`() {
        val (coordinator, bindings) = coordinatorReturning(makePartialResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysRetryPolicy(),
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Reschedule>(outcome)
    }

    @Test
    fun `PartiallySucceeded with stop decision returns Failed outcome`() {
        val (coordinator, bindings) = coordinatorReturning(makePartialResult())
        val handler = makeHandler(
            coordinator = coordinator,
            bindings = bindings,
            retryPolicy = AlwaysStopPolicy(),
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
    }

    // =========================================================================
    // Coordinator invoked exactly once per entry
    // =========================================================================

    @Test
    fun `coordinator pipeline is invoked exactly once per execute call`() {
        val pipeline = ControlledPipeline { makeSucceededResult() }
        val (coordinator, bindings) = makeCoordinatorWithPipeline(pipeline)
        val handler = makeHandler(coordinator = coordinator, bindings = bindings)
        runSuspend { handler.execute(makeLeasedEntry()) }
        assertEquals(1, pipeline.executeCallCount)
    }

    // =========================================================================
    // Cancellation propagates normally
    // =========================================================================

    @Test
    fun `CancellationException from resolver propagates normally`() {
        val resolver = QueuedSynchronizationWorkResolver { _ ->
            throw CancellationException("Cancelled in resolver")
        }
        val (coordinator, _) = coordinatorReturning(makeSucceededResult())
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )
        assertFailsWith<CancellationException> {
            runSuspend { handler.execute(makeLeasedEntry()) }
        }
    }

    // =========================================================================
    // Unexpected exceptions propagate normally
    // =========================================================================

    @Test
    fun `unexpected exception from resolver propagates normally`() {
        val exception = IllegalStateException("Unexpected resolver failure")
        val resolver = QueuedSynchronizationWorkResolver { _ -> throw exception }
        val (coordinator, _) = coordinatorReturning(makeSucceededResult())
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )
        assertFailsWith<IllegalStateException> {
            runSuspend { handler.execute(makeLeasedEntry()) }
        }
    }

    // =========================================================================
    // Overflow-safe timestamp arithmetic
    // =========================================================================

    @Test
    fun `Reschedule availableAt is overflow-safe for maximum delay`() {
        val hugeDelay = SchedulingDelay(Long.MAX_VALUE)
        val largeClock = FixedClock(DataLoomInstant(epochMilliseconds = 1_000_000L))
        val (coordinator, bindings) = coordinatorReturning(makeFailedResult())
        val work = QueuedSynchronizationWork(request = sampleRequest, bindings = bindings)
        val resolver = QueuedSynchronizationWorkResolver { _ ->
            QueuedSynchronizationWorkResolution.Resolved(work)
        }
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(
                policy = AlwaysRetryPolicy(delay = hugeDelay),
                clock = largeClock,
            ),
            retryOperation = retryOp,
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        outcome as QueueEntryExecutionOutcome.Reschedule
        // Must clamp to Long.MAX_VALUE and not wrap to negative
        assertEquals(Long.MAX_VALUE, outcome.availableAt.epochMilliseconds)
    }

    // =========================================================================
    // Resolver receives the exact queue entry
    // =========================================================================

    @Test
    fun `resolver receives the exact QueueEntry passed to execute`() {
        val capturedEntries = mutableListOf<QueueEntry>()
        val (coordinator, bindings) = coordinatorReturning(makeSucceededResult())
        val resolver = QueuedSynchronizationWorkResolver { entry ->
            capturedEntries.add(entry)
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(request = entry.synchronizationRequest, bindings = bindings),
            )
        }
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )
        val entry = makeLeasedEntry()
        runSuspend { handler.execute(entry) }
        assertEquals(1, capturedEntries.size)
        assertEquals(entry, capturedEntries[0])
    }
}
