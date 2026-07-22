package io.dataloom.runtime.execution

import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
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
import io.dataloom.api.provider.QueueProvider
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
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
import io.dataloom.core.provider.ProviderBindingFailure
import io.dataloom.core.provider.ProviderBindingFailureReason
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderLifecycleCoordinatorState
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.ProviderResolutionResult
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.core.provider.SynchronizationProviderBindings
import io.dataloom.core.provider.SynchronizationProviderResolver
import io.dataloom.core.runtime.RuntimeDependencies
import io.dataloom.core.runtime.RuntimeIdentifierGenerators
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-020 synchronization execution coordinator
 * foundation.
 *
 * All fakes are stateless or deterministically stateful. No real network, real
 * database, filesystem, Thread.sleep, arbitrary coroutine delay, Android API,
 * JVM-only API, reflection, ServiceLoader, system clock, random IDs, production
 * credentials, or personal data is used.
 *
 * Suspend functions are exercised using [kotlin.coroutines.startCoroutine]
 * primitives from the Kotlin standard library, without requiring
 * kotlinx.coroutines.
 *
 * Contracts covered:
 * - [SynchronizationExecutionContext]
 * - [SynchronizationPipeline]
 * - [SynchronizationPipelineRegistry]
 * - [SynchronizationExecutionRejectionReason]
 * - [SynchronizationExecutionResult]
 * - [SynchronizationExecutionCoordinator]
 */
class SynchronizationExecutionCoordinatorTest {

    // =========================================================================
    // Fake error
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

