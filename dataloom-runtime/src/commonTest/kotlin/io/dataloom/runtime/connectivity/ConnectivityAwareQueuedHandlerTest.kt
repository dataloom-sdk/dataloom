package io.dataloom.runtime.connectivity

import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
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
import io.dataloom.api.queue.QueueDeferralReason
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
import io.dataloom.runtime.queue.QueueEntryExecutionOutcome
import io.dataloom.runtime.queue.QueuedSynchronizationExecutionHandler
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic common tests for queued offline-deferral behavior introduced
 * by DL-031.
 *
 * Tests verify that [QueuedSynchronizationExecutionHandler] correctly maps
 * connectivity rejections from [SynchronizationExecutionCoordinator] to the
 * appropriate [QueueEntryExecutionOutcome] variants.
 *
 * All fakes are stateless or deterministically stateful. No real network,
 * real database, filesystem, Thread.sleep, arbitrary delay, Android APIs,
 * JVM-only APIs, or production credentials are used.
 */
class ConnectivityAwareQueuedHandlerTest {

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
    // Constants
    // =========================================================================

    private val t0 = DataLoomInstant(epochMilliseconds = 1_000_000L)
    private val t1 = DataLoomInstant(epochMilliseconds = 2_000_000L)
    private val retryOp = RetryOperation("sync.queued")
    private val initContext = ProviderInitializationContext()

