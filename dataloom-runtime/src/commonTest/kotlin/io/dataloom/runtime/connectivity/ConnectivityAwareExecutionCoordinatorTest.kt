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
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
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
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Deterministic common tests for connectivity-aware execution coordinator
 * behavior introduced by DL-031.
 *
 * Tests verify the new connectivity preflight integration in
 * [SynchronizationExecutionCoordinator] without affecting existing DL-020
 * behavior.
 *
 * All fakes are stateless or deterministically stateful. No real network,
 * database, filesystem, Thread.sleep, Android APIs, JVM-only APIs, or
 * production credentials are used.
 */
class ConnectivityAwareExecutionCoordinatorTest {

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
    // Fake errors
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Fake providers
    // =========================================================================

    private class FakeStorageProvider(id: String = "storage-primary") : StorageProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Storage $id"),
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
            name = ProviderName("Transport $id"),
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

    private class RecordingConnectivityProvider(
        private val result: ProviderOperationResult<ConnectivitySnapshot>,
        id: String = "connectivity-test",
    ) : ConnectivityProvider {
        var callCount: Int = 0
        var lastRequest: ConnectivityCheckRequest? = null

        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Test Connectivity $id"),
            type = ProviderType.CONNECTIVITY,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun currentConnectivity(request: ConnectivityCheckRequest): ProviderOperationResult<ConnectivitySnapshot> {
            callCount++
            lastRequest = request
            return result
        }
    }

    // =========================================================================
    // Fake pipeline
    // =========================================================================