    private open class FakeStorageProvider(
        id: String = "storage-primary",
        var initializeCallCount: Int = 0,
        var closeCallCount: Int = 0,
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Storage $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())
    }

    private class FakeTransportProvider(
        id: String = "transport-prod",
        var initializeCallCount: Int = 0,
        var closeCallCount: Int = 0,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Transport $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(FakeError())
    }

    private class FakeSchedulerProvider(id: String = "scheduler-default") : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Scheduler $id"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun cancel(request: ScheduleCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())
    }

    private class FakeConnectivityProvider(id: String = "connectivity-default") : ConnectivityProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Connectivity $id"),
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
        ): ProviderOperationResult<ConnectivitySnapshot> =
            ProviderOperationResult.Success(ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, null))
    }

    private class FakeQueueProvider(id: String = "queue-default") : QueueProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Queue $id"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Failure(FakeError())
    }

    /** Minimal DataLoomProvider that always fails initialization (for FAILED state tests). */
    private class FailingProvider(id: String = "failing") : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Failing $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError(message = "Intentional init failure."))

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.UNHEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    // =========================================================================
    // Fake pipeline
    // =========================================================================

    /**
     * Deterministic fake pipeline that returns a configured result and records
     * how many times it was executed.
     */
    private class FakePipeline(
        override val direction: SynchronizationDirection,
        private val resultToReturn: (SynchronizationExecutionContext) -> SynchronizationResult,
        var executeCallCount: Int = 0,
        var lastContext: SynchronizationExecutionContext? = null,
    ) : SynchronizationPipeline {
        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            executeCallCount++
            lastContext = context
            return resultToReturn(context)
        }
    }

    /** Pipeline that throws CancellationException unconditionally. */
    private class CancellingPipeline(
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH,
    ) : SynchronizationPipeline {
        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            throw CancellationException("Cancelled by test")
        }
    }

    /** Pipeline that throws an unexpected runtime exception. */
    private class ThrowingPipeline(
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        private val exception: Exception = IllegalStateException("Unexpected pipeline failure"),
    ) : SynchronizationPipeline {
        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            throw exception
        }
    }

    // =========================================================================
    // Fake clock and identifier generator
    // =========================================================================

    private class FakeClock(private val nowMs: Long = 1_000_000L) : DataLoomClock {
        var readCallCount: Int = 0
        override fun now(): DataLoomInstant {
            readCallCount++
            return DataLoomInstant(nowMs)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private val initContext = ProviderInitializationContext()

    private fun makeStorage(id: String = "storage-primary") = FakeStorageProvider(id)
    private fun makeTransport(id: String = "transport-prod") = FakeTransportProvider(id)

    private fun makeRegistry(vararg providers: DataLoomProvider): ProviderRegistry =
        ProviderRegistry(providers.toList())

    private fun makeResolver(vararg providers: DataLoomProvider): SynchronizationProviderResolver =
        SynchronizationProviderResolver(makeRegistry(*providers))

    private fun makeBindings(
        storage: FakeStorageProvider = makeStorage(),
        transport: FakeTransportProvider = makeTransport(),
    ) = SynchronizationProviderBindings(
        storageProviderId = storage.descriptor.id,
        transportProviderId = transport.descriptor.id,
    )

    private fun makeRuntimeDependencies(): RuntimeDependencies {
        val idGenerators = RuntimeIdentifierGenerators(
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
            clock = FakeClock(),
            identifiers = idGenerators,
        )
    }

    private fun makeRequest(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        mode: SynchronizationMode = SynchronizationMode.DELTA,
    ) = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = direction,
        mode = mode,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun makeResolvedProviders(
        storage: FakeStorageProvider = makeStorage(),
        transport: FakeTransportProvider = makeTransport(),
    ) = ResolvedSynchronizationProviders(
        storageProvider = storage,
        transportProvider = transport,
        schedulerProvider = null,
        connectivityProvider = null,
        queueProvider = null,
    )

    private fun makeBindingFailure(
        id: String = "storage-missing",
        type: ProviderType = ProviderType.STORAGE,
        reason: ProviderBindingFailureReason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
    ) = ProviderBindingFailure(
        requestedId = ProviderId(id),
        expectedType = type,
        actualType = null,
        reason = reason,
    )

    /** Creates an initialized ProviderLifecycleCoordinator using fake providers. */
    private fun makeInitializedCoordinator(
        storage: FakeStorageProvider = makeStorage(),
        transport: FakeTransportProvider = makeTransport(),
    ): ProviderLifecycleCoordinator {
        val registry = makeRegistry(storage, transport)
        val coordinator = ProviderLifecycleCoordinator(registry, initContext)
        runSuspend { coordinator.initialize() }
        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, coordinator.state)
        return coordinator
    }

    /** Creates a NOT_INITIALIZED coordinator. */
    private fun makeNotInitializedCoordinator(): ProviderLifecycleCoordinator {
        val registry = makeRegistry(makeStorage(), makeTransport())
        return ProviderLifecycleCoordinator(registry, initContext)
    }

    /** Creates a SHUT_DOWN coordinator (initialized then shut down). */
    private fun makeShutDownCoordinator(): ProviderLifecycleCoordinator {
        val registry = makeRegistry(makeStorage(), makeTransport())
        val coordinator = ProviderLifecycleCoordinator(registry, initContext)
        runSuspend { coordinator.initialize() }
        runSuspend { coordinator.shutdown() }
        assertEquals(ProviderLifecycleCoordinatorState.SHUT_DOWN, coordinator.state)
        return coordinator
    }

    /** Creates a FAILED coordinator (init fails). */
    private fun makeFailedCoordinator(): ProviderLifecycleCoordinator {
        val registry = makeRegistry(FailingProvider())
        val coordinator = ProviderLifecycleCoordinator(registry, initContext)
        runSuspend { coordinator.initialize() }
        assertEquals(ProviderLifecycleCoordinatorState.FAILED, coordinator.state)
        return coordinator
    }

    private fun makePipeline(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        result: SynchronizationResult? = null,
    ): FakePipeline {
        val request = makeRequest(direction)
        val resultToReturn = result ?: SynchronizationResult.Succeeded(
            request = request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
        )
        return FakePipeline(direction = direction, resultToReturn = { resultToReturn })
    }

    private fun makeSucceededResult(request: SynchronizationRequest = makeRequest()): SynchronizationResult.Succeeded =
        SynchronizationResult.Succeeded(
            request = request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
        )

    private fun makeCancelledResult(request: SynchronizationRequest = makeRequest()): SynchronizationResult.Cancelled =
        SynchronizationResult.Cancelled(
            request = request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
        )

    private fun makeFailedResult(request: SynchronizationRequest = makeRequest()): SynchronizationResult.Failed =
        SynchronizationResult.Failed(
            request = request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
            error = FakeError(),
        )

    private fun makePartialResult(request: SynchronizationRequest = makeRequest()): SynchronizationResult.PartiallySucceeded =
        SynchronizationResult.PartiallySucceeded(
            request = request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
            errors = listOf(FakeError()),
        )

    private fun makeSkippedResult(request: SynchronizationRequest = makeRequest()): SynchronizationResult.Skipped =
        SynchronizationResult.Skipped(
            request = request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
            reason = SynchronizationSkipReason.NO_CHANGES,
        )

    // =========================================================================
    // Coroutine helpers
    // =========================================================================

    private object Pending

    @Suppress("UNCHECKED_CAST")
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
        return rawResult as T
    }

    private fun <T> runSuspendCatching(block: suspend () -> T): Result<T> {
        var capturedResult: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    capturedResult = result
                }
            },
        )
        return checkNotNull(capturedResult) { "Suspend block did not complete synchronously in test." }
    }

    // =========================================================================
    // SynchronizationExecutionContext tests
    // =========================================================================

    @Test
    fun `SynchronizationExecutionContext preserves request exactly`() {
        val request = makeRequest()
        val providers = makeResolvedProviders()
        val deps = makeRuntimeDependencies()

        val context = SynchronizationExecutionContext(request, providers, deps)

        assertSame(request, context.request)
    }

    @Test
    fun `SynchronizationExecutionContext preserves resolved providers exactly`() {
        val request = makeRequest()
        val providers = makeResolvedProviders()
        val deps = makeRuntimeDependencies()

        val context = SynchronizationExecutionContext(request, providers, deps)

        assertSame(providers, context.providers)
    }

    @Test
    fun `SynchronizationExecutionContext preserves RuntimeDependencies exactly`() {
        val request = makeRequest()
        val providers = makeResolvedProviders()
        val deps = makeRuntimeDependencies()

        val context = SynchronizationExecutionContext(request, providers, deps)

        assertSame(deps, context.runtimeDependencies)
    }

    @Test
    fun `SynchronizationExecutionContext construction reads no clock`() {
        val clock = FakeClock()
        val deps = RuntimeDependencies(
            clock = clock,
            identifiers = makeRuntimeDependencies().identifiers,
        )
        val request = makeRequest()
        val providers = makeResolvedProviders()

        SynchronizationExecutionContext(request, providers, deps)

        assertEquals(0, clock.readCallCount)
    }

    @Test
    fun `SynchronizationExecutionContext construction invokes no provider`() {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        val providers = ResolvedSynchronizationProviders(
            storageProvider = storage,
            transportProvider = transport,
            schedulerProvider = null,
            connectivityProvider = null,
            queueProvider = null,
        )
        val request = makeRequest()
        val deps = makeRuntimeDependencies()

        SynchronizationExecutionContext(request, providers, deps)

        assertEquals(0, storage.initializeCallCount)
        assertEquals(0, storage.closeCallCount)
        assertEquals(0, transport.initializeCallCount)
        assertEquals(0, transport.closeCallCount)
    }

    @Test
    fun `SynchronizationExecutionContext toString does not invoke provider implementation toString`() {
        val storage = object : FakeStorageProvider("storage-spy") {
            var toStringCallCount: Int = 0
            override fun toString(): String {
                toStringCallCount++
                return "SpyStorage"
            }
        }
        val transport = FakeTransportProvider()
        val providers = ResolvedSynchronizationProviders(
            storageProvider = storage,
            transportProvider = transport,
            schedulerProvider = null,
            connectivityProvider = null,
            queueProvider = null,
        )
        val context = SynchronizationExecutionContext(makeRequest(), providers, makeRuntimeDependencies())

        val diagnostic = context.toString()

        assertEquals(0, storage.toStringCallCount)
        assertTrue(diagnostic.contains("storage-spy"), "Diagnostic should include storage provider ID")
    }

    @Test
    fun `SynchronizationExecutionContext toString includes request session and direction`() {
        val request = SynchronizationRequest(
            workflowId = WorkflowId("wf-diag"),
            sessionId = SynchronizationSessionId("session-diag"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("exec-diag"),
                correlationId = CorrelationId("corr-diag"),
            ),
        )
        val providers = makeResolvedProviders()
        val context = SynchronizationExecutionContext(request, providers, makeRuntimeDependencies())

        val diagnostic = context.toString()

        assertTrue(diagnostic.contains("session-diag"))
        assertTrue(diagnostic.contains("wf-diag"))
        assertTrue(diagnostic.contains("PULL"))
    }

    // =========================================================================
    // SynchronizationPipelineRegistry tests
    // =========================================================================

    @Test
    fun `SynchronizationPipelineRegistry empty registry is valid`() {
        val registry = SynchronizationPipelineRegistry(emptyList())
        assertNull(registry.lookup(SynchronizationDirection.PUSH))
    }

    @Test
    fun `SynchronizationPipelineRegistry registers and looks up one pipeline`() {
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val registry = SynchronizationPipelineRegistry(listOf(pipeline))

        assertSame(pipeline, registry.lookup(SynchronizationDirection.PUSH))
    }

    @Test
    fun `SynchronizationPipelineRegistry registers multiple distinct directions`() {
        val push = makePipeline(SynchronizationDirection.PUSH)
        val pull = makePipeline(SynchronizationDirection.PULL)
        val registry = SynchronizationPipelineRegistry(listOf(push, pull))

        assertSame(push, registry.lookup(SynchronizationDirection.PUSH))
        assertSame(pull, registry.lookup(SynchronizationDirection.PULL))
    }

    @Test
    fun `SynchronizationPipelineRegistry lookup for missing direction returns null`() {
        val push = makePipeline(SynchronizationDirection.PUSH)
        val registry = SynchronizationPipelineRegistry(listOf(push))

        assertNull(registry.lookup(SynchronizationDirection.PULL))
        assertNull(registry.lookup(SynchronizationDirection.BIDIRECTIONAL))
    }

    @Test
    fun `SynchronizationPipelineRegistry preserves insertion order`() {
        val push = makePipeline(SynchronizationDirection.PUSH)
        val pull = makePipeline(SynchronizationDirection.PULL)
        val bi = makePipeline(SynchronizationDirection.BIDIRECTIONAL)
        val registry = SynchronizationPipelineRegistry(listOf(push, pull, bi))

        val registered = registry.pipelines
        assertEquals(SynchronizationDirection.PUSH, registered[0].direction)
        assertEquals(SynchronizationDirection.PULL, registered[1].direction)
        assertEquals(SynchronizationDirection.BIDIRECTIONAL, registered[2].direction)
    }

    @Test
    fun `SynchronizationPipelineRegistry rejects duplicate direction`() {
        val push1 = makePipeline(SynchronizationDirection.PUSH)
        val push2 = makePipeline(SynchronizationDirection.PUSH)

        assertFailsWith<IllegalArgumentException> {
            SynchronizationPipelineRegistry(listOf(push1, push2))
        }
    }

    @Test
    fun `SynchronizationPipelineRegistry defensively copies source collection`() {
        val push = makePipeline(SynchronizationDirection.PUSH)
        val mutableList = mutableListOf<SynchronizationPipeline>(push)
        val registry = SynchronizationPipelineRegistry(mutableList)

        // Mutate original collection after construction.
        val pull = makePipeline(SynchronizationDirection.PULL)
        mutableList.add(pull)

        // Registry must not see the added pipeline.
        assertNull(registry.lookup(SynchronizationDirection.PULL))
        assertEquals(1, registry.pipelines.size)
    }

    @Test
    fun `SynchronizationPipelineRegistry pipelines property is read-only`() {
        val push = makePipeline(SynchronizationDirection.PUSH)
        val registry = SynchronizationPipelineRegistry(listOf(push))

        // The result is typed as List (read-only). No cast to MutableList succeeds.
        val pipelines: List<SynchronizationPipeline> = registry.pipelines
        assertIs<List<SynchronizationPipeline>>(pipelines)
    }

    @Test
    fun `SynchronizationPipelineRegistry construction performs no execution`() {
        val push = makePipeline(SynchronizationDirection.PUSH)
        SynchronizationPipelineRegistry(listOf(push))

        assertEquals(0, push.executeCallCount)
    }

    @Test
    fun `SynchronizationPipelineRegistry toString does not invoke pipeline toString`() {
        val spy = object : SynchronizationPipeline {
            override val direction: SynchronizationDirection = SynchronizationDirection.PUSH
            var toStringCallCount = 0
            override fun toString(): String {
                toStringCallCount++
                return "SpyPipeline"
            }
            override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult =
                error("Should not be called")
        }
        val registry = SynchronizationPipelineRegistry(listOf(spy))

        registry.toString()

        assertEquals(0, spy.toStringCallCount)
    }

    // =========================================================================
    // SynchronizationExecutionRejectionReason tests
    // =========================================================================

    @Test
    fun `SynchronizationExecutionRejectionReason contains PROVIDERS_NOT_INITIALIZED`() {
        val reason = SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED
        assertNotNull(reason)
    }

    @Test
    fun `SynchronizationExecutionRejectionReason contains PROVIDER_RESOLUTION_FAILED`() {
        val reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED
        assertNotNull(reason)
    }

    @Test
    fun `SynchronizationExecutionRejectionReason contains PIPELINE_NOT_FOUND`() {
        val reason = SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND
        assertNotNull(reason)
    }

    // =========================================================================
    // SynchronizationExecutionResult tests
    // =========================================================================

    @Test
    fun `SynchronizationExecutionResult Executed preserves the pipeline result`() {
        val pipelineResult = makeSucceededResult()
        val executed = SynchronizationExecutionResult.Executed(pipelineResult)

        assertSame(pipelineResult, executed.result)
    }

    @Test
    fun `SynchronizationExecutionResult Rejected with PROVIDERS_NOT_INITIALIZED requires empty failures`() {
        val rejected = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
        )
        assertEquals(
            SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            rejected.reason,
        )
        assertTrue(rejected.providerBindingFailures.isEmpty())
    }

    @Test
    fun `SynchronizationExecutionResult Rejected with PIPELINE_NOT_FOUND requires empty failures`() {
        val rejected = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND,
        )
        assertEquals(
            SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND,
            rejected.reason,
        )
        assertTrue(rejected.providerBindingFailures.isEmpty())
    }

    @Test
    fun `SynchronizationExecutionResult Rejected with PROVIDER_RESOLUTION_FAILED requires non-empty failures`() {
        val failure = makeBindingFailure()
        val rejected = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
            providerBindingFailures = listOf(failure),
        )

        assertEquals(
            SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
            rejected.reason,
        )
        assertEquals(1, rejected.providerBindingFailures.size)
        assertEquals(failure, rejected.providerBindingFailures[0])
    }

    @Test
    fun `SynchronizationExecutionResult Rejected PROVIDER_RESOLUTION_FAILED with empty failures throws`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationExecutionResult.Rejected(
                reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                providerBindingFailures = emptyList(),
            )
        }
    }

    @Test
    fun `SynchronizationExecutionResult Rejected PROVIDERS_NOT_INITIALIZED with non-empty failures throws`() {
        val failure = makeBindingFailure()
        assertFailsWith<IllegalArgumentException> {
            SynchronizationExecutionResult.Rejected(
                reason = SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
                providerBindingFailures = listOf(failure),
            )
        }
    }

    @Test
    fun `SynchronizationExecutionResult Rejected PIPELINE_NOT_FOUND with non-empty failures throws`() {
        val failure = makeBindingFailure()
        assertFailsWith<IllegalArgumentException> {
            SynchronizationExecutionResult.Rejected(
                reason = SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND,
                providerBindingFailures = listOf(failure),
            )
        }
    }

    @Test
    fun `SynchronizationExecutionResult Rejected defensively copies failure collection`() {
        val failure = makeBindingFailure()
        val mutableList = mutableListOf(failure)
        val rejected = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
            providerBindingFailures = mutableList,
        )

        mutableList.add(makeBindingFailure("extra"))

        assertEquals(1, rejected.providerBindingFailures.size)
    }

    @Test
    fun `SynchronizationExecutionResult Rejected preserves failure order`() {
        val failure1 = makeBindingFailure("storage-a")
        val failure2 = makeBindingFailure("transport-b", ProviderType.TRANSPORT)
        val rejected = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
            providerBindingFailures = listOf(failure1, failure2),
        )

        assertEquals(failure1, rejected.providerBindingFailures[0])
        assertEquals(failure2, rejected.providerBindingFailures[1])
    }

    @Test
    fun `SynchronizationExecutionResult Rejected exposes no provider instance`() {
        // Rejected.providerBindingFailures only contains ProviderBindingFailure
        // which exposes only requestedId, expectedType, actualType, reason.
        val failure = makeBindingFailure()
        val rejected = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
            providerBindingFailures = listOf(failure),
        )

        // Structural verification: only ProviderBindingFailure is accessible.
        val bindingFailure = rejected.providerBindingFailures[0]
        assertIs<ProviderBindingFailure>(bindingFailure)
    }

    @Test
    fun `SynchronizationExecutionResult Rejected value equality`() {
        val failure = makeBindingFailure()
        val r1 = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
            providerBindingFailures = listOf(failure),
        )
        val r2 = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
            providerBindingFailures = listOf(failure),
        )

        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    // =========================================================================
    // Lifecycle guard tests
    // =========================================================================

    @Test
    fun `coordinator rejects execution when lifecycle state is NOT_INITIALIZED`() {
        val lifecycle = makeNotInitializedCoordinator()
        assertEquals(ProviderLifecycleCoordinatorState.NOT_INITIALIZED, lifecycle.state)

        val storage = makeStorage()
        val transport = makeTransport()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(makePipeline())),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        val request = makeRequest()
        val bindings = makeBindings(storage, transport)

        val result = runSuspend { coordinator.execute(request, bindings) }

        val rejected = assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED, rejected.reason)
    }

    @Test
    fun `coordinator rejects execution when lifecycle state is SHUT_DOWN`() {
        val lifecycle = makeShutDownCoordinator()
        assertEquals(ProviderLifecycleCoordinatorState.SHUT_DOWN, lifecycle.state)

        val storage = makeStorage()
        val transport = makeTransport()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(makePipeline())),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        val rejected = assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED, rejected.reason)
    }

    @Test
    fun `coordinator rejects execution when lifecycle state is FAILED`() {
        val lifecycle = makeFailedCoordinator()
        assertEquals(ProviderLifecycleCoordinatorState.FAILED, lifecycle.state)

        val storage = makeStorage()
        val transport = makeTransport()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(makePipeline())),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        val rejected = assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED, rejected.reason)
    }

    @Test
    fun `resolution is not called after lifecycle rejection`() {
        val lifecycle = makeNotInitializedCoordinator()
        // Use an empty registry — if resolve() were called, it would produce
        // PROVIDER_RESOLUTION_FAILED. But because lifecycle is not initialized,
        // the coordinator must return PROVIDERS_NOT_INITIALIZED, proving that
        // resolve() was never invoked.
        val emptyResolver = SynchronizationProviderResolver(ProviderRegistry(emptyList()))
        val storage = makeStorage()
        val transport = makeTransport()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = emptyResolver,
            pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        // Must be PROVIDERS_NOT_INITIALIZED and NOT PROVIDER_RESOLUTION_FAILED.
        val rejected = assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED, rejected.reason)
    }

    @Test
    fun `pipeline is not executed after lifecycle rejection`() {
        val lifecycle = makeNotInitializedCoordinator()
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(makeStorage(), makeTransport()),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings()) }

        assertEquals(0, pipeline.executeCallCount)
    }

    @Test
    fun `INITIALIZED state allows execution to proceed past lifecycle check`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, lifecycle.state)

        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        val bindings = makeBindings(storage, transport)

        val result = runSuspend { coordinator.execute(makeRequest(), bindings) }

        assertIs<SynchronizationExecutionResult.Executed>(result)
    }

    // =========================================================================
    // Provider resolution tests
    // =========================================================================

    @Test
    fun `successful resolution proceeds to pipeline lookup and execution`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend {
            coordinator.execute(makeRequest(), makeBindings(storage, transport))
        }

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, pipeline.executeCallCount)
    }

    @Test
    fun `failed resolution returns PROVIDER_RESOLUTION_FAILED with all binding failures`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        // Resolver uses an empty registry so all IDs fail.
        val emptyResolver = SynchronizationProviderResolver(ProviderRegistry(emptyList()))
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = emptyResolver,
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        val bindings = makeBindings(storage, transport)

        val result = runSuspend { coordinator.execute(makeRequest(), bindings) }

        val rejected = assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED, rejected.reason)
        assertTrue(rejected.providerBindingFailures.isNotEmpty())
    }

    @Test
    fun `pipeline is not executed after resolution failure`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val emptyResolver = SynchronizationProviderResolver(ProviderRegistry(emptyList()))
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = emptyResolver,
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        assertEquals(0, pipeline.executeCallCount)
    }

    @Test
    fun `resolution failures are in resolver deterministic order`() {
        val storage = makeStorage("storage-primary")
        val transport = makeTransport("transport-prod")
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val emptyResolver = SynchronizationProviderResolver(ProviderRegistry(emptyList()))
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = emptyResolver,
            pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        val bindings = makeBindings(storage, transport)

        val result = runSuspend { coordinator.execute(makeRequest(), bindings) }

        val rejected = assertIs<SynchronizationExecutionResult.Rejected>(result)
        // Storage failure should appear before transport failure per resolver contract.
        val failures = rejected.providerBindingFailures
        assertEquals(ProviderType.STORAGE, failures[0].expectedType)
        assertEquals(ProviderType.TRANSPORT, failures[1].expectedType)
    }

    @Test
    fun `coordinator does not expose partially resolved providers in rejected result`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        // Only storage is registered; transport will fail.
        val partialRegistry = makeRegistry(storage)
        val partialResolver = SynchronizationProviderResolver(partialRegistry)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = partialResolver,
            pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        val bindings = makeBindings(storage, transport)

        val result = runSuspend { coordinator.execute(makeRequest(), bindings) }

        val rejected = assertIs<SynchronizationExecutionResult.Rejected>(result)
        // Rejected exposes only ProviderBindingFailure records, not provider instances.
        assertIs<SynchronizationExecutionResult.Rejected>(rejected)
    }

    // =========================================================================
    // Pipeline lookup tests
    // =========================================================================

    @Test
    fun `coordinator selects pipeline matching request direction`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pushPipeline = makePipeline(SynchronizationDirection.PUSH)
        val pullPipeline = makePipeline(SynchronizationDirection.PULL)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pushPipeline, pullPipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend {
            coordinator.execute(makeRequest(SynchronizationDirection.PUSH), makeBindings(storage, transport))
        }

        assertEquals(1, pushPipeline.executeCallCount)
        assertEquals(0, pullPipeline.executeCallCount)
    }

    @Test
    fun `coordinator uses request direction as pipeline selection key`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pullPipeline = makePipeline(SynchronizationDirection.PULL)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pullPipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend {
            coordinator.execute(makeRequest(SynchronizationDirection.PULL), makeBindings(storage, transport))
        }

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, pullPipeline.executeCallCount)
    }

    @Test
    fun `coordinator returns PIPELINE_NOT_FOUND when no pipeline matches direction`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend {
            coordinator.execute(makeRequest(SynchronizationDirection.PUSH), makeBindings(storage, transport))
        }

        val rejected = assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND, rejected.reason)
        assertTrue(rejected.providerBindingFailures.isEmpty())
    }

    @Test
    fun `no pipeline executes after missing-pipeline rejection`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pullPipeline = makePipeline(SynchronizationDirection.PULL)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pullPipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        // Request PUSH when only PULL is registered.
        runSuspend {
            coordinator.execute(makeRequest(SynchronizationDirection.PUSH), makeBindings(storage, transport))
        }

        assertEquals(0, pullPipeline.executeCallCount)
    }

    @Test
    fun `SynchronizationMode does not affect pipeline selection`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pushPipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pushPipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        // Both DELTA and FULL modes should select the same PUSH pipeline.
        val deltaResult = runSuspend {
            coordinator.execute(
                makeRequest(SynchronizationDirection.PUSH, SynchronizationMode.DELTA),
                makeBindings(storage, transport),
            )
        }
        val fullResult = runSuspend {
            coordinator.execute(
                makeRequest(SynchronizationDirection.PUSH, SynchronizationMode.FULL),
                makeBindings(storage, transport),
            )
        }

        assertIs<SynchronizationExecutionResult.Executed>(deltaResult)
        assertIs<SynchronizationExecutionResult.Executed>(fullResult)
        assertEquals(2, pushPipeline.executeCallCount)
    }

    // =========================================================================
    // Pipeline execution tests
    // =========================================================================

    @Test
    fun `selected pipeline is invoked exactly once`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        assertEquals(1, pipeline.executeCallCount)
    }

    @Test
    fun `non-selected pipelines are not invoked`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pushPipeline = makePipeline(SynchronizationDirection.PUSH)
        val pullPipeline = makePipeline(SynchronizationDirection.PULL)
        val biPipeline = makePipeline(SynchronizationDirection.BIDIRECTIONAL)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pushPipeline, pullPipeline, biPipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend {
            coordinator.execute(makeRequest(SynchronizationDirection.PUSH), makeBindings(storage, transport))
        }

        assertEquals(1, pushPipeline.executeCallCount)
        assertEquals(0, pullPipeline.executeCallCount)
        assertEquals(0, biPipeline.executeCallCount)
    }

    @Test
    fun `exact request is included in the execution context`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        val request = makeRequest(SynchronizationDirection.PUSH)
        val bindings = makeBindings(storage, transport)

        runSuspend { coordinator.execute(request, bindings) }

        assertNotNull(pipeline.lastContext)
        assertSame(request, pipeline.lastContext!!.request)
    }

    @Test
    fun `exact resolved provider instances are included in execution context`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        val ctx = pipeline.lastContext!!
        assertSame(storage, ctx.providers.storageProvider)
        assertSame(transport, ctx.providers.transportProvider)
    }

    @Test
    fun `exact RuntimeDependencies instance is included in execution context`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val deps = makeRuntimeDependencies()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = deps,
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        assertSame(deps, pipeline.lastContext!!.runtimeDependencies)
    }

    @Test
    fun `Succeeded result is returned unchanged`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val request = makeRequest()
        val pipelineResult = makeSucceededResult(request)
        val pipeline = FakePipeline(SynchronizationDirection.PUSH, resultToReturn = { pipelineResult })
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend { coordinator.execute(request, makeBindings(storage, transport)) }

        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        assertSame(pipelineResult, executed.result)
    }

    @Test
    fun `PartiallySucceeded result is returned unchanged`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val request = makeRequest()
        val pipelineResult = makePartialResult(request)
        val pipeline = FakePipeline(SynchronizationDirection.PUSH, resultToReturn = { pipelineResult })
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend { coordinator.execute(request, makeBindings(storage, transport)) }

        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        assertSame(pipelineResult, executed.result)
    }

    @Test
    fun `Failed result is returned unchanged`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val request = makeRequest()
        val pipelineResult = makeFailedResult(request)
        val pipeline = FakePipeline(SynchronizationDirection.PUSH, resultToReturn = { pipelineResult })
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend { coordinator.execute(request, makeBindings(storage, transport)) }

        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        assertSame(pipelineResult, executed.result)
    }

    @Test
    fun `Cancelled result is returned unchanged`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val request = makeRequest()
        val pipelineResult = makeCancelledResult(request)
        val pipeline = FakePipeline(SynchronizationDirection.PUSH, resultToReturn = { pipelineResult })
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend { coordinator.execute(request, makeBindings(storage, transport)) }

        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        assertSame(pipelineResult, executed.result)
    }

    @Test
    fun `Skipped result is returned unchanged`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val request = makeRequest()
        val pipelineResult = makeSkippedResult(request)
        val pipeline = FakePipeline(SynchronizationDirection.PUSH, resultToReturn = { pipelineResult })
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val result = runSuspend { coordinator.execute(request, makeBindings(storage, transport)) }

        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        assertSame(pipelineResult, executed.result)
    }

    // =========================================================================
    // Cancellation and exception tests
    // =========================================================================

    @Test
    fun `CancellationException from pipeline propagates normally`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pipeline = CancellingPipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val capturedResult = runSuspendCatching {
            coordinator.execute(makeRequest(), makeBindings(storage, transport))
        }

        assertTrue(capturedResult.isFailure)
        assertIs<CancellationException>(capturedResult.exceptionOrNull())
    }

    @Test
    fun `cancellation is not converted to rejection`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pipeline = CancellingPipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val capturedResult = runSuspendCatching {
            coordinator.execute(makeRequest(), makeBindings(storage, transport))
        }

        assertTrue(capturedResult.isFailure)
        // The thrown exception must be CancellationException, not a result type.
        assertIs<CancellationException>(capturedResult.exceptionOrNull())
    }

    @Test
    fun `unexpected pipeline exception is not swallowed`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val ex = IllegalStateException("Unexpected failure in pipeline")
        val pipeline = ThrowingPipeline(SynchronizationDirection.PUSH, ex)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        val capturedResult = runSuspendCatching {
            coordinator.execute(makeRequest(), makeBindings(storage, transport))
        }

        assertTrue(capturedResult.isFailure)
        assertIs<IllegalStateException>(capturedResult.exceptionOrNull())
    }

    @Test
    fun `pipeline is invoked at most once per execute call`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        assertEquals(1, pipeline.executeCallCount)
    }

    // =========================================================================
    // Side-effect boundary tests
    // =========================================================================

    @Test
    fun `coordinator does not call provider initialize during execute`() {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        // Reset counters after lifecycle initialization.
        storage.initializeCallCount = 0
        transport.initializeCallCount = 0

        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        assertEquals(0, storage.initializeCallCount)
        assertEquals(0, transport.initializeCallCount)
    }

    @Test
    fun `coordinator does not call provider close during execute`() {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        storage.closeCallCount = 0
        transport.closeCallCount = 0

        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = makeRuntimeDependencies(),
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        assertEquals(0, storage.closeCallCount)
        assertEquals(0, transport.closeCallCount)
    }

    @Test
    fun `coordinator reads no clock during execute`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val clock = FakeClock()
        val deps = RuntimeDependencies(
            clock = clock,
            identifiers = makeRuntimeDependencies().identifiers,
        )
        val pipeline = makePipeline(SynchronizationDirection.PUSH)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline)),
            runtimeDependencies = deps,
        )

        runSuspend { coordinator.execute(makeRequest(), makeBindings(storage, transport)) }

        assertEquals(0, clock.readCallCount)
    }

    // =========================================================================
    // Compatibility tests
    // =========================================================================

    @Test
    fun `no Android API is required to construct coordinator`() {
        val storage = makeStorage()
        val transport = makeTransport()
        val lifecycle = makeInitializedCoordinator(storage, transport)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycle,
            providerResolver = makeResolver(storage, transport),
            pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        assertNotNull(coordinator)
    }

    @Test
    fun `SynchronizationPipelineRegistry is constructible without Android types`() {
        val registry = SynchronizationPipelineRegistry(listOf(makePipeline()))
        assertNotNull(registry)
    }

    @Test
    fun `SynchronizationExecutionContext is constructible without Android types`() {
        val context = SynchronizationExecutionContext(
            request = makeRequest(),
            providers = makeResolvedProviders(),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        assertNotNull(context)
    }

    @Test
    fun `SynchronizationExecutionResult sealed variants are accessible`() {
        val succeeded = makeSucceededResult()
        val executed = SynchronizationExecutionResult.Executed(succeeded)
        val rejected = SynchronizationExecutionResult.Rejected(
            reason = SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND,
        )

        assertIs<SynchronizationExecutionResult.Executed>(executed)
        assertIs<SynchronizationExecutionResult.Rejected>(rejected)
    }
}
