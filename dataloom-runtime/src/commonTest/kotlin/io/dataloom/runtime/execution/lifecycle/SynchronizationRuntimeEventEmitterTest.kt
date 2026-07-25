package io.dataloom.runtime.execution.lifecycle

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.observation.SynchronizationObserver
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
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
import io.dataloom.api.synchronization.SynchronizationProgress
import io.dataloom.api.synchronization.SynchronizationProgressUnit
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.core.runtime.RuntimeDependencies
import io.dataloom.core.runtime.RuntimeIdentifierGenerators
import io.dataloom.runtime.conflict.ConflictDetectorRegistry
import io.dataloom.runtime.conflict.ConflictOrchestrationBindings
import io.dataloom.runtime.conflict.ConflictOrchestrationRequest
import io.dataloom.runtime.conflict.ConflictOrchestrationResult
import io.dataloom.runtime.conflict.ConflictResolverRegistry
import io.dataloom.runtime.conflict.SynchronizationConflictOrchestrator
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.inbound.InboundPullPipelineConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline
import io.dataloom.runtime.execution.outbound.OutboundPushPipelineConfiguration
import io.dataloom.runtime.execution.outbound.OutboundPushSynchronizationPipeline
import io.dataloom.runtime.observation.SynchronizationEventDispatchResult
import io.dataloom.runtime.observation.SynchronizationEventDispatchSummary
import io.dataloom.runtime.observation.SynchronizationEventDispatcher
import io.dataloom.runtime.observation.SynchronizationObserverRegistry
import io.dataloom.runtime.retry.RetryOrchestrationStatus
import io.dataloom.runtime.retry.RetrySchedulingConfiguration
import io.dataloom.runtime.retry.SynchronizationRetryOrchestrator
import io.dataloom.runtime.retry.SynchronizationRetryRequest
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
 * Deterministic common tests for DL-030 operational event integration.
 *
 * Covers:
 * - [SynchronizationRuntimeEventEmitter] extension of lifecycle emitter
 * - [DispatchingSynchronizationLifecycleEventEmitter] operational event methods
 * - [OutboundPushSynchronizationPipeline] progress integration
 * - [InboundPullSynchronizationPipeline] progress integration
 * - [SynchronizationRetryOrchestrator] RetryScheduled event integration
 * - [SynchronizationConflictOrchestrator] ConflictDetected event integration
 * - Observer failure isolation
 * - Cancellation propagation
 * - Event ordering
 *
 * All fakes are stateless or deterministically stateful. No real network,
 * real database, filesystem, Thread.sleep, arbitrary delay, Android API,
 * JVM-only API, reflection, ServiceLoader, system clock, random IDs,
 * production credentials, or personal data is used.
 */
class SynchronizationRuntimeEventEmitterTest {

    // =========================================================================
    // runSuspend helpers
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
    // Fake errors
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake recoverable error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Deterministic clock
    // =========================================================================

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

    private class FailingClock : DataLoomClock {
        override fun now(): DataLoomInstant {
            throw IllegalStateException("Clock failure in test.")
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

    private class FailingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        var callCount = 0

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
            throw RuntimeException("Observer intentionally failed.")
        }
    }

