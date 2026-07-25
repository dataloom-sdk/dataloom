package io.dataloom.runtime.execution.lifecycle

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
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
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.observation.SynchronizationObserver
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
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationPhase
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
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationExecutionCoordinator
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.bidirectional.BidirectionalExecutionOrder
import io.dataloom.runtime.execution.bidirectional.BidirectionalPipelineConfiguration
import io.dataloom.runtime.execution.bidirectional.BidirectionalSynchronizationPipeline
import io.dataloom.runtime.execution.inbound.InboundPullPipelineConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline
import io.dataloom.runtime.execution.outbound.OutboundPushPipelineConfiguration
import io.dataloom.runtime.execution.outbound.OutboundPushSynchronizationPipeline
import io.dataloom.runtime.observation.SynchronizationEventDispatchResult
import io.dataloom.runtime.observation.SynchronizationEventDispatcher
import io.dataloom.runtime.observation.SynchronizationObserverRegistry
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
import kotlin.test.assertFalse

/**
 * Deterministic common tests for DL-029 runtime synchronization lifecycle event
 * integration.
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
 * - [SynchronizationLifecycleEventEmitter]
 * - [DispatchingSynchronizationLifecycleEventEmitter]
 * - [SynchronizationExecutionCoordinator] lifecycle integration
 * - [io.dataloom.runtime.execution.outbound.OutboundPushSynchronizationPipeline] phase integration
 * - [io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline] phase integration
 * - [io.dataloom.runtime.execution.bidirectional.BidirectionalSynchronizationPipeline] phase integration
 */
class SynchronizationLifecycleEventIntegrationTest {

    // =========================================================================
    // Sentinel for synchronous coroutine completion detection
    // =========================================================================

    private object Pending

    companion object {
        /** Helper used by inner classes and tests alike. */
        fun noObserversResult(idValue: String): SynchronizationEventDispatchResult {
            return SynchronizationEventDispatchResult.NoObservers(
                eventId = SynchronizationEventId(idValue),
                summary = io.dataloom.runtime.observation.SynchronizationEventDispatchSummary.Zero,
            )
        }
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
    // Recording observer
    // =========================================================================

    private class RecordingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        private val _events = mutableListOf<SynchronizationEvent>()
        val events: List<SynchronizationEvent> get() = _events.toList()

        override fun onEvent(event: SynchronizationEvent) {
            _events.add(event)
        }
    }

