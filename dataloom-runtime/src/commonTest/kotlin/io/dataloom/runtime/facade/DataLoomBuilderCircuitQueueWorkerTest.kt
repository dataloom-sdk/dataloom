package io.dataloom.runtime.facade

import io.dataloom.api.change.ChangeSet
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
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
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
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
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
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueCircuitProviderFailureDisposition
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.CircuitBreakerFailureDisposition
import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.SchedulerCircuitOperation
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataLoomBuilderCircuitQueueWorkerTest {

    @Test
    fun `no worker configuration exposes neither worker capability`() {
        val queue = RecordingQueueProvider()
        val dataLoom = builder(queue).build()

        assertNull(dataLoom.queueWorker)
        assertNull(dataLoom.circuitQueueWorker)
    }

    @Test
    fun `direct worker configuration exposes only direct capability`() {
        val queue = RecordingQueueProvider()
        val dataLoom = builder(queue)
            .queueWorkerConfiguration(workerSpec())
            .build()

        assertNotNull(dataLoom.queueWorker)
        assertNull(dataLoom.circuitQueueWorker)
    }

    @Test
    fun `circuit worker configuration exposes only circuit capability`() {
        val queue = RecordingQueueProvider()
        val store = RecordingCircuitStore()
        val dataLoom = builder(queue)
            .circuitQueueWorkerConfiguration(circuitSpec(queue.descriptor.id, store))
            .build()

        assertNull(dataLoom.queueWorker)
        assertNotNull(dataLoom.circuitQueueWorker)
    }

    @Test
    fun `last worker configuration wins deterministically`() {
        val queue = RecordingQueueProvider()
        val store = RecordingCircuitStore()

        val circuitLast = builder(queue)
            .queueWorkerConfiguration(workerSpec())
            .circuitQueueWorkerConfiguration(circuitSpec(queue.descriptor.id, store))
            .build()
        assertNull(circuitLast.queueWorker)
        assertNotNull(circuitLast.circuitQueueWorker)

        val directLast = builder(queue)
            .circuitQueueWorkerConfiguration(circuitSpec(queue.descriptor.id, store))
            .queueWorkerConfiguration(workerSpec())
            .build()
        assertNotNull(directLast.queueWorker)
        assertNull(directLast.circuitQueueWorker)
    }

    @Test
    fun `valid build performs no state queue or clock operation`() {
        val queue = RecordingQueueProvider()
        val store = RecordingCircuitStore()
        val clock = RecordingClock()

        val dataLoom = builder(queue, clock)
            .circuitQueueWorkerConfiguration(circuitSpec(queue.descriptor.id, store))
            .build()

        assertNotNull(dataLoom.circuitQueueWorker)
        assertEquals(0, store.loadCalls)
        assertEquals(0, store.compareAndSetCalls)
        assertEquals(0, queue.totalQueueOperationCalls)
        assertEquals(0, clock.nowCalls)
    }

    @Test
    fun `built circuit worker uses supplied store and bound queue provider`() {
        val queue = RecordingQueueProvider()
        val store = RecordingCircuitStore()
        val dataLoom = builder(queue)
            .circuitQueueWorkerConfiguration(circuitSpec(queue.descriptor.id, store))
            .build()

        val result = runSuspend {
            requireNotNull(dataLoom.circuitQueueWorker).run(workerRunRequest())
        }

        val completed = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(result)
        assertIs<CircuitBreakerQueueProcessingResult.NoWork>(completed.processingResult)
        assertEquals(1, queue.acquireCalls)
        assertTrue(store.loadCalls >= 1)
    }

    @Test
    fun `recovery provider scope mismatch fails before state or provider access`() {
        val queue = RecordingQueueProvider()
        val store = RecordingCircuitStore()
        val invalid = circuitSpec(
            queueProviderId = queue.descriptor.id,
            store = store,
            recoveryScope = CircuitBreakerScope.providerOperation(
                providerId = ProviderId("wrong-queue"),
                operation = QueueCircuitOperation.RECOVER_EXPIRED_LEASES.retryOperation,
            ),
        )

        assertFailsWith<DataLoomBuildException> {
            builder(queue).circuitQueueWorkerConfiguration(invalid).build()
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, queue.totalQueueOperationCalls)
    }

    @Test
    fun `processing operation scope mismatch fails during build`() {
        val queue = RecordingQueueProvider()
        val store = RecordingCircuitStore()
        val validScopes = processingScopes(queue.descriptor.id)
        val invalid = circuitSpec(
            queueProviderId = queue.descriptor.id,
            store = store,
            processingScopes = validScopes.copy(
                acquisition = CircuitBreakerScope.providerOperation(
                    providerId = queue.descriptor.id,
                    operation = QueueCircuitOperation.COMPLETE.retryOperation,
                ),
            ),
        )

        assertFailsWith<DataLoomBuildException> {
            builder(queue).circuitQueueWorkerConfiguration(invalid).build()
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, queue.totalQueueOperationCalls)
    }

    @Test
    fun `zero queue timeout prevents provider acquisition and records circuit failure`() {
        val queue = RecordingQueueProvider()
        val store = RecordingCircuitStore()
        val dataLoom = builder(queue)
            .circuitQueueWorkerConfiguration(
                circuitSpec(
                    queueProviderId = queue.descriptor.id,
                    store = store,
                    queueProviderTimeout = SchedulingDelay.ZERO,
                ),
            )
            .build()

        val result = runSuspend {
            requireNotNull(dataLoom.circuitQueueWorker).run(workerRunRequest())
        }

        val stopped = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingStopped>(result)
        val failure = assertIs<CircuitBreakerQueueProcessingResult.ProviderFailure>(
            stopped.processingResult,
        )
        assertEquals(QueueCircuitProviderFailureDisposition.CIRCUIT_FAILURE, failure.disposition)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(0, queue.acquireCalls)
        assertTrue(store.compareAndSetCalls >= 1)
    }

    @Test
    fun `custom classifier remains effective through builder assembly`() {
        val providerError = TestError(
            code = ErrorCode("QUEUE_RESPONDED_WITH_SEMANTIC_FAILURE"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val queue = RecordingQueueProvider(
            acquireResult = ProviderOperationResult.Failure(providerError),
        )
        val store = RecordingCircuitStore()
        val classifier = CircuitBreakerFailureClassifier {
            CircuitBreakerFailureDisposition.RECORD_SUCCESS
        }
        val dataLoom = builder(queue)
            .circuitQueueWorkerConfiguration(
                circuitSpec(
                    queueProviderId = queue.descriptor.id,
                    store = store,
                    failureClassifier = classifier,
                ),
            )
            .build()

        val result = runSuspend {
            requireNotNull(dataLoom.circuitQueueWorker).run(workerRunRequest())
        }

        val stopped = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingStopped>(result)
        val failure = assertIs<CircuitBreakerQueueProcessingResult.ProviderFailure>(
            stopped.processingResult,
        )
        assertEquals(QueueCircuitProviderFailureDisposition.NON_CIRCUIT_FAILURE, failure.disposition)
        assertEquals(1, queue.acquireCalls)
        assertEquals(0, store.compareAndSetCalls)
    }

    private fun builder(
        queue: RecordingQueueProvider,
        clock: RecordingClock = RecordingClock(),
        scheduler: RecordingSchedulerProvider? = null,
    ): DataLoomBuilder {
        val storage = TestStorageProvider()
        val transport = TestTransportProvider()
        val builder = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies(clock))
            .providers(storage, transport, queue)
        if (scheduler != null) {
            builder.provider(scheduler)
        }
        return builder.defaultProviderBindings(
            SynchronizationProviderBindings(
                storageProviderId = storage.descriptor.id,
                transportProviderId = transport.descriptor.id,
                queueProviderId = queue.descriptor.id,
                schedulerProviderId = scheduler?.descriptor?.id,
            ),
        )
    }

    @Test
    fun `scheduler circuit configuration requires circuit-aware worker`() {
        val queue = RecordingQueueProvider()
        val scheduler = RecordingSchedulerProvider()
        val store = RecordingCircuitStore()

        assertFailsWith<DataLoomBuildException> {
            builder(queue, scheduler = scheduler)
                .circuitQueueWorkerSchedulerConfiguration(
                    schedulerCircuitSpec(scheduler.descriptor.id, store),
                )
                .build()
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, scheduler.totalOperationCalls)
    }

    @Test
    fun `scheduler circuit configuration requires scheduler binding`() {
        val queue = RecordingQueueProvider()
        val queueStore = RecordingCircuitStore()
        val schedulerStore = RecordingCircuitStore()
        val unboundSchedulerId = ProviderId("unbound-scheduler")

        assertFailsWith<DataLoomBuildException> {
            builder(queue)
                .circuitQueueWorkerConfiguration(
                    circuitSpec(queue.descriptor.id, queueStore),
                )
                .circuitQueueWorkerSchedulerConfiguration(
                    schedulerCircuitSpec(unboundSchedulerId, schedulerStore),
                )
                .build()
        }

        assertEquals(0, queueStore.loadCalls)
        assertEquals(0, schedulerStore.loadCalls)
        assertEquals(0, queue.totalQueueOperationCalls)
    }

    @Test
    fun `scheduler provider scope mismatch fails before state or provider access`() {
        val queue = RecordingQueueProvider()
        val scheduler = RecordingSchedulerProvider()
        val queueStore = RecordingCircuitStore()
        val schedulerStore = RecordingCircuitStore()

        assertFailsWith<DataLoomBuildException> {
            builder(queue, scheduler = scheduler)
                .circuitQueueWorkerConfiguration(
                    circuitSpec(queue.descriptor.id, queueStore),
                )
                .circuitQueueWorkerSchedulerConfiguration(
                    schedulerCircuitSpec(
                        schedulerProviderId = scheduler.descriptor.id,
                        store = schedulerStore,
                        scope = CircuitBreakerScope.providerOperation(
                            providerId = ProviderId("wrong-scheduler"),
                            operation = SchedulerCircuitOperation.SCHEDULE.retryOperation,
                        ),
                    ),
                )
                .build()
        }

        assertEquals(0, schedulerStore.loadCalls)
        assertEquals(0, scheduler.totalOperationCalls)
    }

    @Test
    fun `scheduler operation scope mismatch fails before state or provider access`() {
        val queue = RecordingQueueProvider()
        val scheduler = RecordingSchedulerProvider()
        val queueStore = RecordingCircuitStore()
        val schedulerStore = RecordingCircuitStore()

        assertFailsWith<DataLoomBuildException> {
            builder(queue, scheduler = scheduler)
                .circuitQueueWorkerConfiguration(
                    circuitSpec(queue.descriptor.id, queueStore),
                )
                .circuitQueueWorkerSchedulerConfiguration(
                    schedulerCircuitSpec(
                        schedulerProviderId = scheduler.descriptor.id,
                        store = schedulerStore,
                        scope = CircuitBreakerScope.providerOperation(
                            providerId = scheduler.descriptor.id,
                            operation = RetryOperation("scheduler.cancel"),
                        ),
                    ),
                )
                .build()
        }

        assertEquals(0, schedulerStore.loadCalls)
        assertEquals(0, scheduler.totalOperationCalls)
    }

    @Test
    fun `valid scheduler circuit build is side effect free`() {
        val queue = RecordingQueueProvider()
        val scheduler = RecordingSchedulerProvider()
        val queueStore = RecordingCircuitStore()
        val schedulerStore = RecordingCircuitStore()
        val clock = RecordingClock()

        val dataLoom = builder(queue, clock, scheduler)
            .circuitQueueWorkerConfiguration(
                circuitSpec(queue.descriptor.id, queueStore),
            )
            .circuitQueueWorkerSchedulerConfiguration(
                schedulerCircuitSpec(scheduler.descriptor.id, schedulerStore),
            )
            .build()

        assertNotNull(dataLoom.circuitQueueWorker)
        assertEquals(0, queueStore.loadCalls)
        assertEquals(0, schedulerStore.loadCalls)
        assertEquals(0, queue.totalQueueOperationCalls)
        assertEquals(0, scheduler.totalOperationCalls)
        assertEquals(0, clock.nowCalls)
    }

    private fun schedulerCircuitSpec(
        schedulerProviderId: ProviderId,
        store: CircuitBreakerStateStore,
        scope: CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = schedulerProviderId,
            operation = SchedulerCircuitOperation.SCHEDULE.retryOperation,
        ),
    ): DataLoomCircuitQueueWorkerSchedulerSpec =
        DataLoomCircuitQueueWorkerSchedulerSpec(
            circuitBreakerConfiguration = CircuitBreakerConfiguration(
                failureThreshold = 1,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(1_000L),
            ),
            circuitBreakerStateStore = store,
            scope = scope,
        )

    private fun circuitSpec(
        queueProviderId: ProviderId,
        store: CircuitBreakerStateStore,
        recoveryScope: CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = queueProviderId,
            operation = QueueCircuitOperation.RECOVER_EXPIRED_LEASES.retryOperation,
        ),
        processingScopes: QueueProcessingCircuitScopes = processingScopes(queueProviderId),
        queueProviderTimeout: SchedulingDelay? = null,
        failureClassifier: CircuitBreakerFailureClassifier =
            io.dataloom.runtime.retry.QueueCircuitBreakerFailureClassifier,
    ): DataLoomCircuitQueueWorkerSpec = DataLoomCircuitQueueWorkerSpec(
        workerSpec = workerSpec(queueProviderTimeout),
        circuitBreakerConfiguration = CircuitBreakerConfiguration(
            failureThreshold = 1,
            failureWindow = SchedulingDelay(1_000L),
            openDuration = SchedulingDelay(1_000L),
        ),
        circuitBreakerStateStore = store,
        recoveryScope = recoveryScope,
        processingScopes = processingScopes,
        failureClassifier = failureClassifier,
    )

    private fun processingScopes(queueProviderId: ProviderId): QueueProcessingCircuitScopes =
        QueueProcessingCircuitScopes(
            acquisition = scope(queueProviderId, QueueCircuitOperation.ACQUIRE),
            completion = scope(queueProviderId, QueueCircuitOperation.COMPLETE),
            reschedule = scope(queueProviderId, QueueCircuitOperation.RESCHEDULE),
            deferral = scope(queueProviderId, QueueCircuitOperation.DEFER),
            failure = scope(queueProviderId, QueueCircuitOperation.FAIL),
            cancellation = scope(queueProviderId, QueueCircuitOperation.CANCEL),
        )

    private fun scope(
        providerId: ProviderId,
        operation: QueueCircuitOperation,
    ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
        providerId = providerId,
        operation = operation.retryOperation,
    )

    private fun workerSpec(
        queueProviderTimeout: SchedulingDelay? = null,
    ): DataLoomQueueWorkerSpec {
        val resolver = QueuedSynchronizationWorkResolver {
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = synchronizationRequest(),
                    bindings = SynchronizationProviderBindings(
                        storageProviderId = ProviderId("storage-builder-circuit"),
                        transportProviderId = ProviderId("transport-builder-circuit"),
                        queueProviderId = ProviderId("queue-builder-circuit"),
                    ),
                ),
            )
        }
        val policy = object : RetryPolicy {
            override val id: RetryPolicyId = RetryPolicyId("builder-circuit-no-retry")
            override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                RetryDecision.Stop(io.dataloom.api.retry.RetryStopReason.NON_RECOVERABLE)
        }
        val configuration = QueueWorkerConfiguration(
            scheduleId = ScheduleId("builder-circuit-worker"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay.ZERO,
            recoverExpiredLeasesBeforeProcessing = false,
        )
        return if (queueProviderTimeout == null) {
            DataLoomQueueWorkerSpec(
                workResolver = resolver,
                retryPolicy = policy,
                retryOperation = RetryOperation("builder.circuit.queue"),
                configuration = configuration,
            )
        } else {
            DataLoomQueueWorkerSpec(
                workResolver = resolver,
                retryPolicy = policy,
                retryOperation = RetryOperation("builder.circuit.queue"),
                configuration = configuration,
                queueProviderTimeout = queueProviderTimeout,
            )
        }
    }

    private fun workerRunRequest(): QueueWorkerRunRequest = QueueWorkerRunRequest(
        processingRequest = QueueProcessingRequest(
            QueueAcquireRequest(
                consumerId = QueueConsumerId("builder-circuit-consumer"),
                leaseId = QueueLeaseId("builder-circuit-lease"),
                acquiredAt = DataLoomInstant(1_000L),
                leaseExpiresAt = DataLoomInstant(2_000L),
                maxEntries = 1,
            ),
        ),
        recoveryRequest = null,
    )

    private fun synchronizationRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("builder-circuit-workflow"),
        sessionId = SynchronizationSessionId("builder-circuit-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("builder-circuit-execution"),
            correlationId = CorrelationId("builder-circuit-correlation"),
        ),
    )

    private fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies =
        RuntimeDependencies(
            clock = clock,
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds = generator {
                    SynchronizationEventId("builder-circuit-event")
                },
                queueEntryIds = generator { QueueEntryId("builder-circuit-entry") },
                queueLeaseIds = generator { QueueLeaseId("builder-circuit-generated-lease") },
                conflictIds = generator { ConflictId("builder-circuit-conflict") },
            ),
        )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class RecordingClock : DataLoomClock {
        var nowCalls: Int = 0
            private set

        override fun now(): DataLoomInstant {
            nowCalls++
            return DataLoomInstant(1_000L)
        }
    }

    private class RecordingCircuitStore : CircuitBreakerStateStore {
        var loadCalls: Int = 0
            private set
        var compareAndSetCalls: Int = 0
            private set
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls++
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) {
                    CircuitBreakerLoadResult.Missing
                } else {
                    CircuitBreakerLoadResult.Found(record)
                },
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            compareAndSetCalls++
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val next = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = next
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(next),
            )
        }
    }

    private class RecordingQueueProvider(
        var acquireResult: ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
    ) : QueueProvider {
        var acquireCalls: Int = 0
            private set
        var recoverCalls: Int = 0
            private set
        var transitionCalls: Int = 0
            private set
        var enqueueCalls: Int = 0
            private set

        val totalQueueOperationCalls: Int
            get() = acquireCalls + recoverCalls + transitionCalls + enqueueCalls

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("queue-builder-circuit"),
            name = ProviderName("Builder Circuit Queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(
            request: QueueEnqueueRequest,
        ): ProviderOperationResult<Unit> {
            enqueueCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> {
            acquireCalls++
            return acquireResult
        }

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> = transitionSuccess()

        override suspend fun reschedule(
            request: QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> = transitionSuccess()

        override suspend fun defer(
            request: QueueDeferralRequest,
        ): ProviderOperationResult<Unit> = transitionSuccess()

        override suspend fun fail(
            request: QueueFailureRequest,
        ): ProviderOperationResult<Unit> = transitionSuccess()

        override suspend fun cancel(
            request: QueueCancellationRequest,
        ): ProviderOperationResult<Unit> = transitionSuccess()

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
            recoverCalls++
            return ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0))
        }

        private fun transitionSuccess(): ProviderOperationResult<Unit> {
            transitionCalls++
            return ProviderOperationResult.Success(Unit)
        }
    }

    private class RecordingSchedulerProvider : SchedulerProvider {
        var scheduleCalls: Int = 0
            private set
        var cancelCalls: Int = 0
            private set

        val totalOperationCalls: Int
            get() = scheduleCalls + cancelCalls

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-builder-circuit"),
            name = ProviderName("Builder Circuit Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(
            request: ScheduleRequest,
        ): ProviderOperationResult<ScheduleReceipt> {
            scheduleCalls += 1
            return ProviderOperationResult.Success(ScheduleReceipt(request.id))
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> {
            cancelCalls += 1
            return ProviderOperationResult.Success(Unit)
        }
    }

    private class TestStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-builder-circuit"),
            name = ProviderName("Builder Circuit Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

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
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private class TestTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-builder-circuit"),
            name = ProviderName("Builder Circuit Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(TestError())

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }

    private data class TestError(
        override val code: ErrorCode = ErrorCode("BUILDER_CIRCUIT_TEST_ERROR"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Builder circuit test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private object Pending

    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) rawResult = result.getOrNull()
                    else thrown = result.exceptionOrNull()
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) { "Suspend block did not complete synchronously." }
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }
}