    private class CancellingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)

        override fun onEvent(event: SynchronizationEvent) {
            throw CancellationException("Cancellation from observer.")
        }
    }

    // =========================================================================
    // Recording runtime event emitter
    // =========================================================================

    private open class RecordingRuntimeEventEmitter : SynchronizationRuntimeEventEmitter {
        val startedCalls = mutableListOf<SynchronizationExecutionContext>()
        val phaseChangedPhases = mutableListOf<SynchronizationPhase>()
        val completedResults = mutableListOf<SynchronizationResult>()
        val progressUpdatedList = mutableListOf<SynchronizationProgress>()
        val retryScheduledCalls = mutableListOf<Triple<RetryAttempt, SchedulingDelay, DataLoomError>>()
        val conflictDetectedList = mutableListOf<SynchronizationConflict>()

        var dispatchResult: SynchronizationEventDispatchResult = makeNoObserversResult()

        fun makeNoObserversResult() = SynchronizationEventDispatchResult.NoObservers(
            eventId = SynchronizationEventId("recorded"),
            summary = SynchronizationEventDispatchSummary.Zero,
        )

        override suspend fun emitStarted(
            context: SynchronizationExecutionContext,
        ): SynchronizationEventDispatchResult {
            startedCalls.add(context)
            return dispatchResult
        }

        override suspend fun emitPhaseChanged(
            context: SynchronizationExecutionContext,
            phase: SynchronizationPhase,
        ): SynchronizationEventDispatchResult {
            phaseChangedPhases.add(phase)
            return dispatchResult
        }

        override suspend fun emitCompleted(
            context: SynchronizationExecutionContext,
            result: SynchronizationResult,
        ): SynchronizationEventDispatchResult {
            completedResults.add(result)
            return dispatchResult
        }

        override suspend fun emitProgressUpdated(
            request: SynchronizationRequest,
            progress: SynchronizationProgress,
        ): SynchronizationEventDispatchResult {
            progressUpdatedList.add(progress)
            return dispatchResult
        }

        override suspend fun emitRetryScheduled(
            request: SynchronizationRequest,
            attempt: RetryAttempt,
            delay: SchedulingDelay,
            error: DataLoomError,
        ): SynchronizationEventDispatchResult {
            retryScheduledCalls.add(Triple(attempt, delay, error))
            return dispatchResult
        }

        override suspend fun emitConflictDetected(
            request: SynchronizationRequest,
            conflict: SynchronizationConflict,
        ): SynchronizationEventDispatchResult {
            conflictDetectedList.add(conflict)
            return dispatchResult
        }
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

        var readOutboundResult: () -> ProviderOperationResult<OutboundChangeReadResult> =
            { ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges) }

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
        ): ProviderOperationResult<OutboundChangeReadResult> = readOutboundResult()

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = applyInboundResult()

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = acknowledgeResult()

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = readCheckpointResult()

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = writeCheckpointResult()
    }

    private open class FakeTransportProvider(id: String = "transport-prod") : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Transport $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

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

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            pushResult()

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            pullResult()
    }

    // =========================================================================
    // Fake retry policy
    // =========================================================================

    private class AlwaysRetryPolicy(
        private val delay: SchedulingDelay = SchedulingDelay(1000L),
    ) : RetryPolicy {
        override val id: io.dataloom.api.identifier.RetryPolicyId =
            io.dataloom.api.identifier.RetryPolicyId("always-retry")

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Retry(delay = delay)
    }

    private class AlwaysStopPolicy : RetryPolicy {
        override val id: io.dataloom.api.identifier.RetryPolicyId =
            io.dataloom.api.identifier.RetryPolicyId("always-stop")

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
    }

    // =========================================================================
    // Fake scheduler
    // =========================================================================

    private class SuccessSchedulerProvider : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-001"),
            name = ProviderName("Fake Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )
        var callCount = 0

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> {
            callCount++
            return ProviderOperationResult.Success(ScheduleReceipt(id = request.id))
        }

        override suspend fun cancel(request: ScheduleCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    private class FailingSchedulerProvider : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-failing"),
            name = ProviderName("Failing Scheduler"),
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
            ProviderOperationResult.Success(Unit)
    }

    // =========================================================================
    // Fake conflict detectors and resolvers
    // =========================================================================

    private class FakeDetector(
        override val id: ConflictDetectorId,
        private val result: ConflictDetectionResult,
    ) : ConflictDetector {
        var invokeCount = 0

        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
            invokeCount++
            return result
        }
    }

    private open class FakeResolver(
        override val id: ConflictResolverId,
        private val decision: ConflictResolutionDecision = ConflictResolutionDecision.UseLocal(),
    ) : ConflictResolver {
        var invokeCount = 0

        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
            invokeCount++
            return decision
        }
    }

    // =========================================================================
    // Helper builders
    // =========================================================================

    private fun makeDispatcher(vararg observers: SynchronizationObserver): SynchronizationEventDispatcher {
        val registry = SynchronizationObserverRegistry(observers.toList())
        return SynchronizationEventDispatcher(registry)
    }

    private fun makeEmitter(
        dispatcher: SynchronizationEventDispatcher = makeDispatcher(),
        clock: DataLoomClock = FixedClock(),
        idGen: IdentifierGenerator<SynchronizationEventId> = SequenceIdGenerator(),
    ): DispatchingSynchronizationLifecycleEventEmitter =
        DispatchingSynchronizationLifecycleEventEmitter(dispatcher, clock, idGen)

    private fun makeRequest(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        workflowId: String = "workflow-001",
        sessionId: String = "session-001",
    ) = SynchronizationRequest(
        workflowId = WorkflowId(workflowId),
        sessionId = SynchronizationSessionId(sessionId),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun makeRuntimeDependencies(
        clock: DataLoomClock = FixedClock(),
    ) = RuntimeDependencies(
        clock = clock,
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = ConstantIdGenerator(),
            queueEntryIds = object : IdentifierGenerator<QueueEntryId> {
                override fun generate() = QueueEntryId("queue-001")
            },
            queueLeaseIds = object : IdentifierGenerator<QueueLeaseId> {
                override fun generate() = io.dataloom.api.identifier.QueueLeaseId("lease-001")
            },
            conflictIds = object : IdentifierGenerator<ConflictId> {
                override fun generate() = ConflictId("conflict-001")
            },
        ),
    )

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
        request: SynchronizationRequest = makeRequest(),
        emitter: SynchronizationLifecycleEventEmitter? = null,
        storage: FakeStorageProvider = FakeStorageProvider(),
        transport: FakeTransportProvider = FakeTransportProvider(),
    ) = SynchronizationExecutionContext(
        request = request,
        providers = makeResolvedProviders(storage, transport),
        runtimeDependencies = makeRuntimeDependencies(),
        lifecycleEventEmitter = emitter,
    )

    private fun makeOutboundPipeline(
        maxEventsPerBatch: Int = 100,
        maxBatchesPerExecution: Int = 10,
    ) = OutboundPushSynchronizationPipeline(
        OutboundPushPipelineConfiguration(
            maxEventsPerBatch = maxEventsPerBatch,
            maxBatchesPerExecution = maxBatchesPerExecution,
        ),
    )

    private fun makeInboundPipeline(
        maxEventsPerBatch: Int = 100,
        maxBatchesPerExecution: Int = 10,
    ) = InboundPullSynchronizationPipeline(
        InboundPullPipelineConfiguration(
            maxEventsPerBatch = maxEventsPerBatch,
            maxBatchesPerExecution = maxBatchesPerExecution,
        ),
    )

    private fun makeChangeSet(
        id: String = "cs-001",
        eventCount: Int = 2,
    ): ChangeSet {
        val entityRef = EntityReference(EntityType("invoice"), EntityId("entity-001"))
        return ChangeSet(
            id = ChangeSetId(id),
            events = (1..eventCount).map { i ->
                ChangeEvent(
                    id = ChangeEventId("$id-event-$i"),
                    entity = entityRef,
                    operation = ChangeOperation.UPDATE,
                )
            },
        )
    }

    private fun makeAcknowledgement(changeSet: ChangeSet): ChangeSetAcknowledgement =
        ChangeSetAcknowledgement(
            changeSetId = changeSet.id,
            events = changeSet.events.map { event ->
                ChangeEventAcknowledgement(
                    eventId = event.id,
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                    error = null,
                )
            },
        )

    private fun makeRetryRequest(
        request: SynchronizationRequest = makeRequest(),
        syncResult: SynchronizationResult = SynchronizationResult.Failed(
            request = makeRequest(),
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
            error = FakeError(),
        ),
        attempt: RetryAttempt = RetryAttempt(1),
    ) = SynchronizationRetryRequest(
        synchronizationRequest = request,
        synchronizationResult = syncResult,
        retryOperation = RetryOperation("sync"),
        retryAttempt = attempt,
        scheduleId = ScheduleId("schedule-001"),
    )

    private fun makeRetryOrchestrator(
        policy: RetryPolicy = AlwaysRetryPolicy(),
        scheduler: SchedulerProvider? = SuccessSchedulerProvider(),
        emitter: SynchronizationRuntimeEventEmitter? = null,
    ) = SynchronizationRetryOrchestrator(
        retryPolicy = policy,
        schedulerProvider = scheduler,
        configuration = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        ),
        eventEmitter = emitter,
    )

    private val sampleConflict = SynchronizationConflict(
        id = ConflictId("conflict-001"),
        type = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("invoice"), EntityId("entity-001")),
        localChange = ChangeEvent(
            id = ChangeEventId("event-local"),
            entity = EntityReference(
                EntityType("invoice"),
                EntityId("entity-001"),
                EntityVersion("v1"),
            ),
            operation = ChangeOperation.UPDATE,
        ),
        remoteChange = ChangeEvent(
            id = ChangeEventId("event-remote"),
            entity = EntityReference(
                EntityType("invoice"),
                EntityId("entity-001"),
                EntityVersion("v2"),
            ),
            operation = ChangeOperation.UPDATE,
        ),
    )

    private fun makeConflictRequest(
        detectorId: String,
        resolverId: String? = null,
        syncRequest: SynchronizationRequest = makeRequest(),
    ) = ConflictOrchestrationRequest(
        detectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = sampleConflict.localChange,
            remoteChange = sampleConflict.remoteChange,
        ),
        bindings = ConflictOrchestrationBindings(
            detectorId = ConflictDetectorId(detectorId),
            resolverId = resolverId?.let { ConflictResolverId(it) },
        ),
    )

    private fun makeConflictOrchestrator(
        detectors: List<ConflictDetector> = emptyList(),
        resolvers: List<ConflictResolver> = emptyList(),
        emitter: SynchronizationRuntimeEventEmitter? = null,
    ) = SynchronizationConflictOrchestrator(
        detectorRegistry = ConflictDetectorRegistry(detectors),
        resolverRegistry = ConflictResolverRegistry(resolvers),
        eventEmitter = emitter,
    )

    private fun noObserversResult(id: String = "event-001") =
        SynchronizationEventDispatchResult.NoObservers(
            eventId = SynchronizationEventId(id),
            summary = SynchronizationEventDispatchSummary.Zero,
        )

    // =========================================================================
    // Event-emitter extension: DispatchingSynchronizationLifecycleEventEmitter
    // also implements SynchronizationRuntimeEventEmitter
    // =========================================================================

    @Test
    fun `DispatchingSynchronizationLifecycleEventEmitter is a SynchronizationRuntimeEventEmitter`() {
        val emitter = makeEmitter()
        assertIs<SynchronizationRuntimeEventEmitter>(emitter)
    }

    @Test
    fun `existing emitStarted behavior is unchanged`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(dispatcher = makeDispatcher(observer))
        val context = makeContext()

        runSuspend { emitter.emitStarted(context) }

        assertEquals(1, observer.events.size)
        assertIs<SynchronizationEvent.Started>(observer.events[0])
    }

    @Test
    fun `existing emitPhaseChanged behavior is unchanged`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(dispatcher = makeDispatcher(observer))
        val context = makeContext()

        runSuspend { emitter.emitPhaseChanged(context, SynchronizationPhase.PUSHING) }

        assertEquals(1, observer.events.size)
        val event = observer.events[0]
        assertIs<SynchronizationEvent.PhaseChanged>(event)
        assertEquals(SynchronizationPhase.PUSHING, event.phase)
    }

    @Test
    fun `existing emitCompleted behavior is unchanged`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(dispatcher = makeDispatcher(observer))
        val context = makeContext()
        val result = SynchronizationResult.Skipped(
            request = context.request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
            reason = SynchronizationSkipReason.NO_CHANGES,
        )

        runSuspend { emitter.emitCompleted(context, result) }

        assertEquals(1, observer.events.size)
        assertIs<SynchronizationEvent.Completed>(observer.events[0])
    }

    @Test
    fun `emitProgressUpdated uses a fresh event ID`() {
        val idGen = SequenceIdGenerator()
        val emitter = makeEmitter(idGen = idGen)
        val request = makeRequest()
        val progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 5L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        runSuspend { emitter.emitProgressUpdated(request, progress) }

        assertEquals(1, idGen.generatedIds.size)
        assertEquals("event-1", idGen.generatedIds[0].value)
    }

    @Test
    fun `emitRetryScheduled uses a fresh event ID`() {
        val idGen = SequenceIdGenerator()
        val emitter = makeEmitter(idGen = idGen)
        val request = makeRequest()

        runSuspend {
            emitter.emitRetryScheduled(
                request = request,
                attempt = RetryAttempt(1),
                delay = SchedulingDelay(1000L),
                error = FakeError(),
            )
        }

        assertEquals(1, idGen.generatedIds.size)
        assertEquals("event-1", idGen.generatedIds[0].value)
    }

    @Test
    fun `emitConflictDetected uses a fresh event ID`() {
        val idGen = SequenceIdGenerator()
        val emitter = makeEmitter(idGen = idGen)
        val request = makeRequest()

        runSuspend { emitter.emitConflictDetected(request, sampleConflict) }

        assertEquals(1, idGen.generatedIds.size)
        assertEquals("event-1", idGen.generatedIds[0].value)
    }

    @Test
    fun `each emission uses a distinct event ID`() {
        val idGen = SequenceIdGenerator()
        val emitter = makeEmitter(idGen = idGen)
        val context = makeContext()
        val request = makeRequest()
        val progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 1L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )
        val result = SynchronizationResult.Skipped(
            request = context.request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
            reason = SynchronizationSkipReason.NO_CHANGES,
        )

        runSuspend {
            emitter.emitStarted(context)
            emitter.emitProgressUpdated(request, progress)
            emitter.emitRetryScheduled(
                request = request,
                attempt = RetryAttempt(1),
                delay = SchedulingDelay(1000L),
                error = FakeError(),
            )
            emitter.emitConflictDetected(request, sampleConflict)
            emitter.emitCompleted(context, result)
        }

        // 5 events = 5 distinct IDs
        assertEquals(5, idGen.generatedIds.size)
        assertEquals(
            idGen.generatedIds.size,
            idGen.generatedIds.map { it.value }.toSet().size,
            "All event IDs must be distinct.",
        )
    }

    @Test
    fun `injected clock supplies timestamps for ProgressUpdated`() {
        val clock = StepClock(step = 100L)
        val emitter = makeEmitter(clock = clock)
        val observer = RecordingObserver("obs-001")
        val dispatchingEmitter = DispatchingSynchronizationLifecycleEventEmitter(
            makeDispatcher(observer),
            clock,
            SequenceIdGenerator(),
        )
        val request = makeRequest()
        val progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 1L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        runSuspend { dispatchingEmitter.emitProgressUpdated(request, progress) }

        val event = observer.events.single() as SynchronizationEvent.ProgressUpdated
        assertEquals(1_000_000L, event.occurredAt.epochMilliseconds)
        assertEquals(1, clock.callCount, "Clock should be read exactly once.")
    }

    @Test
    fun `injected clock supplies timestamps for RetryScheduled`() {
        val clock = StepClock(step = 100L)
        val observer = RecordingObserver("obs-001")
        val dispatchingEmitter = DispatchingSynchronizationLifecycleEventEmitter(
            makeDispatcher(observer),
            clock,
            SequenceIdGenerator(),
        )
        val request = makeRequest()

        runSuspend {
            dispatchingEmitter.emitRetryScheduled(
                request = request,
                attempt = RetryAttempt(1),
                delay = SchedulingDelay(1000L),
                error = FakeError(),
            )
        }

        val event = observer.events.single() as SynchronizationEvent.RetryScheduled
        assertEquals(1_000_000L, event.occurredAt.epochMilliseconds)
        assertEquals(1, clock.callCount)
    }

    @Test
    fun `injected clock supplies timestamps for ConflictDetected`() {
        val clock = StepClock(step = 100L)
        val observer = RecordingObserver("obs-001")
        val dispatchingEmitter = DispatchingSynchronizationLifecycleEventEmitter(
            makeDispatcher(observer),
            clock,
            SequenceIdGenerator(),
        )
        val request = makeRequest()

        runSuspend { dispatchingEmitter.emitConflictDetected(request, sampleConflict) }

        val event = observer.events.single() as SynchronizationEvent.ConflictDetected
        assertEquals(1_000_000L, event.occurredAt.epochMilliseconds)
        assertEquals(1, clock.callCount)
    }

    @Test
    fun `exact dispatcher result is returned for ProgressUpdated`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(dispatcher = makeDispatcher(observer))
        val request = makeRequest()
        val progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 1L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        val result = runSuspend { emitter.emitProgressUpdated(request, progress) }

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
    }

    @Test
    fun `no event construction when emitter is null in outbound pipeline`() {
        val idGen = ConstantIdGenerator()
        val clock = FixedClock()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        storage.readOutboundResult = { ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)) }
        transport.pushResult = { ProviderOperationResult.Success(makeAcknowledgement(changeSet)) }

        val context = makeContext(
            emitter = null,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeOutboundPipeline()

        runSuspend { pipeline.execute(context) }

        assertEquals(0, idGen.callCount, "No ID should be generated when emitter is null.")
        assertEquals(0, clock.callCount, "No clock read should occur when emitter is null.")
    }

    @Test
    fun `no event construction when emitter does not support runtime events`() {
        // When a SynchronizationLifecycleEventEmitter that is NOT a
        // SynchronizationRuntimeEventEmitter is provided, no progress event is emitted.
        val idGen = ConstantIdGenerator()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        storage.readOutboundResult = { ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)) }
        transport.pushResult = { ProviderOperationResult.Success(makeAcknowledgement(changeSet)) }

        val nonRuntimeEmitter = object : SynchronizationLifecycleEventEmitter {
            override suspend fun emitStarted(context: SynchronizationExecutionContext) = noObserversResult()
            override suspend fun emitPhaseChanged(context: SynchronizationExecutionContext, phase: SynchronizationPhase) = noObserversResult()
            override suspend fun emitCompleted(context: SynchronizationExecutionContext, result: SynchronizationResult) = noObserversResult()
        }

        val context = makeContext(
            emitter = nonRuntimeEmitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeOutboundPipeline()
        // Should not throw - just skips progress emission silently
        val result = runSuspend { pipeline.execute(context) }
        assertIs<SynchronizationResult.Succeeded>(result)
    }

    // =========================================================================
    // ProgressUpdated event construction
    // =========================================================================

    @Test
    fun `emitProgressUpdated constructs ProgressUpdated with exact request and progress`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(dispatcher = makeDispatcher(observer))
        val request = makeRequest()
        val progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 7L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        runSuspend { emitter.emitProgressUpdated(request, progress) }

        val event = observer.events.single() as SynchronizationEvent.ProgressUpdated
        assertSame(request, event.request)
        assertSame(progress, event.progress)
        assertEquals(SynchronizationPhase.PUSHING, event.progress.phase)
        assertEquals(7L, event.progress.completed)
        assertNull(event.progress.total)
        assertEquals(SynchronizationProgressUnit.EVENTS, event.progress.unit)
    }

    @Test
    fun `emitRetryScheduled constructs RetryScheduled with exact fields`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(dispatcher = makeDispatcher(observer))
        val request = makeRequest()
        val attempt = RetryAttempt(3)
        val delay = SchedulingDelay(5000L)
        val error = FakeError()

        runSuspend {
            emitter.emitRetryScheduled(
                request = request,
                attempt = attempt,
                delay = delay,
                error = error,
            )
        }

        val event = observer.events.single() as SynchronizationEvent.RetryScheduled
        assertSame(request, event.request)
        assertSame(attempt, event.attempt)
        assertSame(delay, event.delay)
        assertSame(error, event.error)
    }

    @Test
    fun `emitConflictDetected constructs ConflictDetected with exact conflict`() {
        val observer = RecordingObserver("obs-001")
        val emitter = makeEmitter(dispatcher = makeDispatcher(observer))
        val request = makeRequest()

        runSuspend { emitter.emitConflictDetected(request, sampleConflict) }

        val event = observer.events.single() as SynchronizationEvent.ConflictDetected
        assertSame(request, event.request)
        assertSame(sampleConflict, event.conflict)
    }

    // =========================================================================
    // Event-ID generator and clock failure propagation
    // =========================================================================

    @Test
    fun `ID generator exception propagates from emitProgressUpdated`() {
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            makeDispatcher(),
            FixedClock(),
            FailingIdGenerator(),
        )
        val request = makeRequest()
        val progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 1L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        assertFailsWith<IllegalStateException> {
            runSuspend { emitter.emitProgressUpdated(request, progress) }
        }
    }

    @Test
    fun `clock exception propagates from emitRetryScheduled`() {
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            makeDispatcher(),
            FailingClock(),
            ConstantIdGenerator(),
        )
        val request = makeRequest()

        assertFailsWith<IllegalStateException> {
            runSuspend {
                emitter.emitRetryScheduled(
                    request = request,
                    attempt = RetryAttempt(1),
                    delay = SchedulingDelay(1000L),
                    error = FakeError(),
                )
            }
        }
    }

    @Test
    fun `cancellation propagates from emitConflictDetected`() {
        val cancellingEmitter = DispatchingSynchronizationLifecycleEventEmitter(
            SynchronizationEventDispatcher(
                SynchronizationObserverRegistry(listOf(CancellingObserver("obs-cancel"))),
            ),
            FixedClock(),
            ConstantIdGenerator(),
        )
        val request = makeRequest()

        assertFailsWith<CancellationException> {
            runSuspend { cancellingEmitter.emitConflictDetected(request, sampleConflict) }
        }
    }

    // =========================================================================
    // Outbound pipeline progress integration
    // =========================================================================

    @Test
    fun `no progress for initial NoChanges in outbound pipeline`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        storage.readOutboundResult = { ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges) }

        val context = makeContext(emitter = recordingEmitter, storage = storage)
        val pipeline = makeOutboundPipeline()

        val result = runSuspend { pipeline.execute(context) }

        assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(0, recordingEmitter.progressUpdatedList.size, "No progress for initial NoChanges.")
    }

    @Test
    fun `one progress event after one completed outbound batch`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001", eventCount = 3)
        var readCount = 0
        storage.readOutboundResult = {
            readCount++
            if (readCount == 1) {
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
            } else {
                ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }
        }
        transport.pushResult = { ProviderOperationResult.Success(makeAcknowledgement(changeSet)) }

        val context = makeContext(emitter = recordingEmitter, storage = storage, transport = transport)
        val pipeline = makeOutboundPipeline()

        runSuspend { pipeline.execute(context) }

        assertEquals(1, recordingEmitter.progressUpdatedList.size, "Exactly one progress event per batch.")
        val progress = recordingEmitter.progressUpdatedList[0]
        assertEquals(SynchronizationPhase.PUSHING, progress.phase)
        assertEquals(3L, progress.completed, "Cumulative events read: 3.")
        assertNull(progress.total, "Total is unknown; must be null.")
        assertEquals(SynchronizationProgressUnit.EVENTS, progress.unit)
    }

    @Test
    fun `multiple completed outbound batches emit ordered progress`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val cs1 = makeChangeSet("cs-001", eventCount = 2)
        val cs2 = makeChangeSet("cs-002", eventCount = 3)
        var readCount = 0
        storage.readOutboundResult = {
            readCount++
            when (readCount) {
                1 -> ProviderOperationResult.Success(OutboundChangeReadResult.Changes(cs1, hasMore = true))
                2 -> ProviderOperationResult.Success(OutboundChangeReadResult.Changes(cs2, hasMore = false))
                else -> ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }
        }
        transport.pushResult = {
            when (readCount) {
                1 -> ProviderOperationResult.Success(makeAcknowledgement(cs1))
                else -> ProviderOperationResult.Success(makeAcknowledgement(cs2))
            }
        }
        var pushCount = 0
        transport.pushResult = {
            pushCount++
            when (pushCount) {
                1 -> ProviderOperationResult.Success(makeAcknowledgement(cs1))
                else -> ProviderOperationResult.Success(makeAcknowledgement(cs2))
            }
        }

        val context = makeContext(emitter = recordingEmitter, storage = storage, transport = transport)
        val pipeline = makeOutboundPipeline()

        runSuspend { pipeline.execute(context) }

        assertEquals(2, recordingEmitter.progressUpdatedList.size, "One event per completed batch.")
        val p1 = recordingEmitter.progressUpdatedList[0]
        val p2 = recordingEmitter.progressUpdatedList[1]
        assertEquals(2L, p1.completed, "After first batch: 2 events.")
        assertEquals(5L, p2.completed, "After second batch: 5 events cumulative.")
        assertTrue(p2.completed >= p1.completed, "Progress must be non-decreasing.")
    }

    @Test
    fun `progress values are monotonic across batches`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val batches = listOf(
            makeChangeSet("cs-001", eventCount = 2),
            makeChangeSet("cs-002", eventCount = 1),
            makeChangeSet("cs-003", eventCount = 4),
        )
        var batchIndex = 0
        storage.readOutboundResult = {
            if (batchIndex < batches.size) {
                val cs = batches[batchIndex]
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(cs, hasMore = batchIndex < batches.size - 1))
            } else {
                ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }
        }
        transport.pushResult = {
            val cs = batches[batchIndex]
            batchIndex++
            ProviderOperationResult.Success(makeAcknowledgement(cs))
        }

        val context = makeContext(emitter = recordingEmitter, storage = storage, transport = transport)
        val pipeline = makeOutboundPipeline()

        runSuspend { pipeline.execute(context) }

        val progressValues = recordingEmitter.progressUpdatedList.map { it.completed }
        for (i in 1 until progressValues.size) {
            assertTrue(
                progressValues[i] >= progressValues[i - 1],
                "Progress must be non-decreasing. Got ${progressValues[i - 1]} then ${progressValues[i]}.",
            )
        }
    }

    @Test
    fun `no progress after push failure`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        storage.readOutboundResult = {
            ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
        }
        transport.pushResult = { ProviderOperationResult.Failure(FakeError()) }

        val context = makeContext(emitter = recordingEmitter, storage = storage, transport = transport)
        val pipeline = makeOutboundPipeline()

        val result = runSuspend { pipeline.execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
        assertEquals(0, recordingEmitter.progressUpdatedList.size, "No progress after push failure.")
    }

    @Test
    fun `no progress after acknowledgement-persistence failure`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        storage.readOutboundResult = {
            ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
        }
        transport.pushResult = { ProviderOperationResult.Success(makeAcknowledgement(changeSet)) }
        storage.acknowledgeResult = { ProviderOperationResult.Failure(FakeError()) }

        val context = makeContext(emitter = recordingEmitter, storage = storage, transport = transport)
        val pipeline = makeOutboundPipeline()

        val result = runSuspend { pipeline.execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
        assertEquals(0, recordingEmitter.progressUpdatedList.size, "No progress after ack persistence failure.")
    }

    @Test
    fun `ordinary observer failure does not alter outbound pipeline result`() {
        val failingObserver = FailingObserver("obs-fail")
        val dispatcher = makeDispatcher(failingObserver)
        val emitter = makeEmitter(dispatcher = dispatcher)
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        storage.readOutboundResult = {
            ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
        }
        transport.pushResult = { ProviderOperationResult.Success(makeAcknowledgement(changeSet)) }

        val context = makeContext(emitter = emitter, storage = storage, transport = transport)
        val pipeline = makeOutboundPipeline()

        val result = runSuspend { pipeline.execute(context) }

        // Observer failure is isolated; pipeline result is Succeeded
        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun `cancellation during outbound progress delivery propagates`() {
        val cancellingObserver = CancellingObserver("obs-cancel")
        val dispatcher = makeDispatcher(cancellingObserver)
        val emitter = makeEmitter(dispatcher = dispatcher)
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        storage.readOutboundResult = {
            ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
        }
        transport.pushResult = { ProviderOperationResult.Success(makeAcknowledgement(changeSet)) }

        val context = makeContext(emitter = emitter, storage = storage, transport = transport)
        val pipeline = makeOutboundPipeline()

        assertFailsWith<CancellationException> {
            runSuspend { pipeline.execute(context) }
        }
    }

    // =========================================================================
    // Inbound pipeline progress integration
    // =========================================================================

    @Test
    fun `no progress for initial NoChanges in inbound pipeline`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        storage.readCheckpointResult = { ProviderOperationResult.Success(null) }
        transport.pullResult = { ProviderOperationResult.Success(PullChangesResult.NoChanges()) }

        val context = makeContext(
            request = makeRequest(direction = SynchronizationDirection.PULL),
            emitter = recordingEmitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeInboundPipeline()

        val result = runSuspend { pipeline.execute(context) }

        assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(0, recordingEmitter.progressUpdatedList.size, "No progress for initial NoChanges.")
    }

    @Test
    fun `one progress event after one durably completed inbound batch`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001", eventCount = 4)
        val checkpoint = SynchronizationCheckpoint(
            key = CheckpointKey("cp-key-001"),
            token = CheckpointToken("cp-001"),
        )
        storage.readCheckpointResult = { ProviderOperationResult.Success(null) }
        transport.pullResult = {
            ProviderOperationResult.Success(
                PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = checkpoint),
            )
        }
        storage.applyInboundResult = { ProviderOperationResult.Success(Unit) }
        storage.writeCheckpointResult = { ProviderOperationResult.Success(Unit) }

        val context = makeContext(
            request = makeRequest(direction = SynchronizationDirection.PULL),
            emitter = recordingEmitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeInboundPipeline()

        runSuspend { pipeline.execute(context) }

        assertEquals(1, recordingEmitter.progressUpdatedList.size)
        val progress = recordingEmitter.progressUpdatedList[0]
        assertEquals(SynchronizationPhase.APPLYING_INBOUND, progress.phase)
        assertEquals(4L, progress.completed)
        assertNull(progress.total)
        assertEquals(SynchronizationProgressUnit.EVENTS, progress.unit)
    }

    @Test
    fun `multiple inbound batches emit ordered progress`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val cs1 = makeChangeSet("cs-001", eventCount = 3)
        val cs2 = makeChangeSet("cs-002", eventCount = 5)
        val cp1 = SynchronizationCheckpoint(key = CheckpointKey("cp-key-001"), token = CheckpointToken("cp-001"))
        val cp2 = SynchronizationCheckpoint(key = CheckpointKey("cp-key-002"), token = CheckpointToken("cp-002"))
        var pullCount = 0

        storage.readCheckpointResult = { ProviderOperationResult.Success(null) }
        transport.pullResult = {
            pullCount++
            when (pullCount) {
                1 -> ProviderOperationResult.Success(
                    PullChangesResult.Changes(cs1, hasMore = true, nextCheckpoint = cp1),
                )
                else -> ProviderOperationResult.Success(
                    PullChangesResult.Changes(cs2, hasMore = false, nextCheckpoint = cp2),
                )
            }
        }

        val context = makeContext(
            request = makeRequest(direction = SynchronizationDirection.PULL),
            emitter = recordingEmitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeInboundPipeline()

        runSuspend { pipeline.execute(context) }

        assertEquals(2, recordingEmitter.progressUpdatedList.size)
        val p1 = recordingEmitter.progressUpdatedList[0]
        val p2 = recordingEmitter.progressUpdatedList[1]
        assertEquals(3L, p1.completed)
        assertEquals(8L, p2.completed)
        assertTrue(p2.completed >= p1.completed)
    }

    @Test
    fun `no progress after inbound apply failure`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        val checkpoint = SynchronizationCheckpoint(key = CheckpointKey("cp-key-001"), token = CheckpointToken("cp-001"))
        storage.readCheckpointResult = { ProviderOperationResult.Success(null) }
        transport.pullResult = {
            ProviderOperationResult.Success(
                PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = checkpoint),
            )
        }
        storage.applyInboundResult = { ProviderOperationResult.Failure(FakeError()) }

        val context = makeContext(
            request = makeRequest(direction = SynchronizationDirection.PULL),
            emitter = recordingEmitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeInboundPipeline()

        val result = runSuspend { pipeline.execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
        assertEquals(0, recordingEmitter.progressUpdatedList.size, "No progress after apply failure.")
    }

    @Test
    fun `no progress after checkpoint write failure`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        val checkpoint = SynchronizationCheckpoint(key = CheckpointKey("cp-key-001"), token = CheckpointToken("cp-001"))
        storage.readCheckpointResult = { ProviderOperationResult.Success(null) }
        transport.pullResult = {
            ProviderOperationResult.Success(
                PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = checkpoint),
            )
        }
        storage.applyInboundResult = { ProviderOperationResult.Success(Unit) }
        storage.writeCheckpointResult = { ProviderOperationResult.Failure(FakeError()) }

        val context = makeContext(
            request = makeRequest(direction = SynchronizationDirection.PULL),
            emitter = recordingEmitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeInboundPipeline()

        val result = runSuspend { pipeline.execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
        assertEquals(0, recordingEmitter.progressUpdatedList.size, "No progress after checkpoint write failure.")
    }

    @Test
    fun `inbound progress emitted when no checkpoint required`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001", eventCount = 2)
        // nextCheckpoint = null means no checkpoint write required
        storage.readCheckpointResult = { ProviderOperationResult.Success(null) }
        transport.pullResult = {
            ProviderOperationResult.Success(
                PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = null),
            )
        }
        storage.applyInboundResult = { ProviderOperationResult.Success(Unit) }

        val context = makeContext(
            request = makeRequest(direction = SynchronizationDirection.PULL),
            emitter = recordingEmitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeInboundPipeline()

        runSuspend { pipeline.execute(context) }

        assertEquals(1, recordingEmitter.progressUpdatedList.size, "Progress should emit after apply when no checkpoint required.")
        assertEquals(2L, recordingEmitter.progressUpdatedList[0].completed)
    }

    @Test
    fun `ordinary inbound observer failure does not alter pipeline result`() {
        val failingObserver = FailingObserver("obs-fail")
        val dispatcher = makeDispatcher(failingObserver)
        val emitter = makeEmitter(dispatcher = dispatcher)
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        val checkpoint = SynchronizationCheckpoint(key = CheckpointKey("cp-key-001"), token = CheckpointToken("cp-001"))
        storage.readCheckpointResult = { ProviderOperationResult.Success(null) }
        transport.pullResult = {
            ProviderOperationResult.Success(
                PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = checkpoint),
            )
        }

        val context = makeContext(
            request = makeRequest(direction = SynchronizationDirection.PULL),
            emitter = emitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeInboundPipeline()

        val result = runSuspend { pipeline.execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun `cancellation during inbound progress delivery propagates`() {
        val cancellingObserver = CancellingObserver("obs-cancel")
        val dispatcher = makeDispatcher(cancellingObserver)
        val emitter = makeEmitter(dispatcher = dispatcher)
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        val changeSet = makeChangeSet("cs-001")
        val checkpoint = SynchronizationCheckpoint(key = CheckpointKey("cp-key-001"), token = CheckpointToken("cp-001"))
        storage.readCheckpointResult = { ProviderOperationResult.Success(null) }
        transport.pullResult = {
            ProviderOperationResult.Success(
                PullChangesResult.Changes(changeSet, hasMore = false, nextCheckpoint = checkpoint),
            )
        }

        val context = makeContext(
            request = makeRequest(direction = SynchronizationDirection.PULL),
            emitter = emitter,
            storage = storage,
            transport = transport,
        )
        val pipeline = makeInboundPipeline()

        assertFailsWith<CancellationException> {
            runSuspend { pipeline.execute(context) }
        }
    }

    // =========================================================================
    // RetryScheduled event integration
    // =========================================================================

    @Test
    fun `NOT_REQUIRED emits no RetryScheduled event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val orchestrator = makeRetryOrchestrator(
            policy = AlwaysRetryPolicy(),
            scheduler = SuccessSchedulerProvider(),
            emitter = recordingEmitter,
        )
        val request = makeRetryRequest(
            syncResult = SynchronizationResult.Succeeded(
                request = makeRequest(),
                completedAt = DataLoomInstant(1_000_000L),
                summary = SynchronizationSummary(),
            ),
        )

        val result = runSuspend { orchestrator.evaluateAndSchedule(request) }

        assertEquals(RetryOrchestrationStatus.NOT_REQUIRED, result.status)
        assertEquals(0, recordingEmitter.retryScheduledCalls.size, "No event for NOT_REQUIRED.")
    }

    @Test
    fun `STOPPED emits no RetryScheduled event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val orchestrator = makeRetryOrchestrator(
            policy = AlwaysStopPolicy(),
            scheduler = SuccessSchedulerProvider(),
            emitter = recordingEmitter,
        )
        val request = makeRetryRequest(
            syncResult = SynchronizationResult.Failed(
                request = makeRequest(),
                completedAt = DataLoomInstant(1_000_000L),
                summary = SynchronizationSummary(),
                error = FakeError(),
            ),
        )

        val result = runSuspend { orchestrator.evaluateAndSchedule(request) }

        assertEquals(RetryOrchestrationStatus.STOPPED, result.status)
        assertEquals(0, recordingEmitter.retryScheduledCalls.size, "No event for STOPPED.")
    }

    @Test
    fun `missing scheduler emits no RetryScheduled event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val orchestrator = makeRetryOrchestrator(
            policy = AlwaysRetryPolicy(),
            scheduler = null,
            emitter = recordingEmitter,
        )
        val request = makeRetryRequest()

        val result = runSuspend { orchestrator.evaluateAndSchedule(request) }

        assertEquals(RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED, result.status)
        assertEquals(0, recordingEmitter.retryScheduledCalls.size)
    }

    @Test
    fun `scheduler failure emits no RetryScheduled event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val orchestrator = makeRetryOrchestrator(
            policy = AlwaysRetryPolicy(),
            scheduler = FailingSchedulerProvider(),
            emitter = recordingEmitter,
        )
        val request = makeRetryRequest()

        val result = runSuspend { orchestrator.evaluateAndSchedule(request) }

        assertEquals(RetryOrchestrationStatus.SCHEDULER_FAILED, result.status)
        assertEquals(0, recordingEmitter.retryScheduledCalls.size, "No event for SCHEDULER_FAILED.")
    }

    @Test
    fun `successful schedule emits one RetryScheduled event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val orchestrator = makeRetryOrchestrator(
            policy = AlwaysRetryPolicy(delay = SchedulingDelay(2000L)),
            scheduler = SuccessSchedulerProvider(),
            emitter = recordingEmitter,
        )
        val request = makeRetryRequest()

        val result = runSuspend { orchestrator.evaluateAndSchedule(request) }

        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status)
        assertEquals(1, recordingEmitter.retryScheduledCalls.size, "Exactly one RetryScheduled event.")
    }

    @Test
    fun `RetryScheduled event emits after scheduler success`() {
        val eventOrder = mutableListOf<String>()
        val scheduler = object : SchedulerProvider {
            override val descriptor: ProviderDescriptor = ProviderDescriptor(
                id = ProviderId("scheduler-order"),
                name = ProviderName("Order Scheduler"),
                type = ProviderType.SCHEDULER,
                version = ProviderVersion("1.0.0"),
            )

            override suspend fun initialize(context: ProviderInitializationContext) = ProviderOperationResult.Success(Unit)
            override suspend fun health() = ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
            override suspend fun close() = ProviderOperationResult.Success(Unit)
            override suspend fun cancel(request: ScheduleCancellationRequest) = ProviderOperationResult.Success(Unit)

            override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> {
                eventOrder.add("scheduler.schedule()")
                return ProviderOperationResult.Success(ScheduleReceipt(id = request.id))
            }
        }

        val emitter = object : RecordingRuntimeEventEmitter() {
            override suspend fun emitRetryScheduled(
                request: SynchronizationRequest,
                attempt: RetryAttempt,
                delay: SchedulingDelay,
                error: DataLoomError,
            ): SynchronizationEventDispatchResult {
                eventOrder.add("emitRetryScheduled()")
                return super.emitRetryScheduled(request, attempt, delay, error)
            }
        }

        val orchestrator = SynchronizationRetryOrchestrator(
            retryPolicy = AlwaysRetryPolicy(),
            schedulerProvider = scheduler,
            configuration = RetrySchedulingConfiguration(
                constraints = ScheduleConstraints(),
                existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            ),
            eventEmitter = emitter,
        )

        runSuspend { orchestrator.evaluateAndSchedule(makeRetryRequest()) }

        assertEquals(listOf("scheduler.schedule()", "emitRetryScheduled()"), eventOrder)
    }

    @Test
    fun `RetryScheduled event preserves exact request`() {
        val syncRequest = makeRequest(sessionId = "session-retry-test")
        var capturedRequest: SynchronizationRequest? = null
        val emitter = object : RecordingRuntimeEventEmitter() {
            override suspend fun emitRetryScheduled(
                request: SynchronizationRequest,
                attempt: RetryAttempt,
                delay: SchedulingDelay,
                error: DataLoomError,
            ): SynchronizationEventDispatchResult {
                capturedRequest = request
                return super.emitRetryScheduled(request, attempt, delay, error)
            }
        }

        val orchestrator = makeRetryOrchestrator(emitter = emitter)
        val retryRequest = makeRetryRequest(request = syncRequest)

        runSuspend { orchestrator.evaluateAndSchedule(retryRequest) }

        assertSame(syncRequest, capturedRequest, "Exact request must be preserved.")
    }

    @Test
    fun `RetryScheduled event preserves exact RetryAttempt`() {
        val attempt = RetryAttempt(5)
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val orchestrator = makeRetryOrchestrator(emitter = recordingEmitter)
        val request = makeRetryRequest(attempt = attempt)

        runSuspend { orchestrator.evaluateAndSchedule(request) }

        val (capturedAttempt, _, _) = recordingEmitter.retryScheduledCalls.single()
        assertSame(attempt, capturedAttempt, "Exact RetryAttempt must be preserved.")
    }

    @Test
    fun `RetryScheduled event preserves selected delay`() {
        val delay = SchedulingDelay(7500L)
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val orchestrator = makeRetryOrchestrator(
            policy = AlwaysRetryPolicy(delay = delay),
            emitter = recordingEmitter,
        )

        runSuspend { orchestrator.evaluateAndSchedule(makeRetryRequest()) }

        val (_, capturedDelay, _) = recordingEmitter.retryScheduledCalls.single()
        assertEquals(delay.milliseconds, capturedDelay.milliseconds, "Selected delay must be preserved.")
    }

    @Test
    fun `observer failure does not change SCHEDULED result`() {
        val failingObserver = FailingObserver("obs-fail")
        val dispatcher = makeDispatcher(failingObserver)
        val emitter = makeEmitter(dispatcher = dispatcher)
        val orchestrator = makeRetryOrchestrator(emitter = emitter)

        val result = runSuspend { orchestrator.evaluateAndSchedule(makeRetryRequest()) }

        assertEquals(RetryOrchestrationStatus.SCHEDULED, result.status, "Observer failure must not change SCHEDULED result.")
    }

    @Test
    fun `scheduler is called at most once`() {
        val scheduler = SuccessSchedulerProvider()
        val orchestrator = makeRetryOrchestrator(scheduler = scheduler)

        runSuspend { orchestrator.evaluateAndSchedule(makeRetryRequest()) }

        assertEquals(1, scheduler.callCount, "Scheduler must be called at most once.")
    }

    @Test
    fun `cancellation during RetryScheduled delivery propagates`() {
        val cancellingObserver = CancellingObserver("obs-cancel")
        val dispatcher = makeDispatcher(cancellingObserver)
        val emitter = makeEmitter(dispatcher = dispatcher)
        val orchestrator = makeRetryOrchestrator(emitter = emitter)

        assertFailsWith<CancellationException> {
            runSuspend { orchestrator.evaluateAndSchedule(makeRetryRequest()) }
        }
    }

    // =========================================================================
    // ConflictDetected event integration
    // =========================================================================

    @Test
    fun `missing detector emits no ConflictDetected event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val orchestrator = makeConflictOrchestrator(emitter = recordingEmitter)

        val result = runSuspend { orchestrator.detectAndResolve(makeConflictRequest("missing")) }

        assertIs<ConflictOrchestrationResult.DetectorNotFound>(result)
        assertEquals(0, recordingEmitter.conflictDetectedList.size)
    }

    @Test
    fun `no conflict result emits no ConflictDetected event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.NoConflict)
        val orchestrator = makeConflictOrchestrator(detectors = listOf(detector), emitter = recordingEmitter)

        val result = runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1")) }

        assertIs<ConflictOrchestrationResult.NoConflict>(result)
        assertEquals(0, recordingEmitter.conflictDetectedList.size)
    }

    @Test
    fun `detected conflict emits one ConflictDetected event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val orchestrator = makeConflictOrchestrator(detectors = listOf(detector), emitter = recordingEmitter)

        runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1")) }

        assertEquals(1, recordingEmitter.conflictDetectedList.size)
    }

    @Test
    fun `exact conflict reaches the ConflictDetected event`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val orchestrator = makeConflictOrchestrator(detectors = listOf(detector), emitter = recordingEmitter)

        runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1")) }

        assertSame(sampleConflict, recordingEmitter.conflictDetectedList[0], "Exact conflict must be preserved.")
    }

    @Test
    fun `conflict ID is preserved in ConflictDetected`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val orchestrator = makeConflictOrchestrator(detectors = listOf(detector), emitter = recordingEmitter)

        runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1")) }

        assertEquals(sampleConflict.id, recordingEmitter.conflictDetectedList[0].id)
    }

    @Test
    fun `event emits before resolver invocation`() {
        val eventOrder = mutableListOf<String>()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val resolver = object : FakeResolver(ConflictResolverId("r1")) {
            override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
                eventOrder.add("resolver.resolve()")
                return super.resolve(request)
            }
        }

        val emitter = object : RecordingRuntimeEventEmitter() {
            override suspend fun emitConflictDetected(
                request: SynchronizationRequest,
                conflict: SynchronizationConflict,
            ): SynchronizationEventDispatchResult {
                eventOrder.add("emitConflictDetected()")
                return super.emitConflictDetected(request, conflict)
            }
        }

        val orchestrator = makeConflictOrchestrator(
            detectors = listOf(detector),
            resolvers = listOf(resolver),
            emitter = emitter,
        )

        runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1", "r1")) }

        assertEquals(listOf("emitConflictDetected()", "resolver.resolve()"), eventOrder)
    }

    @Test
    fun `resolver-not-configured still emits ConflictDetected`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val orchestrator = makeConflictOrchestrator(detectors = listOf(detector), emitter = recordingEmitter)

        val result = runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1", resolverId = null)) }

        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result)
        assertEquals(1, recordingEmitter.conflictDetectedList.size, "ConflictDetected must emit even when resolver is not configured.")
    }

    @Test
    fun `resolver-not-found still emits ConflictDetected`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val orchestrator = makeConflictOrchestrator(detectors = listOf(detector), emitter = recordingEmitter)

        val result = runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1", "missing-resolver")) }

        assertIs<ConflictOrchestrationResult.ResolverNotFound>(result)
        assertEquals(1, recordingEmitter.conflictDetectedList.size, "ConflictDetected must emit even when resolver is not found.")
    }

    @Test
    fun `ordinary observer failure does not stop conflict resolution`() {
        val failingObserver = FailingObserver("obs-fail")
        val dispatcher = makeDispatcher(failingObserver)
        val emitter = makeEmitter(dispatcher = dispatcher)
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val resolver = FakeResolver(ConflictResolverId("r1"))
        val orchestrator = makeConflictOrchestrator(
            detectors = listOf(detector),
            resolvers = listOf(resolver),
            emitter = emitter,
        )

        val result = runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1", "r1")) }

        assertIs<ConflictOrchestrationResult.Resolved>(result)
        assertEquals(1, resolver.invokeCount, "Resolver must still be invoked despite observer failure.")
    }

    @Test
    fun `cancellation during ConflictDetected delivery prevents resolver execution`() {
        val cancellingObserver = CancellingObserver("obs-cancel")
        val dispatcher = makeDispatcher(cancellingObserver)
        val emitter = makeEmitter(dispatcher = dispatcher)
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val resolver = FakeResolver(ConflictResolverId("r1"))
        val orchestrator = makeConflictOrchestrator(
            detectors = listOf(detector),
            resolvers = listOf(resolver),
            emitter = emitter,
        )

        assertFailsWith<CancellationException> {
            runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1", "r1")) }
        }

        assertEquals(0, resolver.invokeCount, "Resolver must not be invoked after cancellation during event delivery.")
    }

    @Test
    fun `detector and resolver execute at most once per call`() {
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val resolver = FakeResolver(ConflictResolverId("r1"))
        val orchestrator = makeConflictOrchestrator(
            detectors = listOf(detector),
            resolvers = listOf(resolver),
            emitter = recordingEmitter,
        )

        runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1", "r1")) }

        assertEquals(1, detector.invokeCount, "Detector must execute at most once.")
        assertEquals(1, resolver.invokeCount, "Resolver must execute at most once.")
    }

    // =========================================================================
    // Event ordering
    // =========================================================================

    @Test
    fun `event IDs follow emission order for lifecycle and operational events`() {
        val idGen = SequenceIdGenerator()
        val observer = RecordingObserver("obs-001")
        val dispatcher = makeDispatcher(observer)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(dispatcher, FixedClock(), idGen)

        val context = makeContext()
        val request = makeRequest()
        val progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 1L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )
        val result = SynchronizationResult.Succeeded(
            request = context.request,
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
        )

        runSuspend {
            emitter.emitStarted(context)              // ID event-1
            emitter.emitProgressUpdated(request, progress) // ID event-2
            emitter.emitCompleted(context, result)     // ID event-3
        }

        val eventIds = observer.events.map { it.id.value }
        assertEquals(listOf("event-1", "event-2", "event-3"), eventIds)
    }

    // =========================================================================
    // Security: no payload or sensitive data exposure
    // =========================================================================

    @Test
    fun `ConflictDetected event does not copy payload bytes`() {
        // Verify the same conflict reference is used, not a copy
        val recordingEmitter = RecordingRuntimeEventEmitter()
        val detector = FakeDetector(ConflictDetectorId("d1"), ConflictDetectionResult.ConflictDetected(sampleConflict))
        val orchestrator = makeConflictOrchestrator(detectors = listOf(detector), emitter = recordingEmitter)

        runSuspend { orchestrator.detectAndResolve(makeConflictRequest("d1")) }

        // The exact same conflict object reference is used (no copy)
        assertSame(sampleConflict, recordingEmitter.conflictDetectedList[0])
    }

    @Test
    fun `progress diagnostics do not expose payloads`() {
        // Progress contains only structural fields; no payload
        val progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 10L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )
        // toString must not include payload content
        val str = progress.toString()
        assertTrue(!str.contains("bytes"), "Progress toString must not expose payload bytes.")
    }
}