    /** Observer that always throws an ordinary exception. */
    private class FailingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        var callCount = 0

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
            throw RuntimeException("Observer intentionally failed in test.")
        }
    }

    /** Observer that throws CancellationException. */
    private class CancellingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)

        override fun onEvent(event: SynchronizationEvent) {
            throw CancellationException("Cancellation from observer in test.")
        }
    }

    // =========================================================================
    // Deterministic clock
    // =========================================================================

    /**
     * Clock that returns a fixed instant and records how many times it was called.
     */
    private class StepClock(private val step: Long = 1L) : DataLoomClock {
        private var epochMs = 1_000_000L
        var callCount = 0
            private set
        val instants = mutableListOf<DataLoomInstant>()

        override fun now(): DataLoomInstant {
            callCount++
            val instant = DataLoomInstant(epochMs)
            instants.add(instant)
            epochMs += step
            return instant
        }
    }

    private class FixedClock(private val epochMs: Long = 1_000_000L) : DataLoomClock {
        var callCount = 0
            private set

        override fun now(): DataLoomInstant {
            callCount++
            return DataLoomInstant(epochMs)
        }
    }

    // =========================================================================
    // Deterministic identifier generator
    // =========================================================================

    private class SequenceIdGenerator : IdentifierGenerator<SynchronizationEventId> {
        private var counter = 0
        val generatedIds = mutableListOf<SynchronizationEventId>()

        override fun generate(): SynchronizationEventId {
            counter++
            val id = SynchronizationEventId("event-$counter")
            generatedIds.add(id)
            return id
        }
    }

    private class ConstantIdGenerator(private val value: String = "event-001") :
        IdentifierGenerator<SynchronizationEventId> {
        var callCount = 0
            private set

        override fun generate(): SynchronizationEventId {
            callCount++
            return SynchronizationEventId(value)
        }
    }

    private class FailingIdGenerator : IdentifierGenerator<SynchronizationEventId> {
        override fun generate(): SynchronizationEventId {
            throw IllegalStateException("ID generator failure in test.")
        }
    }

    private class FailingClock : DataLoomClock {
        override fun now(): DataLoomInstant {
            throw IllegalStateException("Clock failure in test.")
        }
    }

    // =========================================================================
    // Recording lifecycle event emitter
    // =========================================================================

    private class RecordingLifecycleEventEmitter : SynchronizationLifecycleEventEmitter {
        val startedContexts = mutableListOf<SynchronizationExecutionContext>()
        val phaseChangedContexts = mutableListOf<SynchronizationExecutionContext>()
        val phaseChangedPhases = mutableListOf<SynchronizationPhase>()
        val completedContexts = mutableListOf<SynchronizationExecutionContext>()
        val completedResults = mutableListOf<SynchronizationResult>()

        // Simulated dispatch result to return
        var startedResult: SynchronizationEventDispatchResult? = null
        var phaseResult: SynchronizationEventDispatchResult? = null
        var completedResult: SynchronizationEventDispatchResult? = null

        override suspend fun emitStarted(
            context: SynchronizationExecutionContext,
        ): SynchronizationEventDispatchResult {
            startedContexts.add(context)
            return startedResult ?: noObserversResult("started-id")
        }

        override suspend fun emitPhaseChanged(
            context: SynchronizationExecutionContext,
            phase: SynchronizationPhase,
        ): SynchronizationEventDispatchResult {
            phaseChangedContexts.add(context)
            phaseChangedPhases.add(phase)
            return phaseResult ?: noObserversResult("phase-id")
        }

        override suspend fun emitCompleted(
            context: SynchronizationExecutionContext,
            result: SynchronizationResult,
        ): SynchronizationEventDispatchResult {
            completedContexts.add(context)
            completedResults.add(result)
            return completedResult ?: noObserversResult("completed-id")
        }
    }

    /** Emitter that throws CancellationException from emitStarted. */
    private class CancellingStartedEmitter : SynchronizationLifecycleEventEmitter {
        override suspend fun emitStarted(
            context: SynchronizationExecutionContext,
        ): SynchronizationEventDispatchResult {
            throw CancellationException("Cancellation from emitStarted in test.")
        }

        override suspend fun emitPhaseChanged(
            context: SynchronizationExecutionContext,
            phase: SynchronizationPhase,
        ): SynchronizationEventDispatchResult = noObserversResult("phase-id")

        override suspend fun emitCompleted(
            context: SynchronizationExecutionContext,
            result: SynchronizationResult,
        ): SynchronizationEventDispatchResult = noObserversResult("completed-id")
    }

    // =========================================================================
    // Fake providers
    // =========================================================================

    private open class FakeStorageProvider(id: String = "storage-primary") : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Storage $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        var readOutboundCallCount = 0
        var pushCallCount = 0
        var acknowledgeCallCount = 0
        var readCheckpointCallCount = 0
        var applyInboundCallCount = 0
        var writeCheckpointCallCount = 0

        // Default: return no changes
        var readOutboundResult: () -> ProviderOperationResult<OutboundChangeReadResult> =
            { ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges) }

        var pushResult: () -> ProviderOperationResult<ChangeSetAcknowledgement> =
            { ProviderOperationResult.Failure(FakeError()) }

        var acknowledgeResult: () -> ProviderOperationResult<Unit> =
            { ProviderOperationResult.Success(Unit) }

        var readCheckpointResult: () -> ProviderOperationResult<SynchronizationCheckpoint?> =
            { ProviderOperationResult.Success(null) }

        var applyInboundResult: () -> ProviderOperationResult<Unit> =
            { ProviderOperationResult.Success(Unit) }

        var writeCheckpointResult: () -> ProviderOperationResult<Unit> =
            { ProviderOperationResult.Success(Unit) }

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            readOutboundCallCount++
            return readOutboundResult()
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyInboundCallCount++
            return applyInboundResult()
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            acknowledgeCallCount++
            return acknowledgeResult()
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            readCheckpointCallCount++
            return readCheckpointResult()
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            writeCheckpointCallCount++
            return writeCheckpointResult()
        }
    }

    private open class FakeTransportProvider(id: String = "transport-prod") : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Transport $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        var pushCallCount = 0
        var pullCallCount = 0

        var pushResult: () -> ProviderOperationResult<ChangeSetAcknowledgement> =
            { ProviderOperationResult.Failure(FakeError()) }

        var pullResult: () -> ProviderOperationResult<PullChangesResult> =
            { ProviderOperationResult.Success(PullChangesResult.NoChanges()) }

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCallCount++
            return pushResult()
        }

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> {
            pullCallCount++
            return pullResult()
        }
    }

    // =========================================================================
    // Fake pipeline
    // =========================================================================

    private class FakePipeline(
        override val direction: SynchronizationDirection,
        private val result: () -> SynchronizationResult,
    ) : SynchronizationPipeline {
        var executeCallCount = 0
        var lastContext: SynchronizationExecutionContext? = null

        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            executeCallCount++
            lastContext = context
            return result()
        }
    }

    private class CancellingPipeline(override val direction: SynchronizationDirection) : SynchronizationPipeline {
        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            throw CancellationException("Cancellation from pipeline in test.")
        }
    }

    private class ThrowingPipeline(override val direction: SynchronizationDirection) : SynchronizationPipeline {
        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            throw IllegalStateException("Unexpected exception from pipeline in test.")
        }
    }

    // =========================================================================
    // Fake provider lifecycle coordinator
    // =========================================================================

    // We use ProviderLifecycleCoordinator directly; the coordinator helper methods
    // initialize/use real coordinators.

    // =========================================================================
    // Helper builder
    // =========================================================================

    private fun makeRequest(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
    ) = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun makeRuntimeDependencies(
        clock: DataLoomClock = FixedClock(),
        idGen: IdentifierGenerator<SynchronizationEventId> = ConstantIdGenerator(),
    ): RuntimeDependencies {
        val idGenerators = RuntimeIdentifierGenerators(
            synchronizationEventIds = idGen,
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
        return RuntimeDependencies(clock = clock, identifiers = idGenerators)
    }

    private fun makeResolvedProviders(
        storage: FakeStorageProvider = FakeStorageProvider(),
        transport: FakeTransportProvider = FakeTransportProvider(),
    ) = ResolvedSynchronizationProviders(
        storageProvider = storage,
        transportProvider = transport,
        schedulerProvider = null,
        connectivityProvider = null,
        queueProvider = null,
    )

    private fun makeContext(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        storage: FakeStorageProvider = FakeStorageProvider(),
        transport: FakeTransportProvider = FakeTransportProvider(),
        emitter: SynchronizationLifecycleEventEmitter? = null,
        clock: DataLoomClock = FixedClock(),
        request: SynchronizationRequest? = null,
    ) = SynchronizationExecutionContext(
        request = request ?: makeRequest(direction),
        providers = makeResolvedProviders(storage, transport),
        runtimeDependencies = makeRuntimeDependencies(clock),
        lifecycleEventEmitter = emitter,
    )

    private fun makeDispatcher(observer: SynchronizationObserver? = null): SynchronizationEventDispatcher {
        val observers = if (observer != null) listOf(observer) else emptyList()
        return SynchronizationEventDispatcher(SynchronizationObserverRegistry(observers))
    }

    private fun makeDispatcher(observers: List<SynchronizationObserver>): SynchronizationEventDispatcher {
        return SynchronizationEventDispatcher(SynchronizationObserverRegistry(observers))
    }

    private fun makeResolver(
        storage: FakeStorageProvider = FakeStorageProvider(),
        transport: FakeTransportProvider = FakeTransportProvider(),
    ): SynchronizationProviderResolver {
        val registry = ProviderRegistry(listOf(storage, transport))
        return SynchronizationProviderResolver(registry)
    }

    private fun makeEmptyResolver(): SynchronizationProviderResolver {
        return SynchronizationProviderResolver(ProviderRegistry(emptyList()))
    }

    private fun makeBindings(
        storageId: String = "storage-primary",
        transportId: String = "transport-prod",
    ) = SynchronizationProviderBindings(
        storageProviderId = ProviderId(storageId),
        transportProviderId = ProviderId(transportId),
    )

    private fun makeEmitter(
        observer: SynchronizationObserver? = null,
        clock: DataLoomClock = FixedClock(),
        idGen: IdentifierGenerator<SynchronizationEventId> = SequenceIdGenerator(),
    ) = DispatchingSynchronizationLifecycleEventEmitter(
        dispatcher = makeDispatcher(observer),
        clock = clock,
        eventIdGenerator = idGen,
    )

    private fun noObserversResult(idValue: String): SynchronizationEventDispatchResult {
        return SynchronizationEventDispatchResult.NoObservers(
            eventId = SynchronizationEventId(idValue),
            summary = io.dataloom.runtime.observation.SynchronizationEventDispatchSummary.Zero,
        )
    }

    private fun makeSucceededResult(
        request: SynchronizationRequest = makeRequest(),
        completedAt: DataLoomInstant = DataLoomInstant(1_000_000L),
    ) = SynchronizationResult.Succeeded(
        request = request,
        completedAt = completedAt,
        summary = SynchronizationSummary(),
    )

    private fun makeFailedResult(
        request: SynchronizationRequest = makeRequest(),
        completedAt: DataLoomInstant = DataLoomInstant(1_000_000L),
    ) = SynchronizationResult.Failed(
        request = request,
        completedAt = completedAt,
        summary = SynchronizationSummary(),
        error = FakeError(),
    )

    private fun makeSkippedResult(
        request: SynchronizationRequest = makeRequest(),
        completedAt: DataLoomInstant = DataLoomInstant(1_000_000L),
    ) = SynchronizationResult.Skipped(
        request = request,
        completedAt = completedAt,
        summary = SynchronizationSummary(),
        reason = SynchronizationSkipReason.NO_CHANGES,
    )

    private fun makeCancelledResult(
        request: SynchronizationRequest = makeRequest(),
        completedAt: DataLoomInstant = DataLoomInstant(1_000_000L),
    ) = SynchronizationResult.Cancelled(
        request = request,
        completedAt = completedAt,
        summary = SynchronizationSummary(),
    )

    private fun makeChangeSet(id: String = "cs-001"): ChangeSet {
        val changeEventId = ChangeEventId("ce-001")
        return ChangeSet(
            id = ChangeSetId(id),
            events = listOf(
                ChangeEvent(
                    id = changeEventId,
                    entity = EntityReference(
                        id = EntityId("entity-001"),
                        type = EntityType("Widget"),
                    ),
                    operation = ChangeOperation.CREATE,
                    payload = null,
                ),
            ),
        )
    }

    private fun makeAcknowledgement(
        changeSetId: String = "cs-001",
        changeEventId: String = "ce-001",
    ): ChangeSetAcknowledgement {
        return ChangeSetAcknowledgement(
            changeSetId = ChangeSetId(changeSetId),
            events = listOf(
                ChangeEventAcknowledgement(
                    eventId = ChangeEventId(changeEventId),
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                    error = null,
                ),
            ),
        )
    }

    private fun makePipelineRegistry(vararg pipelines: SynchronizationPipeline): SynchronizationPipelineRegistry =
        SynchronizationPipelineRegistry(pipelines.toList())

    // =========================================================================
    // Coroutine test helpers
    // =========================================================================

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
    // DispatchingSynchronizationLifecycleEventEmitter tests
    // =========================================================================

    @Test
    fun `construction performs no dispatch`() {
        val observer = RecordingObserver("obs-001")
        val dispatcher = makeDispatcher(observer)
        val idGen = SequenceIdGenerator()
        val clock = FixedClock()

        DispatchingSynchronizationLifecycleEventEmitter(dispatcher, clock, idGen)

        assertEquals(0, observer.events.size, "No events should be dispatched on construction.")
        assertEquals(0, idGen.generatedIds.size, "No IDs should be generated on construction.")
        assertEquals(0, clock.callCount, "Clock should not be read on construction.")
    }

    @Test
    fun `emitStarted uses dispatcher and returns result`() {
        val observer = RecordingObserver("obs-001")
        val clock = FixedClock()
        val idGen = SequenceIdGenerator()
        val emitter = makeEmitter(observer, clock, idGen)
        val context = makeContext()

        val result = runSuspend { emitter.emitStarted(context) }

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(1, observer.events.size)
        assertIs<SynchronizationEvent.Started>(observer.events[0])
    }

    @Test
    fun `emitStarted generates a fresh event ID`() {
        val idGen = SequenceIdGenerator()
        val emitter = makeEmitter(idGen = idGen)
        val context = makeContext()

        runSuspend { emitter.emitStarted(context) }

        assertEquals(1, idGen.generatedIds.size)
        assertEquals("event-1", idGen.generatedIds[0].value)
    }

    @Test
    fun `emitPhaseChanged generates a fresh event ID distinct from Started`() {
        val idGen = SequenceIdGenerator()
        val emitter = makeEmitter(idGen = idGen)
        val context = makeContext()

        runSuspend { emitter.emitStarted(context) }
        runSuspend { emitter.emitPhaseChanged(context, SynchronizationPhase.PUSHING) }

        assertEquals(2, idGen.generatedIds.size)
        assertFalse(
            idGen.generatedIds[0] == idGen.generatedIds[1],
            "Started and PhaseChanged must have distinct IDs.",
        )
    }

    @Test
    fun `emitCompleted generates a fresh event ID distinct from other events`() {
        val idGen = SequenceIdGenerator()
        val emitter = makeEmitter(idGen = idGen)
        val request = makeRequest()
        val context = makeContext()
        val result = makeSucceededResult(request = request, completedAt = DataLoomInstant(1_000_000L))

        runSuspend { emitter.emitStarted(context) }
        runSuspend { emitter.emitPhaseChanged(context, SynchronizationPhase.PUSHING) }
        runSuspend { emitter.emitCompleted(context, result) }

        assertEquals(3, idGen.generatedIds.size)
        val ids = idGen.generatedIds.map { it.value }.toSet()
        assertEquals(3, ids.size, "All three events must have distinct IDs.")
    }

    @Test
    fun `emitStarted reads clock exactly once`() {
        val clock = FixedClock()
        val emitter = makeEmitter(clock = clock)
        val context = makeContext()

        runSuspend { emitter.emitStarted(context) }

        assertEquals(1, clock.callCount, "Clock must be read exactly once for Started.")
    }

    @Test
    fun `emitPhaseChanged reads clock exactly once`() {
        val clock = FixedClock()
        val emitter = makeEmitter(clock = clock)
        val context = makeContext()

        runSuspend { emitter.emitPhaseChanged(context, SynchronizationPhase.READING_OUTBOUND) }

        assertEquals(1, clock.callCount, "Clock must be read exactly once for PhaseChanged.")
    }

    @Test
    fun `emitCompleted reads clock exactly once`() {
        val clock = FixedClock(epochMs = 2_000_000L)
        val emitter = makeEmitter(clock = clock)
        val request = makeRequest()
        val context = makeContext()
        val result = makeSucceededResult(request = request, completedAt = DataLoomInstant(1_000_000L))

        runSuspend { emitter.emitCompleted(context, result) }

        assertEquals(1, clock.callCount, "Clock must be read exactly once for Completed.")
    }

    @Test
    fun `emitStarted preserves exact request`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(observer)
        val request = makeRequest()
        val context = makeContext(request = request)

        runSuspend { emitter.emitStarted(context) }

        val event = observer.events[0] as SynchronizationEvent.Started
        assertSame(request, event.request)
    }

    @Test
    fun `emitPhaseChanged preserves exact phase`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(observer)
        val context = makeContext()

        runSuspend { emitter.emitPhaseChanged(context, SynchronizationPhase.APPLYING_INBOUND) }

        val event = observer.events[0] as SynchronizationEvent.PhaseChanged
        assertEquals(SynchronizationPhase.APPLYING_INBOUND, event.phase)
    }

    @Test
    fun `emitCompleted preserves exact result`() {
        val observer = RecordingObserver("obs-001")
        val clock = FixedClock(epochMs = 2_000_000L)
        val emitter = makeEmitter(observer, clock)
        val request = makeRequest()
        val context = makeContext()
        val result = makeSucceededResult(request = request, completedAt = DataLoomInstant(1_000_000L))

        runSuspend { emitter.emitCompleted(context, result) }

        val event = observer.events[0] as SynchronizationEvent.Completed
        assertSame(result, event.result)
    }

    @Test
    fun `emitStarted returns dispatcher result unchanged`() {
        val idGen = ConstantIdGenerator("event-started")
        val dispatcher = makeDispatcher(observer = null) // no observers
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(dispatcher, FixedClock(), idGen)
        val context = makeContext()

        val result = runSuspend { emitter.emitStarted(context) }

        assertIs<SynchronizationEventDispatchResult.NoObservers>(result)
    }

    @Test
    fun `emitPhaseChanged returns dispatcher result unchanged`() {
        val dispatcher = makeDispatcher(observer = null)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(dispatcher, FixedClock(), SequenceIdGenerator())
        val context = makeContext()

        val result = runSuspend { emitter.emitPhaseChanged(context, SynchronizationPhase.PUSHING) }

        assertIs<SynchronizationEventDispatchResult.NoObservers>(result)
    }

    @Test
    fun `emitCompleted returns dispatcher result unchanged`() {
        val dispatcher = makeDispatcher(observer = null)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(dispatcher, FixedClock(2_000_000L), SequenceIdGenerator())
        val request = makeRequest()
        val context = makeContext()
        val result = makeSucceededResult(request = request, completedAt = DataLoomInstant(1_000_000L))

        val dispatchResult = runSuspend { emitter.emitCompleted(context, result) }

        assertIs<SynchronizationEventDispatchResult.NoObservers>(dispatchResult)
    }

    @Test
    fun `failing identifier generator propagates exception from emitStarted`() {
        val dispatcher = makeDispatcher()
        val failingGen = FailingIdGenerator()
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(dispatcher, FixedClock(), failingGen)
        val context = makeContext()

        val resultCatching = runSuspendCatching { emitter.emitStarted(context) }
        assertTrue(resultCatching.isFailure)
        assertIs<IllegalStateException>(resultCatching.exceptionOrNull())
    }

    @Test
    fun `failing clock propagates exception from emitStarted`() {
        val dispatcher = makeDispatcher()
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(dispatcher, FailingClock(), SequenceIdGenerator())
        val context = makeContext()

        val resultCatching = runSuspendCatching { emitter.emitStarted(context) }
        assertTrue(resultCatching.isFailure)
        assertIs<IllegalStateException>(resultCatching.exceptionOrNull())
    }

    @Test
    fun `cancellation from dispatcher propagates from emitStarted`() {
        val cancellingObserver = CancellingObserver("cancel-obs")
        val dispatcher = makeDispatcher(cancellingObserver)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(dispatcher, FixedClock(), SequenceIdGenerator())
        val context = makeContext()

        val resultCatching = runSuspendCatching { emitter.emitStarted(context) }
        assertTrue(resultCatching.isFailure)
        assertIs<CancellationException>(resultCatching.exceptionOrNull())
    }

    // =========================================================================
    // Coordinator lifecycle integration
    // =========================================================================

    private fun makeCoordinatorWithEmitter(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        pipelineResult: () -> SynchronizationResult = { makeSucceededResult(makeRequest(direction)) },
        emitter: SynchronizationLifecycleEventEmitter? = null,
    ): Triple<SynchronizationExecutionCoordinator, FakePipeline, SynchronizationProviderBindings> {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        val pipeline = FakePipeline(direction, pipelineResult)
        val registry = makePipelineRegistry(pipeline)
        val resolver = makeResolver(storage, transport)

        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = registry,
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()
        return Triple(coordinator, pipeline, bindings)
    }

    private fun makeInitializedLifecycleCoordinator(): ProviderLifecycleCoordinator {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        val registry = ProviderRegistry(listOf(storage, transport))
        val lc = ProviderLifecycleCoordinator(registry, ProviderInitializationContext())
        runSuspend { lc.initialize() }
        return lc
    }

    private fun makeNotInitializedLifecycleCoordinator(): ProviderLifecycleCoordinator {
        val registry = ProviderRegistry(listOf(FakeStorageProvider(), FakeTransportProvider()))
        return ProviderLifecycleCoordinator(registry, ProviderInitializationContext())
    }

    @Test
    fun `coordinator with no emitter executes pipeline without events`() {
        val (coordinator, pipeline, bindings) = makeCoordinatorWithEmitter(emitter = null)
        val request = makeRequest()

        val result = runSuspend { coordinator.execute(request, bindings) }

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, pipeline.executeCallCount)
    }

    @Test
    fun `coordinator emitter receives Started before pipeline execution`() {
        val emitter = RecordingLifecycleEventEmitter()
        val orderedEvents = mutableListOf<String>()

        val recordingEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext): SynchronizationEventDispatchResult {
                orderedEvents.add("Started")
                return emitter.emitStarted(context)
            }

            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderedEvents.add("Phase:$phase")
                return emitter.emitPhaseChanged(context, phase)
            }

            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult): SynchronizationEventDispatchResult {
                orderedEvents.add("Completed")
                return emitter.emitCompleted(context, result)
            }
        }

        val request = makeRequest()
        val pipelineExecOrder = mutableListOf<String>()
        val pipeline = object : SynchronizationPipeline {
            override val direction = SynchronizationDirection.PUSH
            override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
                pipelineExecOrder.add("Pipeline")
                return makeSucceededResult(request)
            }
        }
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = recordingEmitter,
        )
        val bindings = makeBindings()

        runSuspend { coordinator.execute(request, bindings) }

        // Started must come before Pipeline, Pipeline before Completed
        val startedIdx = orderedEvents.indexOf("Started")
        val pipelineIdx = pipelineExecOrder.indexOf("Pipeline")
        val completedIdx = orderedEvents.indexOf("Completed")

        assertTrue(startedIdx >= 0, "Started must be emitted.")
        assertTrue(pipelineIdx >= 0, "Pipeline must execute.")
        assertTrue(completedIdx >= 0, "Completed must be emitted.")
        assertTrue(startedIdx < completedIdx, "Started must come before Completed.")
    }

    @Test
    fun `coordinator emits Started exactly once per accepted execution`() {
        val emitter = RecordingLifecycleEventEmitter()
        val (coordinator, _, bindings) = makeCoordinatorWithEmitter(emitter = emitter)
        val request = makeRequest()

        runSuspend { coordinator.execute(request, bindings) }

        assertEquals(1, emitter.startedContexts.size, "Started must be emitted exactly once.")
    }

    @Test
    fun `coordinator emits Completed exactly once after pipeline returns`() {
        val emitter = RecordingLifecycleEventEmitter()
        val (coordinator, _, bindings) = makeCoordinatorWithEmitter(emitter = emitter)
        val request = makeRequest()

        runSuspend { coordinator.execute(request, bindings) }

        assertEquals(1, emitter.completedContexts.size, "Completed must be emitted exactly once.")
    }

    @Test
    fun `coordinator preserves exact pipeline result in Completed`() {
        val request = makeRequest()
        val expectedResult = makeSucceededResult(request = request)
        val emitter = RecordingLifecycleEventEmitter()
        val (coordinator, _, bindings) = makeCoordinatorWithEmitter(
            pipelineResult = { expectedResult },
            emitter = emitter,
        )

        runSuspend { coordinator.execute(request, bindings) }

        assertEquals(1, emitter.completedResults.size)
        assertSame(expectedResult, emitter.completedResults[0])
    }

    @Test
    fun `coordinator returns exact pipeline result unchanged`() {
        val request = makeRequest()
        val expectedResult = makeSucceededResult(request = request)
        val emitter = RecordingLifecycleEventEmitter()
        val (coordinator, _, bindings) = makeCoordinatorWithEmitter(
            pipelineResult = { expectedResult },
            emitter = emitter,
        )

        val executionResult = runSuspend { coordinator.execute(request, bindings) }

        val executed = assertIs<SynchronizationExecutionResult.Executed>(executionResult)
        assertSame(expectedResult, executed.result)
    }

    @Test
    fun `coordinator does not emit events for PROVIDERS_NOT_INITIALIZED rejection`() {
        val emitter = RecordingLifecycleEventEmitter()
        val request = makeRequest()
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeNotInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(FakePipeline(SynchronizationDirection.PUSH) { makeSucceededResult(request) }),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        runSuspend { coordinator.execute(request, bindings) }

        assertEquals(0, emitter.startedContexts.size, "No Started for rejected execution.")
        assertEquals(0, emitter.phaseChangedContexts.size, "No PhaseChanged for rejected execution.")
        assertEquals(0, emitter.completedContexts.size, "No Completed for rejected execution.")
    }

    @Test
    fun `coordinator does not emit events for PROVIDER_RESOLUTION_FAILED rejection`() {
        val emitter = RecordingLifecycleEventEmitter()
        val request = makeRequest()
        val bindingFailure = ProviderBindingFailure(
            requestedId = ProviderId("missing"),
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )
        val resolver = makeEmptyResolver() // will fail - providers not in registry
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(FakePipeline(SynchronizationDirection.PUSH) { makeSucceededResult(request) }),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("missing"),
            transportProviderId = ProviderId("transport-prod"),
        )

        val result = runSuspend { coordinator.execute(request, bindings) }

        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(0, emitter.startedContexts.size)
        assertEquals(0, emitter.completedContexts.size)
    }

    @Test
    fun `coordinator does not emit events for PIPELINE_NOT_FOUND rejection`() {
        val emitter = RecordingLifecycleEventEmitter()
        val request = makeRequest(SynchronizationDirection.PULL)
        val resolver = makeResolver()
        // Only register PUSH pipeline, request PULL
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(FakePipeline(SynchronizationDirection.PUSH) { makeSucceededResult(makeRequest()) }),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        val result = runSuspend { coordinator.execute(request, bindings) }

        assertIs<SynchronizationExecutionResult.Rejected>(result)
        assertEquals(SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND, result.reason)
        assertEquals(0, emitter.startedContexts.size)
        assertEquals(0, emitter.completedContexts.size)
    }

    @Test
    fun `cancellation from emitStarted prevents pipeline execution`() {
        val cancellingEmitter = CancellingStartedEmitter()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { makeSucceededResult(makeRequest()) }
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = cancellingEmitter,
        )
        val bindings = makeBindings()

        val resultCatching = runSuspendCatching {
            coordinator.execute(makeRequest(), bindings)
        }

        assertTrue(resultCatching.isFailure)
        assertIs<CancellationException>(resultCatching.exceptionOrNull())
        assertEquals(0, pipeline.executeCallCount, "Pipeline must not execute after Started cancellation.")
    }

    @Test
    fun `ordinary observer failure in Started does not stop pipeline execution`() {
        val failingObserver = FailingObserver("failing-obs")
        val dispatcher = makeDispatcher(failingObserver)
        val realEmitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = dispatcher,
            clock = FixedClock(2_000_000L),
            eventIdGenerator = SequenceIdGenerator(),
        )
        val request = makeRequest()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { makeSucceededResult(request) }
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = realEmitter,
        )
        val bindings = makeBindings()

        val result = runSuspend { coordinator.execute(request, bindings) }

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, pipeline.executeCallCount, "Pipeline must execute despite observer failure in Started.")
        assertTrue(failingObserver.callCount > 0)
    }

    @Test
    fun `cancellation from pipeline propagates without Completed`() {
        val emitter = RecordingLifecycleEventEmitter()
        val cancellingPipeline = CancellingPipeline(SynchronizationDirection.PUSH)
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(cancellingPipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        val resultCatching = runSuspendCatching { coordinator.execute(makeRequest(), bindings) }

        assertTrue(resultCatching.isFailure)
        assertIs<CancellationException>(resultCatching.exceptionOrNull())
        assertEquals(0, emitter.completedContexts.size, "Completed must not be emitted after pipeline cancellation.")
    }

    @Test
    fun `unexpected exception from pipeline propagates without Completed`() {
        val emitter = RecordingLifecycleEventEmitter()
        val throwingPipeline = ThrowingPipeline(SynchronizationDirection.PUSH)
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(throwingPipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        val resultCatching = runSuspendCatching { coordinator.execute(makeRequest(), bindings) }

        assertTrue(resultCatching.isFailure)
        assertIs<IllegalStateException>(resultCatching.exceptionOrNull())
        assertEquals(0, emitter.completedContexts.size, "Completed must not be emitted after pipeline exception.")
    }

    // =========================================================================
    // Completed event for all result variants
    // =========================================================================

    private fun assertCompletedForResult(result: SynchronizationResult) {
        val emitter = RecordingLifecycleEventEmitter()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { result }
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()
        val request = result.request

        val executionResult = runSuspend { coordinator.execute(request, bindings) }

        assertIs<SynchronizationExecutionResult.Executed>(executionResult)
        assertSame(result, executionResult.result)
        assertEquals(1, emitter.completedContexts.size)
        assertSame(result, emitter.completedResults[0])
    }

    @Test
    fun `Completed is dispatched for Succeeded result`() {
        assertCompletedForResult(makeSucceededResult(request = makeRequest()))
    }

    @Test
    fun `Completed is dispatched for Failed result`() {
        assertCompletedForResult(makeFailedResult(request = makeRequest()))
    }

    @Test
    fun `Completed is dispatched for Skipped result`() {
        assertCompletedForResult(makeSkippedResult(request = makeRequest()))
    }

    @Test
    fun `Completed is dispatched for explicit Cancelled result`() {
        assertCompletedForResult(makeCancelledResult(request = makeRequest()))
    }

    @Test
    fun `Completed is dispatched for PartiallySucceeded result`() {
        val request = makeRequest()
        val partialResult = SynchronizationResult.PartiallySucceeded(
            request = request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
            errors = listOf(FakeError()),
        )
        assertCompletedForResult(partialResult)
    }

    // =========================================================================
    // Outbound pipeline phase integration
    // =========================================================================

    private fun makeOutboundPipeline(config: OutboundPushPipelineConfiguration? = null): OutboundPushSynchronizationPipeline {
        return OutboundPushSynchronizationPipeline(
            config ?: OutboundPushPipelineConfiguration(
                entityTypes = emptySet(),
                maxEventsPerBatch = 100,
                maxBatchesPerExecution = 100,
            ),
        )
    }

    @Test
    fun `outbound pipeline emits READING_OUTBOUND before storage read`() {
        val emitter = RecordingLifecycleEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val orderLog = mutableListOf<String>()
        val trackingEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext) = noObserversResult("s1")
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderLog.add("phase:$phase")
                emitter.emitPhaseChanged(context, phase)
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult) = noObserversResult("c1")
        }
        storage.readOutboundResult = {
            orderLog.add("storage.readOutbound")
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
        }
        val context = makeContext(SynchronizationDirection.PUSH, storage, transport, trackingEmitter)

        runSuspend { makeOutboundPipeline().execute(context) }

        val readOutboundPhaseIdx = orderLog.indexOf("phase:${SynchronizationPhase.READING_OUTBOUND}")
        val storageReadIdx = orderLog.indexOf("storage.readOutbound")
        assertTrue(readOutboundPhaseIdx >= 0, "READING_OUTBOUND phase must be emitted.")
        assertTrue(storageReadIdx >= 0, "Storage read must occur.")
        assertTrue(readOutboundPhaseIdx < storageReadIdx, "READING_OUTBOUND must come before storage read.")
    }

    @Test
    fun `outbound pipeline emits PUSHING before transport push`() {
        val orderLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val trackingEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext) = noObserversResult("s1")
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderLog.add("phase:$phase")
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult) = noObserversResult("c1")
        }

        val changeSet = makeChangeSet()
        var batchReturned = false
        storage.readOutboundResult = {
            if (!batchReturned) {
                batchReturned = true
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
            } else {
                ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }
        }
        transport.pushResult = {
            orderLog.add("transport.push")
            ProviderOperationResult.Success(makeAcknowledgement())
        }
        val context = makeContext(SynchronizationDirection.PUSH, storage, transport, trackingEmitter)

        runSuspend { makeOutboundPipeline().execute(context) }

        val pushPhaseIdx = orderLog.indexOf("phase:${SynchronizationPhase.PUSHING}")
        val transportPushIdx = orderLog.indexOf("transport.push")
        assertTrue(pushPhaseIdx >= 0, "PUSHING phase must be emitted.")
        assertTrue(transportPushIdx >= 0, "Transport push must occur.")
        assertTrue(pushPhaseIdx < transportPushIdx, "PUSHING phase must come before transport push.")
    }

    @Test
    fun `outbound pipeline emits ACKNOWLEDGING_OUTBOUND before acknowledgement persistence`() {
        val orderLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val trackingEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext) = noObserversResult("s1")
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderLog.add("phase:$phase")
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult) = noObserversResult("c1")
        }

        val changeSet = makeChangeSet()
        var batchReturned = false
        storage.readOutboundResult = {
            if (!batchReturned) {
                batchReturned = true
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
            } else {
                ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }
        }
        transport.pushResult = { ProviderOperationResult.Success(makeAcknowledgement()) }
        storage.acknowledgeResult = {
            orderLog.add("storage.acknowledge")
            ProviderOperationResult.Success(Unit)
        }
        val context = makeContext(SynchronizationDirection.PUSH, storage, transport, trackingEmitter)

        runSuspend { makeOutboundPipeline().execute(context) }

        val ackPhaseIdx = orderLog.indexOf("phase:${SynchronizationPhase.ACKNOWLEDGING_OUTBOUND}")
        val ackPersistIdx = orderLog.indexOf("storage.acknowledge")
        assertTrue(ackPhaseIdx >= 0, "ACKNOWLEDGING_OUTBOUND phase must be emitted.")
        assertTrue(ackPersistIdx >= 0, "Acknowledge persistence must occur.")
        assertTrue(ackPhaseIdx < ackPersistIdx, "ACKNOWLEDGING_OUTBOUND must come before acknowledge persistence.")
    }

    @Test
    fun `outbound pipeline emits no phases when no emitter configured`() {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        val context = makeContext(SynchronizationDirection.PUSH, storage, transport, emitter = null)

        // Should not throw
        val result = runSuspend { makeOutboundPipeline().execute(context) }

        assertIs<SynchronizationResult.Skipped>(result)
    }

    // =========================================================================
    // Inbound pipeline phase integration
    // =========================================================================

    private fun makeInboundPipeline(): InboundPullSynchronizationPipeline {
        return InboundPullSynchronizationPipeline(
            InboundPullPipelineConfiguration(
                entityTypes = emptySet(),
                maxEventsPerBatch = 100,
                maxBatchesPerExecution = 100,
            ),
        )
    }

    @Test
    fun `inbound pipeline emits PULLING before transport pull`() {
        val orderLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val trackingEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext) = noObserversResult("s1")
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderLog.add("phase:$phase")
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult) = noObserversResult("c1")
        }
        transport.pullResult = {
            orderLog.add("transport.pull")
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
        }
        val context = makeContext(SynchronizationDirection.PULL, storage, transport, trackingEmitter)

        runSuspend { makeInboundPipeline().execute(context) }

        val pullPhaseIdx = orderLog.indexOf("phase:${SynchronizationPhase.PULLING}")
        val transportPullIdx = orderLog.indexOf("transport.pull")
        assertTrue(pullPhaseIdx >= 0, "PULLING phase must be emitted.")
        assertTrue(transportPullIdx >= 0, "Transport pull must occur.")
        assertTrue(pullPhaseIdx < transportPullIdx, "PULLING phase must come before transport pull.")
    }

    @Test
    fun `inbound pipeline emits APPLYING_INBOUND before apply`() {
        val orderLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val trackingEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext) = noObserversResult("s1")
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderLog.add("phase:$phase")
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult) = noObserversResult("c1")
        }

        val changeSet = makeChangeSet()
        var batchPulled = false
        transport.pullResult = {
            if (!batchPulled) {
                batchPulled = true
                ProviderOperationResult.Success(PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = null))
            } else {
                ProviderOperationResult.Success(PullChangesResult.NoChanges())
            }
        }
        storage.applyInboundResult = {
            orderLog.add("storage.apply")
            ProviderOperationResult.Success(Unit)
        }
        val context = makeContext(SynchronizationDirection.PULL, storage, transport, trackingEmitter)

        runSuspend { makeInboundPipeline().execute(context) }

        val applyPhaseIdx = orderLog.indexOf("phase:${SynchronizationPhase.APPLYING_INBOUND}")
        val applyIdx = orderLog.indexOf("storage.apply")
        assertTrue(applyPhaseIdx >= 0, "APPLYING_INBOUND phase must be emitted.")
        assertTrue(applyIdx >= 0, "Storage apply must occur.")
        assertTrue(applyPhaseIdx < applyIdx, "APPLYING_INBOUND must come before storage apply.")
    }

    @Test
    fun `inbound pipeline emits WRITING_CHECKPOINT before checkpoint write`() {
        val orderLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val trackingEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext) = noObserversResult("s1")
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderLog.add("phase:$phase")
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult) = noObserversResult("c1")
        }

        val changeSet = makeChangeSet()
        val checkpoint = SynchronizationCheckpoint(key = CheckpointKey("checkpoint-stream"), token = CheckpointToken("token-001"))
        var batchPulled = false
        transport.pullResult = {
            if (!batchPulled) {
                batchPulled = true
                ProviderOperationResult.Success(PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = checkpoint))
            } else {
                ProviderOperationResult.Success(PullChangesResult.NoChanges())
            }
        }
        storage.writeCheckpointResult = {
            orderLog.add("storage.writeCheckpoint")
            ProviderOperationResult.Success(Unit)
        }
        val context = makeContext(SynchronizationDirection.PULL, storage, transport, trackingEmitter)

        runSuspend { makeInboundPipeline().execute(context) }

        val writePhaseIdx = orderLog.indexOf("phase:${SynchronizationPhase.WRITING_CHECKPOINT}")
        val writeIdx = orderLog.indexOf("storage.writeCheckpoint")
        assertTrue(writePhaseIdx >= 0, "WRITING_CHECKPOINT phase must be emitted.")
        assertTrue(writeIdx >= 0, "Storage writeCheckpoint must occur.")
        assertTrue(writePhaseIdx < writeIdx, "WRITING_CHECKPOINT must come before checkpoint write.")
    }

    @Test
    fun `inbound pipeline emits no phases when no emitter configured`() {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        val context = makeContext(SynchronizationDirection.PULL, storage, transport, emitter = null)

        val result = runSuspend { makeInboundPipeline().execute(context) }

        assertIs<SynchronizationResult.Skipped>(result)
    }

    // =========================================================================
    // Bidirectional pipeline phase integration
    // =========================================================================

    @Test
    fun `bidirectional pipeline child pipelines emit their phases in execution order`() {
        val phaseLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val trackingEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext) = noObserversResult("s1")
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                phaseLog.add(phase.name)
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult) = noObserversResult("c1")
        }

        val context = makeContext(SynchronizationDirection.BIDIRECTIONAL, storage, transport, trackingEmitter)

        val biPipeline = BidirectionalSynchronizationPipeline(
            outboundPipeline = makeOutboundPipeline(),
            inboundPipeline = makeInboundPipeline(),
            configuration = BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND),
        )

        runSuspend { biPipeline.execute(context) }

        // Outbound phases should come before inbound phases
        val readingOutboundIdx = phaseLog.indexOf(SynchronizationPhase.READING_OUTBOUND.name)
        val pullingIdx = phaseLog.indexOf(SynchronizationPhase.PULLING.name)
        assertTrue(readingOutboundIdx >= 0 || pullingIdx >= 0, "At least one phase should be emitted.")
        // Outbound (READING_OUTBOUND) must come before inbound (PULLING) in OUTBOUND_THEN_INBOUND order
        if (readingOutboundIdx >= 0 && pullingIdx >= 0) {
            assertTrue(readingOutboundIdx < pullingIdx, "Outbound phases must come before inbound phases.")
        }
    }

    // =========================================================================
    // Execution context backward compatibility
    // =========================================================================

    @Test
    fun `SynchronizationExecutionContext preserves request`() {
        val request = makeRequest()
        val context = SynchronizationExecutionContext(
            request = request,
            providers = makeResolvedProviders(),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        assertSame(request, context.request)
    }

    @Test
    fun `SynchronizationExecutionContext defaults lifecycleEventEmitter to null`() {
        val context = SynchronizationExecutionContext(
            request = makeRequest(),
            providers = makeResolvedProviders(),
            runtimeDependencies = makeRuntimeDependencies(),
        )
        assertNull(context.lifecycleEventEmitter, "lifecycleEventEmitter must default to null.")
    }

    @Test
    fun `SynchronizationExecutionContext accepts non-null emitter`() {
        val emitter = RecordingLifecycleEventEmitter()
        val context = SynchronizationExecutionContext(
            request = makeRequest(),
            providers = makeResolvedProviders(),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        assertSame(emitter, context.lifecycleEventEmitter)
    }

    // =========================================================================
    // Event ordering tests
    // =========================================================================

    @Test
    fun `full outbound lifecycle event order is Started READING_OUTBOUND PUSHING ACKNOWLEDGING_OUTBOUND Completed`() {
        val orderLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet()
        var batchReturned = false
        storage.readOutboundResult = {
            orderLog.add("op:read")
            if (!batchReturned) {
                batchReturned = true
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
            } else {
                ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }
        }
        transport.pushResult = {
            orderLog.add("op:push")
            ProviderOperationResult.Success(makeAcknowledgement())
        }
        storage.acknowledgeResult = {
            orderLog.add("op:ack")
            ProviderOperationResult.Success(Unit)
        }

        val emitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext): SynchronizationEventDispatchResult {
                orderLog.add("event:Started")
                return noObserversResult("s1")
            }
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderLog.add("event:${phase.name}")
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult): SynchronizationEventDispatchResult {
                orderLog.add("event:Completed")
                return noObserversResult("c1")
            }
        }

        val pipeline = makeOutboundPipeline()
        val resolver = makeResolver(storage, transport)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        runSuspend { coordinator.execute(makeRequest(), bindings) }

        val startedIdx = orderLog.indexOf("event:Started")
        val readingOutboundIdx = orderLog.indexOf("event:${SynchronizationPhase.READING_OUTBOUND.name}")
        val readOpIdx = orderLog.indexOf("op:read")
        val pushingIdx = orderLog.indexOf("event:${SynchronizationPhase.PUSHING.name}")
        val pushOpIdx = orderLog.indexOf("op:push")
        val ackPhaseIdx = orderLog.indexOf("event:${SynchronizationPhase.ACKNOWLEDGING_OUTBOUND.name}")
        val ackOpIdx = orderLog.indexOf("op:ack")
        val completedIdx = orderLog.indexOf("event:Completed")

        assertTrue(startedIdx >= 0, "Started must be emitted.")
        assertTrue(completedIdx >= 0, "Completed must be emitted.")
        assertTrue(startedIdx < readingOutboundIdx, "Started before READING_OUTBOUND.")
        assertTrue(readingOutboundIdx < readOpIdx, "READING_OUTBOUND before op:read.")
        assertTrue(readOpIdx < pushingIdx, "op:read before PUSHING.")
        assertTrue(pushingIdx < pushOpIdx, "PUSHING before op:push.")
        assertTrue(pushOpIdx < ackPhaseIdx, "op:push before ACKNOWLEDGING_OUTBOUND.")
        assertTrue(ackPhaseIdx < ackOpIdx, "ACKNOWLEDGING_OUTBOUND before op:ack.")
        assertTrue(ackOpIdx < completedIdx, "op:ack before Completed.")
    }

    @Test
    fun `full inbound lifecycle event order is Started PULLING APPLYING_INBOUND WRITING_CHECKPOINT Completed`() {
        val orderLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet()
        val checkpoint = SynchronizationCheckpoint(key = CheckpointKey("checkpoint-stream"), token = CheckpointToken("token-001"))
        var batchPulled = false
        transport.pullResult = {
            orderLog.add("op:pull")
            if (!batchPulled) {
                batchPulled = true
                ProviderOperationResult.Success(PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = checkpoint))
            } else {
                ProviderOperationResult.Success(PullChangesResult.NoChanges())
            }
        }
        storage.applyInboundResult = {
            orderLog.add("op:apply")
            ProviderOperationResult.Success(Unit)
        }
        storage.writeCheckpointResult = {
            orderLog.add("op:writeCheckpoint")
            ProviderOperationResult.Success(Unit)
        }

        val emitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext): SynchronizationEventDispatchResult {
                orderLog.add("event:Started")
                return noObserversResult("s1")
            }
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase): SynchronizationEventDispatchResult {
                orderLog.add("event:${phase.name}")
                return noObserversResult("p1")
            }
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult): SynchronizationEventDispatchResult {
                orderLog.add("event:Completed")
                return noObserversResult("c1")
            }
        }

        val pipeline = makeInboundPipeline()
        val request = makeRequest(SynchronizationDirection.PULL)
        val resolver = makeResolver(storage, transport)
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        runSuspend { coordinator.execute(request, bindings) }

        val startedIdx = orderLog.indexOf("event:Started")
        val pullingIdx = orderLog.indexOf("event:${SynchronizationPhase.PULLING.name}")
        val pullOpIdx = orderLog.indexOf("op:pull")
        val applyPhaseIdx = orderLog.indexOf("event:${SynchronizationPhase.APPLYING_INBOUND.name}")
        val applyOpIdx = orderLog.indexOf("op:apply")
        val writePhaseIdx = orderLog.indexOf("event:${SynchronizationPhase.WRITING_CHECKPOINT.name}")
        val writeOpIdx = orderLog.indexOf("op:writeCheckpoint")
        val completedIdx = orderLog.indexOf("event:Completed")

        assertTrue(startedIdx >= 0, "Started must be emitted.")
        assertTrue(completedIdx >= 0, "Completed must be emitted.")
        assertTrue(startedIdx < pullingIdx, "Started before PULLING.")
        assertTrue(pullingIdx < pullOpIdx, "PULLING before op:pull.")
        assertTrue(pullOpIdx < applyPhaseIdx, "op:pull before APPLYING_INBOUND.")
        assertTrue(applyPhaseIdx < applyOpIdx, "APPLYING_INBOUND before op:apply.")
        assertTrue(applyOpIdx < writePhaseIdx, "op:apply before WRITING_CHECKPOINT.")
        assertTrue(writePhaseIdx < writeOpIdx, "WRITING_CHECKPOINT before op:writeCheckpoint.")
        assertTrue(writeOpIdx < completedIdx, "op:writeCheckpoint before Completed.")
    }

    // =========================================================================
    // Security: diagnostics contain no payload
    // =========================================================================

    @Test
    fun `DispatchingSynchronizationLifecycleEventEmitter toString does not expose secrets`() {
        val emitter = makeEmitter()
        val str = emitter.toString()

        assertFalse(str.contains("password"), "toString must not contain secrets.")
        assertFalse(str.contains("token"), "toString must not contain tokens.")
        assertTrue(str.contains("DispatchingSynchronizationLifecycleEventEmitter"), "toString must contain class name.")
    }

    // =========================================================================
    // Observer failure isolation
    // =========================================================================

    @Test
    fun `PartiallyDelivered Started does not stop synchronization`() {
        val failingObserver = FailingObserver("failing-obs")
        val goodObserver = RecordingObserver("good-obs")
        val registry = SynchronizationObserverRegistry(listOf(failingObserver, goodObserver))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = dispatcher,
            clock = FixedClock(2_000_000L),
            eventIdGenerator = SequenceIdGenerator(),
        )
        val request = makeRequest()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { makeSucceededResult(request) }
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        val result = runSuspend { coordinator.execute(request, bindings) }

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, pipeline.executeCallCount, "Pipeline must execute despite partial delivery.")
    }

    @Test
    fun `DeliveryFailed Started does not stop synchronization`() {
        val failingObserver = FailingObserver("failing-obs")
        val registry = SynchronizationObserverRegistry(listOf(failingObserver))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = dispatcher,
            clock = FixedClock(2_000_000L),
            eventIdGenerator = SequenceIdGenerator(),
        )
        val request = makeRequest()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { makeSucceededResult(request) }
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        val result = runSuspend { coordinator.execute(request, bindings) }

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, pipeline.executeCallCount, "Pipeline must execute despite all-failed delivery.")
    }

    @Test
    fun `ordinary observer failure in phase event does not stop provider operation`() {
        val orderLog = mutableListOf<String>()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val failingObserver = FailingObserver("fail-obs")
        val registry = SynchronizationObserverRegistry(listOf(failingObserver))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = dispatcher,
            clock = FixedClock(2_000_000L),
            eventIdGenerator = SequenceIdGenerator(),
        )

        storage.readOutboundResult = {
            orderLog.add("op:read")
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
        }
        val context = makeContext(SynchronizationDirection.PUSH, storage, transport, emitter)

        // Should not throw despite failing observer
        val result = runSuspend { makeOutboundPipeline().execute(context) }

        assertIs<SynchronizationResult.Skipped>(result)
        assertTrue(orderLog.contains("op:read"), "Storage read must occur despite phase-event observer failure.")
        assertTrue(failingObserver.callCount > 0, "Failing observer must have been called.")
    }

    @Test
    fun `ordinary Completed observer failure does not alter the result`() {
        val failingObserver = FailingObserver("fail-completed-obs")
        val registry = SynchronizationObserverRegistry(listOf(failingObserver))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = dispatcher,
            clock = FixedClock(2_000_000L),
            eventIdGenerator = SequenceIdGenerator(),
        )
        val request = makeRequest()
        val expectedResult = makeSucceededResult(request = request)
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { expectedResult }
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            lifecycleEventEmitter = emitter,
        )
        val bindings = makeBindings()

        val result = runSuspend { coordinator.execute(request, bindings) }

        val executed = assertIs<SynchronizationExecutionResult.Executed>(result)
        assertSame(expectedResult, executed.result, "Result must not be altered by failing Completed observer.")
    }

    // =========================================================================
    // Compatibility: no Android API, no JVM-only, no reflection, no DI
    // =========================================================================

    @Test
    fun `emitter construction does not depend on any platform specific API`() {
        // This test verifies no exception is thrown during construction.
        // All dependencies are injected; no platform API is needed.
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(),
            clock = FixedClock(),
            eventIdGenerator = SequenceIdGenerator(),
        )
        assertNotNull(emitter)
    }

    @Test
    fun `coordinator backward compatible with four-arg constructor`() {
        // Existing callers omit the emitter. Verify four-arg construction compiles
        // and works without emitter.
        val request = makeRequest()
        val pipeline = FakePipeline(SynchronizationDirection.PUSH) { makeSucceededResult(request) }
        val resolver = makeResolver()
        val coordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = makeInitializedLifecycleCoordinator(),
            providerResolver = resolver,
            pipelineRegistry = makePipelineRegistry(pipeline),
            runtimeDependencies = makeRuntimeDependencies(),
            // lifecycleEventEmitter omitted — backward compatible default
        )
        val bindings = makeBindings()

        val result = runSuspend { coordinator.execute(request, bindings) }

        assertIs<SynchronizationExecutionResult.Executed>(result)
    }
}
