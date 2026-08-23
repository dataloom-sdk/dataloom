package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
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
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomQueueLifecycleOperationalEventOutboxSpec]/
 * `queueLifecycleOperationalEventOutboxConfiguration` is also a real, reachable
 * caller of [io.dataloom.api.operational.DurableOperationalEventOutbox] for a
 * real durable queue-entry transition produced by a real [DataLoomBuilder]-built
 * [io.dataloom.api.facade.DataLoom] instance's *circuit-aware* queue worker --
 * mirroring exactly
 * [io.dataloom.runtime.facade.DataLoomBuilderQueueLifecycleOperationalEventOutboxTest]'s
 * non-circuit-aware coverage, and that a durable-recording failure never
 * changes the real [CircuitBreakerQueueWorkerRunResult] the caller receives.
 */
class DataLoomBuilderCircuitQueueLifecycleOperationalEventOutboxTest {

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message",
        override val cause: Throwable? = null,
    ) : DataLoomError

    @Test
    fun successfulQueueEntry_appendsRealEntry_whenBothCircuitQueueWorkerAndOutboxAreConfigured() = runTest {
        val queue = RecordingQueueProvider()
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-circuit-queue-lifecycle-events")
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline(SynchronizationDirection.PUSH))
            .circuitQueueWorkerConfiguration(circuitQueueWorkerSpec(queue.descriptor.id))
            .queueLifecycleOperationalEventOutboxConfiguration(
                DataLoomQueueLifecycleOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = dataLoom.circuitQueueWorker!!.run(workerRunRequest())

        assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(result)
        assertEquals(1, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun nothingIsBridged_whenOutboxIsConfiguredButCircuitQueueWorkerIsNot() = runTest {
        val queue = RecordingQueueProvider()
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-circuit-no-worker-events")
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline(SynchronizationDirection.PUSH))
            // Note: circuitQueueWorkerConfiguration is never called, so no
            // queue-entry transition is ever persisted to bridge.
            .queueLifecycleOperationalEventOutboxConfiguration(
                DataLoomQueueLifecycleOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()

        assertEquals(null, dataLoom.circuitQueueWorker)
        assertEquals(0, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun nothingIsBridged_whenCircuitQueueWorkerIsConfiguredButOutboxIsNot() = runTest {
        val queue = RecordingQueueProvider()
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-circuit-no-outbox-events")
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline(SynchronizationDirection.PUSH))
            .circuitQueueWorkerConfiguration(circuitQueueWorkerSpec(queue.descriptor.id))
            // Note: queueLifecycleOperationalEventOutboxConfiguration is never called.
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = dataLoom.circuitQueueWorker!!.run(workerRunRequest())

        assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(result)
        assertEquals(0, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun appendFailureDoesNotChangeTheRealCircuitQueueProcessingResult() = runTest {
        val queue = RecordingQueueProvider()
        val scope = OperationalEventOutboxScope("integration-circuit-failing-events")
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline(SynchronizationDirection.PUSH))
            .circuitQueueWorkerConfiguration(circuitQueueWorkerSpec(queue.descriptor.id))
            .queueLifecycleOperationalEventOutboxConfiguration(
                DataLoomQueueLifecycleOperationalEventOutboxSpec(
                    store = AlwaysFailingOperationalEventOutboxStore(),
                    scope = scope,
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = dataLoom.circuitQueueWorker!!.run(workerRunRequest())

        // The real queue-processing result is unchanged despite the outbox
        // append failure, and the real provider transition (a fully separate
        // concern from the outbox bridge) still succeeded.
        val completed = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(result)
        val processed = assertIs<CircuitBreakerQueueProcessingResult.Processed>(completed.processingResult)
        assertEquals(1, processed.summary.completed)
        assertEquals(1, queue.completionRequests.size)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun builder(queue: RecordingQueueProvider): DataLoomBuilder = DataLoomBuilder()
        .runtimeDependencies(runtimeDependencies())
        .providers(RecordingStorageProvider(), RecordingTransportProvider(), queue)
        .defaultProviderBindings(
            SynchronizationProviderBindings(
                storageProviderId = ProviderId("circuit-queue-lifecycle-storage"),
                transportProviderId = ProviderId("circuit-queue-lifecycle-transport"),
                queueProviderId = queue.descriptor.id,
            ),
        )

    private fun circuitQueueWorkerSpec(queueProviderId: ProviderId): DataLoomCircuitQueueWorkerSpec =
        DataLoomCircuitQueueWorkerSpec(
            workerSpec = queueWorkerSpec(),
            circuitBreakerConfiguration = CircuitBreakerConfiguration(
                failureThreshold = 1,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(10_000L),
            ),
            circuitBreakerStateStore = InMemoryCircuitBreakerStateStore(),
            recoveryScope = CircuitBreakerScope.providerOperation(
                queueProviderId,
                QueueCircuitOperation.RECOVER_EXPIRED_LEASES.retryOperation,
            ),
            processingScopes = QueueProcessingCircuitScopes(
                acquisition = CircuitBreakerScope.providerOperation(
                    queueProviderId,
                    QueueCircuitOperation.ACQUIRE.retryOperation,
                ),
                completion = CircuitBreakerScope.providerOperation(
                    queueProviderId,
                    QueueCircuitOperation.COMPLETE.retryOperation,
                ),
                reschedule = CircuitBreakerScope.providerOperation(
                    queueProviderId,
                    QueueCircuitOperation.RESCHEDULE.retryOperation,
                ),
                deferral = CircuitBreakerScope.providerOperation(
                    queueProviderId,
                    QueueCircuitOperation.DEFER.retryOperation,
                ),
                failure = CircuitBreakerScope.providerOperation(
                    queueProviderId,
                    QueueCircuitOperation.FAIL.retryOperation,
                ),
                cancellation = CircuitBreakerScope.providerOperation(
                    queueProviderId,
                    QueueCircuitOperation.CANCEL.retryOperation,
                ),
            ),
        )

    private fun queueWorkerSpec(): DataLoomQueueWorkerSpec = DataLoomQueueWorkerSpec(
        workResolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = entry.synchronizationRequest,
                    bindings = SynchronizationProviderBindings(
                        storageProviderId = ProviderId("circuit-queue-lifecycle-storage"),
                        transportProviderId = ProviderId("circuit-queue-lifecycle-transport"),
                    ),
                ),
            )
        },
        retryPolicy = object : RetryPolicy {
            override val id = RetryPolicyId("circuit-queue-lifecycle-no-retry")
            override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                RetryDecision.Stop(RetryStopReason.NON_RECOVERABLE)
        },
        retryOperation = RetryOperation("circuit-queue-lifecycle.test.operation"),
        configuration = QueueWorkerConfiguration(
            scheduleId = ScheduleId("circuit-queue-lifecycle-worker"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay.ZERO,
            recoverExpiredLeasesBeforeProcessing = false,
        ),
    )

    private fun workerRunRequest(): QueueWorkerRunRequest = QueueWorkerRunRequest(
        processingRequest = QueueProcessingRequest(
            acquireRequest = QueueAcquireRequest(
                consumerId = QueueConsumerId("circuit-queue-lifecycle-consumer"),
                leaseId = QueueLeaseId("circuit-queue-lifecycle-lease"),
                acquiredAt = DataLoomInstant(1_000_000L),
                leaseExpiresAt = DataLoomInstant(2_000_000L),
                maxEntries = 5,
            ),
        ),
        recoveryRequest = null,
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("circuit-queue-lifecycle-event") },
            queueEntryIds = generator { QueueEntryId("circuit-queue-lifecycle-generated-entry") },
            queueLeaseIds = generator { QueueLeaseId("circuit-queue-lifecycle-generated-lease") },
            conflictIds = generator { ConflictId("circuit-queue-lifecycle-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    /** Always succeeds -- deterministically drives the queue entry to [io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Completed]. */
    private class SucceedingPipeline(override val direction: SynchronizationDirection) : SynchronizationPipeline {
        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult =
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = DataLoomInstant(1_500_000L),
                summary = SynchronizationSummary(),
            )
    }

    /** Returns exactly one real acquired entry on the first [acquire] call, then [QueueAcquireResult.NoEntries]. */
    private class RecordingQueueProvider : QueueProvider {
        val completionRequests = mutableListOf<QueueCompletionRequest>()
        private var acquireCallCount = 0

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("circuit-queue-lifecycle-queue"),
            name = ProviderName("Circuit Queue Lifecycle Test Queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
            acquireCallCount++
            if (acquireCallCount > 1) {
                return ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
            }
            val entryRequest = SynchronizationRequest(
                workflowId = WorkflowId("circuit-queue-lifecycle-workflow"),
                sessionId = SynchronizationSessionId("circuit-queue-lifecycle-session"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("circuit-queue-lifecycle-execution"),
                    correlationId = CorrelationId("circuit-queue-lifecycle-correlation"),
                ),
            )
            val lease = QueueLease(
                id = request.leaseId,
                consumerId = request.consumerId,
                acquiredAt = request.acquiredAt,
                expiresAt = request.leaseExpiresAt,
            )
            val entry = QueueEntry(
                id = QueueEntryId("circuit-queue-lifecycle-real-entry"),
                synchronizationRequest = entryRequest,
                state = QueueEntryState.LEASED,
                enqueuedAt = request.acquiredAt,
                availableAt = request.acquiredAt,
                lease = lease,
            )
            return ProviderOperationResult.Success(QueueAcquireResult.Entries(lease = lease, entries = listOf(entry)))
        }

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> {
            completionRequests.add(request)
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun defer(request: QueueDeferralRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recoveredEntries = 0))
    }

    /** Never actually invoked -- [SucceedingPipeline] replaces default pipeline execution. */
    private class RecordingStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("circuit-queue-lifecycle-storage"),
            name = ProviderName("Circuit Queue Lifecycle Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /** Never actually invoked -- [SucceedingPipeline] replaces default pipeline execution. */
    private class RecordingTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("circuit-queue-lifecycle-transport"),
            name = ProviderName("Circuit Queue Lifecycle Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }

    /** Always empty -- every circuit scope is treated as closed with no recorded failures. */
    private class InMemoryCircuitBreakerStateStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            val current = records[scope]
            return ProviderOperationResult.Success(
                if (current == null) CircuitBreakerLoadResult.Missing else CircuitBreakerLoadResult.Found(current),
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(CircuitBreakerCompareAndSetResult.Conflict(current))
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(CircuitBreakerCompareAndSetResult.Updated(updated))
        }
    }

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        suspend fun recordedEntryCount(scope: OperationalEventOutboxScope): Int = records[scope]?.state?.entries?.size ?: 0

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    /** Always fails the underlying durable store -- proves append failures never propagate. */
    private class AlwaysFailingOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(FakeError())
    }
}