    private class RecordingPipeline(
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        private val result: SynchronizationResult,
    ) : SynchronizationPipeline {
        var executeCallCount: Int = 0

        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            executeCallCount++
            return result
        }
    }

    // =========================================================================
    // Fixture factories
    // =========================================================================

    private val initContext = ProviderInitializationContext()

    private fun makeRequest(direction: SynchronizationDirection = SynchronizationDirection.PUSH): SynchronizationRequest =
        SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("exec-001"),
                correlationId = CorrelationId("corr-001"),
            ),
        )

    private fun makeRuntimeDependencies(): RuntimeDependencies {
        val generators = RuntimeIdentifierGenerators(
            synchronizationEventIds = object : IdentifierGenerator<SynchronizationEventId> {
                override fun generate() = SynchronizationEventId("event-001")
            },
            queueEntryIds = object : IdentifierGenerator<QueueEntryId> {
                override fun generate() = QueueEntryId("queue-001")
            },
            queueLeaseIds = object : IdentifierGenerator<QueueLeaseId> {
                override fun generate() = QueueLeaseId("lease-001")
            },
            conflictIds = object : IdentifierGenerator<ConflictId> {
                override fun generate() = ConflictId("conflict-001")
            },
        )
        return RuntimeDependencies(
            clock = object : DataLoomClock {
                override fun now() = DataLoomInstant(1_000_000L)
            },
            identifiers = generators,
        )
    }

    private fun makeStorage(id: String = "storage-primary") = FakeStorageProvider(id)
    private fun makeTransport(id: String = "transport-prod") = FakeTransportProvider(id)

    private fun makeRegistry(vararg providers: DataLoomProvider): ProviderRegistry =
        ProviderRegistry(providers.toList())

    /**
     * Creates an initialized lifecycle coordinator with default storage+transport providers
     * and any supplied extras.
     *
     * The storage and transport registered internally use the default IDs
     * "storage-primary" and "transport-prod", which must match [makeBindings].
     */
    private fun makeInitializedLifecycle(vararg extras: DataLoomProvider): ProviderLifecycleCoordinator {
        val registry = makeRegistry(makeStorage(), makeTransport(), *extras)
        val coordinator = ProviderLifecycleCoordinator(registry, initContext)
        runSuspend { coordinator.initialize() }
        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, coordinator.state)
        return coordinator
    }

    private fun makeBindings(connectivityProviderId: ProviderId? = null): SynchronizationProviderBindings =
        SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-primary"),
            transportProviderId = ProviderId("transport-prod"),
            connectivityProviderId = connectivityProviderId,
        )

    private fun makeProviderResolver(vararg providers: DataLoomProvider): SynchronizationProviderResolver =
        SynchronizationProviderResolver(makeRegistry(*providers))

    private fun makeSucceededResult(
        request: SynchronizationRequest = makeRequest(),
    ): SynchronizationResult.Succeeded = SynchronizationResult.Succeeded(
        request = request,
        completedAt = DataLoomInstant(1_000_000L),
        summary = SynchronizationSummary(),
    )

    private fun buildCoordinator(
        lifecycleCoordinator: ProviderLifecycleCoordinator,
        providerResolver: SynchronizationProviderResolver,
        pipeline: SynchronizationPipeline? = null,
        connectivityConfiguration: SynchronizationConnectivityConfiguration = SynchronizationConnectivityConfiguration.NONE,
    ): SynchronizationExecutionCoordinator {
        val pipelines: Collection<SynchronizationPipeline> =
            if (pipeline != null) listOf(pipeline) else emptyList()
        return SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            providerResolver = providerResolver,
            pipelineRegistry = SynchronizationPipelineRegistry(pipelines),
            runtimeDependencies = makeRuntimeDependencies(),
            connectivityConfiguration = connectivityConfiguration,
        )
    }

    // =========================================================================
    // Backward compatibility: NONE requirement
    // =========================================================================

    @Test
    fun `NONE requirement allows execution without a connectivity provider`() {
        val request = makeRequest()
        val pipeline = RecordingPipeline(result = makeSucceededResult(request))
        val lifecycle = makeInitializedLifecycle()
        val resolver = makeProviderResolver(makeStorage(), makeTransport())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration.NONE,
        )
        val result = runSuspend { coordinator.execute(request, makeBindings()) }
        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, pipeline.executeCallCount)
    }

    @Test
    fun `NONE requirement does not invoke connectivity provider`() {
        val request = makeRequest()
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult(request))
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration.NONE,
        )
        // Even with connectivityProviderId bound, NONE requirement skips provider invocation
        runSuspend {
            coordinator.execute(
                request,
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertEquals(0, connectivityProvider.callCount)
    }

    // =========================================================================
    // Satisfied connectivity
    // =========================================================================

    @Test
    fun `AVAILABLE requirement satisfied by AVAILABLE status allows execution`() {
        val request = makeRequest()
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult(request))
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(
                request,
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, pipeline.executeCallCount)
    }

    @Test
    fun `UNMETERED requirement satisfied by AVAILABLE unmetered allows execution`() {
        val request = makeRequest()
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = false),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult(request))
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.UNMETERED,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(
                request,
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertIs<SynchronizationExecutionResult.Executed>(result)
    }

    @Test
    fun `connectivity provider is invoked exactly once per execute call`() {
        val request = makeRequest()
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = false),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult(request))
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        runSuspend {
            coordinator.execute(
                request,
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertEquals(1, connectivityProvider.callCount)
    }

    @Test
    fun `correct ConnectivityCheckRequest context is supplied`() {
        val request = makeRequest()
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult(request))
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        runSuspend {
            coordinator.execute(
                request,
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertNotNull(connectivityProvider.lastRequest)
        assertEquals(request.context, connectivityProvider.lastRequest!!.context)
    }

    // =========================================================================
    // ConnectivityRequirementNotMet rejection
    // =========================================================================

    @Test
    fun `AVAILABLE requirement not met by UNAVAILABLE returns rejection`() {
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            result.reason,
        )
    }

    @Test
    fun `pipeline is not invoked when connectivity requirement not met`() {
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        runSuspend {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertEquals(0, pipeline.executeCallCount)
    }

    @Test
    fun `UNMETERED not met when AVAILABLE but metered`() {
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = true),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.UNMETERED,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            result.reason,
        )
    }

    @Test
    fun `UNMETERED not met when AVAILABLE but isMetered is null`() {
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.UNMETERED,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            result.reason,
        )
    }

    @Test
    fun `AVAILABLE not met by UNKNOWN connectivity`() {
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNKNOWN, isMetered = null),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            result.reason,
        )
    }

    @Test
    fun `connectivity rejection has no connectivityCheckError`() {
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertNull(result.connectivityCheckError)
    }

    // =========================================================================
    // ConnectivityProviderNotConfigured rejection
    // =========================================================================

    @Test
    fun `AVAILABLE requirement with no connectivity provider in bindings returns ProviderNotConfigured rejection`() {
        val lifecycle = makeInitializedLifecycle()
        val resolver = makeProviderResolver(makeStorage(), makeTransport())
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(makeRequest(), makeBindings(connectivityProviderId = null))
        }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            SynchronizationExecutionRejectionReason.CONNECTIVITY_PROVIDER_NOT_CONFIGURED,
            result.reason,
        )
    }

    // =========================================================================
    // ConnectivityCheckFailed rejection
    // =========================================================================

    @Test
    fun `provider failure returns CheckFailed rejection with exact error`() {
        val error = FakeError(code = ErrorCode("DL-CONN-FAIL"))
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Failure(error),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        val result = runSuspend {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            SynchronizationExecutionRejectionReason.CONNECTIVITY_CHECK_FAILED,
            result.reason,
        )
        assertEquals(error, result.connectivityCheckError)
    }

    @Test
    fun `pipeline is not invoked when connectivity provider fails`() {
        val connectivityProvider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Failure(FakeError()),
        )
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        runSuspend {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        assertEquals(0, pipeline.executeCallCount)
    }

    // =========================================================================
    // Cancellation
    // =========================================================================

    @Test
    fun `CancellationException from connectivity provider propagates normally`() {
        val cancellation = CancellationException("test cancel")
        val connectivityProvider = object : ConnectivityProvider {
            override val descriptor = ProviderDescriptor(
                id = ProviderId("cancel-connectivity"),
                name = ProviderName("Cancel Connectivity"),
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
            ): ProviderOperationResult<ConnectivitySnapshot> {
                throw cancellation
            }
        }
        val lifecycle = makeInitializedLifecycle(connectivityProvider)
        val resolver = makeProviderResolver(makeStorage(), makeTransport(), connectivityProvider)
        val pipeline = RecordingPipeline(result = makeSucceededResult())
        val coordinator = buildCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = resolver,
            pipeline = pipeline,
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            ),
        )
        var thrown: Throwable? = null
        val block: suspend () -> Unit = {
            coordinator.execute(
                makeRequest(),
                makeBindings(connectivityProviderId = connectivityProvider.descriptor.id),
            )
        }
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    thrown = result.exceptionOrNull()
                }
            },
        )
        assertEquals(cancellation, thrown)
    }
}