    // =========================================================================
    // Fake implementations
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        var readCount: Int = 0
        override fun now(): DataLoomInstant {
            readCount++
            return instant
        }
    }

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

        override suspend fun readOutboundChanges(request: OutboundChangeReadRequest): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun acknowledgeOutboundChanges(request: OutboundChangeAcknowledgementRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun readCheckpoint(request: CheckpointReadRequest): ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())
    }

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

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(FakeError())
    }

    private class FakeConnectivityProvider(
        private val result: ProviderOperationResult<ConnectivitySnapshot>,
    ) : ConnectivityProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("connectivity-test"),
            name = ProviderName("Test Connectivity"),
            type = ProviderType.CONNECTIVITY,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun currentConnectivity(
            request: ConnectivityCheckRequest,
        ): ProviderOperationResult<ConnectivitySnapshot> = result
    }

    private class AlwaysStopRetryPolicy : RetryPolicy {
        override val id = RetryPolicyId("always-stop")
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(reason = RetryStopReason.ATTEMPT_LIMIT_REACHED)
    }

    private class AlwaysRetryPolicy(
        private val delay: SchedulingDelay = SchedulingDelay(1_000L),
    ) : RetryPolicy {
        override val id = RetryPolicyId("always-retry")
        var evaluateCallCount: Int = 0
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            evaluateCallCount++
            return RetryDecision.Retry(delay = delay)
        }
    }

    private class RecordingPipeline(
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        private val result: () -> SynchronizationResult,
    ) : SynchronizationPipeline {
        var executeCallCount: Int = 0

        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            executeCallCount++
            return result()
        }
    }

    // =========================================================================
    // Fixture factories
    // =========================================================================

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

    private fun makeRegistry(vararg providers: DataLoomProvider): ProviderRegistry =
        ProviderRegistry(providers.toList())

    private fun makeRuntimeDeps(clock: DataLoomClock = FixedClock(t0)): RuntimeDependencies {
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
        vararg extras: DataLoomProvider,
    ): ProviderLifecycleCoordinator {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        val registry = makeRegistry(storage, transport, *extras)
        val coordinator = ProviderLifecycleCoordinator(registry, initContext)
        runSuspend { coordinator.initialize() }
        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, coordinator.state)
        return coordinator
    }

    private fun makeResolver(vararg providers: DataLoomProvider): SynchronizationProviderResolver =
        SynchronizationProviderResolver(makeRegistry(*providers))

    private fun makeLeasedEntry(
        retryAttempt: RetryAttempt? = null,
        request: SynchronizationRequest = sampleRequest,
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
        )
    }

    private fun makeSucceededResult(request: SynchronizationRequest = sampleRequest): SynchronizationResult.Succeeded =
        SynchronizationResult.Succeeded(
            request = request,
            completedAt = t1,
            summary = SynchronizationSummary(),
        )

    /**
     * Builds a coordinator and handler that will trigger connectivity rejection.
     *
     * The coordinator is configured with [connectivityConfig] and a
     * [connectivityProvider] that returns [snapshotResult]. The handler uses
     * [clock] for offline deferral timestamp calculation.
     */
    private fun buildConnectivityScenario(
        connectivityConfig: SynchronizationConnectivityConfiguration,
        connectivityProvider: ConnectivityProvider?,
        offlineClock: DataLoomClock,
        pipeline: RecordingPipeline = RecordingPipeline(result = { makeSucceededResult() }),
        retryPolicy: RetryPolicy = AlwaysStopRetryPolicy(),
    ): Triple<QueuedSynchronizationExecutionHandler, RecordingPipeline, FixedClock> {
        // makeInitializedLifecycle creates its own storage/transport internally.
        // Only pass the connectivity provider as an extra to avoid duplicate ID errors.
        val extras = if (connectivityProvider != null) arrayOf(connectivityProvider) else emptyArray<DataLoomProvider>()
        val lifecycle = makeInitializedLifecycle(*extras)

        // Resolver gets fresh storage/transport (same default IDs) plus connectivity provider.
        val resolverProviders: MutableList<DataLoomProvider> = mutableListOf(
            FakeStorageProvider(), // ID = "storage-primary"
            FakeTransportProvider(), // ID = "transport-prod"
        )
        if (connectivityProvider != null) resolverProviders.add(connectivityProvider)
        val resolver = makeResolver(*resolverProviders.toTypedArray())

        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDeps(),
            connectivityConfiguration = connectivityConfig,
        )

        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-primary"),
            transportProviderId = ProviderId("transport-prod"),
            connectivityProviderId = connectivityProvider?.descriptor?.id,
        )

        val workResolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(request = entry.synchronizationRequest, bindings = bindings),
            )
        }

        val clockForOffline = offlineClock as FixedClock
        val retryEvaluator = SynchronizationRetryEvaluator(
            retryPolicy = retryPolicy,
            clock = FixedClock(t0),
        )

        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = workResolver,
            executionCoordinator = coordinator,
            retryEvaluator = retryEvaluator,
            retryOperation = retryOp,
            connectivityConfiguration = connectivityConfig,
            clock = offlineClock,
        )

        return Triple(handler, pipeline, clockForOffline)
    }

    // =========================================================================
    // Offline deferral: CONNECTIVITY_REQUIREMENT_NOT_MET → Deferred
    // =========================================================================

    @Test
    fun `unmet connectivity maps to Deferred outcome`() {
        val offlineClock = FixedClock(t0)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(30_000L),
        )
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Deferred>(outcome)
    }

    @Test
    fun `offline deferral availableAt equals clock now plus configured delay`() {
        val now = DataLoomInstant(epochMilliseconds = 1_000_000L)
        val delay = SchedulingDelay(30_000L)
        val offlineClock = FixedClock(now)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = delay,
        )
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) } as QueueEntryExecutionOutcome.Deferred
        val expectedMillis = now.epochMilliseconds + delay.milliseconds
        assertEquals(expectedMillis, outcome.availableAt.epochMilliseconds)
    }

    @Test
    fun `clock is read exactly once for offline deferral`() {
        val offlineClock = FixedClock(t0)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(5_000L),
        )
        val (handler, _, clockRef) = buildConnectivityScenario(config, provider, offlineClock)
        runSuspend { handler.execute(makeLeasedEntry()) }
        assertEquals(1, clockRef.readCount)
    }

    @Test
    fun `offline deferral has the stable connectivity reason with existing retry history`() {
        val offlineClock = FixedClock(t0)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock)
        val entry = makeLeasedEntry(retryAttempt = RetryAttempt(2))
        val outcome = runSuspend { handler.execute(entry) } as QueueEntryExecutionOutcome.Deferred
        assertEquals(QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET, outcome.reason)
    }

    @Test
    fun `offline deferral has the same stable reason without retry history`() {
        val offlineClock = FixedClock(t0)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock)
        val entry = makeLeasedEntry(retryAttempt = null)
        val outcome = runSuspend { handler.execute(entry) } as QueueEntryExecutionOutcome.Deferred
        assertEquals(QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET, outcome.reason)
    }

    @Test
    fun `offline deferral does not invoke RetryPolicy`() {
        val offlineClock = FixedClock(t0)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val alwaysRetry = AlwaysRetryPolicy()
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock, retryPolicy = alwaysRetry)
        runSuspend { handler.execute(makeLeasedEntry()) }
        assertEquals(0, alwaysRetry.evaluateCallCount)
    }

    @Test
    fun `pipeline is not invoked when connectivity requirement not met`() {
        val offlineClock = FixedClock(t0)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val pipeline = RecordingPipeline(result = { makeSucceededResult() })
        val (handler, pipelineRef, _) = buildConnectivityScenario(config, provider, offlineClock, pipeline = pipeline)
        runSuspend { handler.execute(makeLeasedEntry()) }
        assertEquals(0, pipelineRef.executeCallCount)
    }

    @Test
    fun `overflow-safe timestamp arithmetic when delay overflows Long max`() {
        val nearMax = DataLoomInstant(epochMilliseconds = Long.MAX_VALUE - 1_000L)
        val offlineClock = FixedClock(nearMax)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(Long.MAX_VALUE),
        )
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Deferred>(outcome)
        // Result should be Long.MAX_VALUE (overflow-safe)
        assertEquals(Long.MAX_VALUE, outcome.availableAt.epochMilliseconds)
    }

    // =========================================================================
    // Missing connectivity provider → Failed
    // =========================================================================

    @Test
    fun `missing connectivity provider maps to Failed outcome`() {
        val offlineClock = FixedClock(t0)
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        // null provider — will trigger CONNECTIVITY_PROVIDER_NOT_CONFIGURED
        val (handler, _, _) = buildConnectivityScenario(config, null, offlineClock)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals(QueueFailureDisposition.FAILED, outcome.disposition)
    }

    // =========================================================================
    // Provider failure → Failed with exact error
    // =========================================================================

    @Test
    fun `connectivity provider failure maps to Failed outcome`() {
        val offlineClock = FixedClock(t0)
        val error = FakeError(code = ErrorCode("DL-CONN-FAIL"))
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Failure(error),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
    }

    @Test
    fun `connectivity provider failure preserves exact error`() {
        val offlineClock = FixedClock(t0)
        val error = FakeError(code = ErrorCode("DL-CONN-FAIL"))
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Failure(error),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) } as QueueEntryExecutionOutcome.Failed
        assertEquals(error, outcome.error)
    }

    @Test
    fun `connectivity provider failure does not map to Deferred`() {
        val offlineClock = FixedClock(t0)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Failure(FakeError()),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val (handler, _, _) = buildConnectivityScenario(config, provider, offlineClock)
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        // Failure should NOT be treated as RequirementNotMet → Deferred
        assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
    }

    // =========================================================================
    // Backward compatibility: existing retry behavior unchanged
    // =========================================================================

    @Test
    fun `pipeline Failed result still uses RetryPolicy when connectivity satisfied`() {
        val offlineClock = FixedClock(t0)
        val provider = FakeConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(30_000L),
        )
        val alwaysRetry = AlwaysRetryPolicy(delay = SchedulingDelay(5_000L))

        val failedResult = SynchronizationResult.Failed(
            request = sampleRequest,
            completedAt = t1,
            summary = SynchronizationSummary(),
            error = FakeError(),
        )
        val pipeline = RecordingPipeline(result = { failedResult })
        val (handler, _, _) = buildConnectivityScenario(
            config, provider, offlineClock, pipeline = pipeline, retryPolicy = alwaysRetry,
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Reschedule>(outcome)
        // The retry attempt is incremented (retry policy path, not offline deferral)
        assertEquals(RetryAttempt(1), outcome.retryAttempt)
        // The delay comes from AlwaysRetryPolicy, not offlineRescheduleDelay
        val expectedMillis = t0.epochMilliseconds + 5_000L
        assertEquals(expectedMillis, outcome.availableAt.epochMilliseconds)
        // RetryPolicy was invoked
        assertEquals(1, alwaysRetry.evaluateCallCount)
    }

    @Test
    fun `offline then online first genuine failure is retry attempt 1`() {
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val offlineProvider = FakeConnectivityProvider(
            ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val (offlineHandler, _, _) = buildConnectivityScenario(
            config,
            offlineProvider,
            FixedClock(t0),
        )
        val deferred = runSuspend { offlineHandler.execute(makeLeasedEntry(retryAttempt = null)) }
        assertIs<QueueEntryExecutionOutcome.Deferred>(deferred)

        val onlineProvider = FakeConnectivityProvider(
            ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val failedPipeline = RecordingPipeline(
            result = {
                SynchronizationResult.Failed(
                    request = sampleRequest,
                    completedAt = t1,
                    summary = SynchronizationSummary(),
                    error = FakeError(),
                )
            },
        )
        val (onlineHandler, _, _) = buildConnectivityScenario(
            config,
            onlineProvider,
            FixedClock(t0),
            pipeline = failedPipeline,
            retryPolicy = AlwaysRetryPolicy(),
        )

        val firstFailure = runSuspend {
            onlineHandler.execute(makeLeasedEntry(retryAttempt = null))
        }
        assertIs<QueueEntryExecutionOutcome.Reschedule>(firstFailure)
        assertEquals(RetryAttempt(1), firstFailure.retryAttempt)
    }

    @Test
    fun `retry N then offline then online failure is retry attempt N plus 1`() {
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val offlineProvider = FakeConnectivityProvider(
            ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val (offlineHandler, _, _) = buildConnectivityScenario(
            config,
            offlineProvider,
            FixedClock(t0),
        )
        val deferred = runSuspend {
            offlineHandler.execute(makeLeasedEntry(retryAttempt = RetryAttempt(2)))
        }
        assertIs<QueueEntryExecutionOutcome.Deferred>(deferred)

        val onlineProvider = FakeConnectivityProvider(
            ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val failedPipeline = RecordingPipeline(
            result = {
                SynchronizationResult.Failed(
                    request = sampleRequest,
                    completedAt = t1,
                    summary = SynchronizationSummary(),
                    error = FakeError(),
                )
            },
        )
        val (onlineHandler, _, _) = buildConnectivityScenario(
            config,
            onlineProvider,
            FixedClock(t0),
            pipeline = failedPipeline,
            retryPolicy = AlwaysRetryPolicy(),
        )

        val nextFailure = runSuspend {
            onlineHandler.execute(makeLeasedEntry(retryAttempt = RetryAttempt(2)))
        }
        assertIs<QueueEntryExecutionOutcome.Reschedule>(nextFailure)
        assertEquals(RetryAttempt(3), nextFailure.retryAttempt)
    }

    @Test
    fun `no connectivity configuration maps all coordinator rejections to Failed`() {
        // Handler with no connectivity configuration — default fallback
        val lifecycle = makeInitializedLifecycle()
        val resolver = makeResolver(FakeStorageProvider(), FakeTransportProvider())
        RecordingPipeline(result = { makeSucceededResult() })

        // Create coordinator WITHOUT connectivity (default NONE)
        // Have coordinator reject due to PIPELINE_NOT_FOUND by registering no pipeline
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
            runtimeDependencies = makeRuntimeDeps(),
        )
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-primary"),
            transportProviderId = ProviderId("transport-prod"),
        )
        val workResolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(request = entry.synchronizationRequest, bindings = bindings),
            )
        }
        val retryEvaluator = SynchronizationRetryEvaluator(
            retryPolicy = AlwaysStopRetryPolicy(),
            clock = FixedClock(t0),
        )
        // Handler with no connectivity configuration
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = workResolver,
            executionCoordinator = coordinator,
            retryEvaluator = retryEvaluator,
            retryOperation = retryOp,
            // connectivityConfiguration and clock are null (defaults)
        )
        val outcome = runSuspend { handler.execute(makeLeasedEntry()) }
        assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
    }
}
