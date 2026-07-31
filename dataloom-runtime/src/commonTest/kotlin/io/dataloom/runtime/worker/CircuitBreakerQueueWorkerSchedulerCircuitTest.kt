package io.dataloom.runtime.worker

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
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
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingEngine
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueCircuitOperationRecord
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingSummary
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRetrySchedulingAdapter
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.SchedulerCircuitOperation
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class CircuitBreakerQueueWorkerSchedulerCircuitTest {
    private val queueProviderId = ProviderId("queue-worker-scheduler-circuit")
    private val schedulerProviderId = ProviderId("worker-scheduler-circuit")
    private val schedulerScope = CircuitBreakerScope.providerOperation(
        providerId = schedulerProviderId,
        operation = SchedulerCircuitOperation.SCHEDULE.retryOperation,
    )

    @Test
    fun `accepted schedule with failed circuit recording preserves both facts`() {
        val recordingError = TestError(
            code = ErrorCode("SCHEDULER_CIRCUIT_WRITE_FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val store = RecordingCircuitStore(
            initial = closedFailureRecord(schedulerScope),
            compareFailure = recordingError,
        )
        val scheduler = RecordingScheduler(schedulerProviderId)
        val coordinator = coordinator(
            scheduler = scheduler,
            schedulerStore = store,
        )

        val result = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            runSuspend { coordinator.run(runRequest()) },
        )
        val scheduling = assertIs<QueueWorkerSchedulingResult.CircuitProtected>(
            result.schedulingResult,
        )
        val executed = assertIs<CircuitBreakerExecutionResult.Executed<ScheduleReceipt>>(
            scheduling.executionResult,
        )
        assertEquals(
            ScheduleId("scheduler-circuit-wake-up"),
            assertIs<CircuitProtectedOperationResult.Success<ScheduleReceipt>>(
                executed.operationResult,
            ).value.id,
        )
        assertSame(
            recordingError,
            assertIs<CircuitBreakerRecordResult.PersistenceFailure>(
                executed.recordResult,
            ).error,
        )
        assertEquals(1, scheduler.scheduleCalls)
    }

    @Test
    fun `open scheduler circuit rejects before provider invocation`() {
        val store = RecordingCircuitStore(
            initial = openRecord(schedulerScope),
        )
        val scheduler = RecordingScheduler(schedulerProviderId)
        val coordinator = coordinator(scheduler, store)

        val result = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            runSuspend { coordinator.run(runRequest()) },
        )
        val protected = assertIs<QueueWorkerSchedulingResult.CircuitProtected>(
            result.schedulingResult,
        )
        val rejected = assertIs<CircuitBreakerExecutionResult.Rejected>(
            protected.executionResult,
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `permission persistence failure prevents scheduler invocation`() {
        val loadError = TestError(
            code = ErrorCode("SCHEDULER_CIRCUIT_LOAD_FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val store = RecordingCircuitStore(loadFailure = loadError)
        val scheduler = RecordingScheduler(schedulerProviderId)
        val coordinator = coordinator(scheduler, store)

        val result = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            runSuspend { coordinator.run(runRequest()) },
        )
        val protected = assertIs<QueueWorkerSchedulingResult.CircuitProtected>(
            result.schedulingResult,
        )
        assertSame(
            loadError,
            assertIs<CircuitBreakerExecutionResult.PermissionPersistenceFailure>(
                protected.executionResult,
            ).error,
        )
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `recoverable scheduler failure opens circuit and blocks next submission`() {
        val providerError = TestError(
            code = ErrorCode("SCHEDULER_UNAVAILABLE"),
            category = ErrorCategory.SCHEDULER,
            recoverability = Recoverability.RECOVERABLE,
        )
        val store = RecordingCircuitStore()
        val scheduler = RecordingScheduler(
            providerId = schedulerProviderId,
            scheduleResult = ProviderOperationResult.Failure(providerError),
        )
        val coordinator = coordinator(scheduler, store)

        val first = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            runSuspend { coordinator.run(runRequest()) },
        )
        val firstProtected = assertIs<QueueWorkerSchedulingResult.CircuitProtected>(
            first.schedulingResult,
        )
        val firstExecuted = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            firstProtected.executionResult,
        )
        assertSame(
            providerError,
            assertIs<CircuitProtectedOperationResult.Failure>(
                firstExecuted.operationResult,
            ).error,
        )
        assertIs<CircuitBreakerRecordResult.Recorded>(firstExecuted.recordResult)

        val second = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            runSuspend { coordinator.run(runRequest()) },
        )
        val secondProtected = assertIs<QueueWorkerSchedulingResult.CircuitProtected>(
            second.schedulingResult,
        )
        assertIs<CircuitBreakerExecutionResult.Rejected>(secondProtected.executionResult)
        assertEquals(1, scheduler.scheduleCalls)
    }

    @Test
    fun `zero timeout is classified before delegate and contributes to circuit health`() {
        val store = RecordingCircuitStore()
        val scheduler = RecordingScheduler(schedulerProviderId)
        val coordinator = coordinator(
            scheduler = scheduler,
            schedulerStore = store,
            schedulerTimeout = SchedulingDelay.ZERO,
        )

        val result = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            runSuspend { coordinator.run(runRequest()) },
        )
        val protected = assertIs<QueueWorkerSchedulingResult.CircuitProtected>(
            result.schedulingResult,
        )
        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            protected.executionResult,
        )
        assertEquals(
            "SCHEDULER_PROVIDER_TIMEOUT",
            assertIs<CircuitProtectedOperationResult.Failure>(
                executed.operationResult,
            ).error.code.value,
        )
        assertIs<CircuitBreakerRecordResult.Recorded>(executed.recordResult)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `no wake-up performs no scheduler circuit or provider access`() {
        val store = RecordingCircuitStore()
        val scheduler = RecordingScheduler(schedulerProviderId)
        val noWork = CircuitBreakerQueueProcessingResult.NoWork(
            acquisitionRecord = QueueCircuitOperationRecord(
                operation = QueueCircuitOperation.ACQUIRE,
                recordResult = CircuitBreakerRecordResult.Ignored,
            ),
        )
        val coordinator = coordinator(
            scheduler = scheduler,
            schedulerStore = store,
            processingResult = noWork,
        )

        val result = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            runSuspend { coordinator.run(runRequest()) },
        )
        assertIs<QueueWorkerSchedulingResult.NotRequired>(result.schedulingResult)
        assertEquals(0, store.loadCalls)
        assertEquals(0, store.compareAndSetCalls)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `scheduler operation scope mismatch fails during adapter construction`() {
        val scheduler = RecordingScheduler(schedulerProviderId)
        val store = RecordingCircuitStore()
        val gate = CircuitBreakerExecutionGate(circuitCoordinator(store))

        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerRetrySchedulingAdapter(
                schedulerProvider = scheduler,
                providerOperationAdapter = CircuitBreakerProviderOperationAdapter(gate),
                scope = CircuitBreakerScope.providerOperation(
                    providerId = schedulerProviderId,
                    operation = RetryOperation("scheduler.cancel"),
                ),
            )
        }
        assertEquals(0, store.loadCalls)
        assertEquals(0, scheduler.scheduleCalls)
    }

    private fun coordinator(
        scheduler: SchedulerProvider,
        schedulerStore: CircuitBreakerStateStore,
        processingResult: CircuitBreakerQueueProcessingResult = processedResult(),
        schedulerTimeout: SchedulingDelay? = null,
    ): CircuitBreakerQueueWorkerCoordinator {
        val queueStore = RecordingCircuitStore()
        val queueAdapter = CircuitBreakerQueueOperationAdapter(
            queueProvider = RecordingQueueProvider(queueProviderId),
            executionGate = CircuitBreakerExecutionGate(circuitCoordinator(queueStore)),
        )
        val protectedScheduler = checkNotNull(
            assembleQueueWorkerSchedulerProvider(
                provider = scheduler,
                timeout = schedulerTimeout,
                clock = FixedClock,
            ),
        )
        val schedulerAdapter = CircuitBreakerRetrySchedulingAdapter(
            schedulerProvider = protectedScheduler,
            providerOperationAdapter = CircuitBreakerProviderOperationAdapter(
                CircuitBreakerExecutionGate(circuitCoordinator(schedulerStore)),
            ),
            scope = schedulerScope,
        )
        return CircuitBreakerQueueWorkerCoordinator(
            queueOperationAdapter = queueAdapter,
            recoveryScope = CircuitBreakerScope.providerOperation(
                providerId = queueProviderId,
                operation = QueueCircuitOperation.RECOVER_EXPIRED_LEASES.retryOperation,
            ),
            queueProcessor = CircuitBreakerQueueProcessingEngine { processingResult },
            schedulerProvider = null,
            schedulerCircuitAdapter = schedulerAdapter,
            clock = FixedClock,
            configuration = configuration(schedulerTimeout),
        )
    }

    private fun circuitCoordinator(
        store: CircuitBreakerStateStore,
    ): CircuitBreakerCoordinator = CircuitBreakerCoordinator(
        configuration = CircuitBreakerConfiguration(
            failureThreshold = 1,
            failureWindow = SchedulingDelay(1_000L),
            openDuration = SchedulingDelay(1_000L),
        ),
        clock = FixedClock,
        stateStore = store,
    )

    private fun processedResult(): CircuitBreakerQueueProcessingResult.Processed =
        CircuitBreakerQueueProcessingResult.Processed(
            summary = QueueProcessingSummary(
                acquired = 1,
                executed = 1,
                completed = 1,
                rescheduled = 0,
                failed = 0,
                cancelled = 0,
            ),
            acquisitionLimitReached = true,
            operationRecords = emptyList(),
        )

    private fun configuration(
        schedulerTimeout: SchedulingDelay?,
    ): QueueWorkerConfiguration = QueueWorkerConfiguration(
        scheduleId = ScheduleId("scheduler-circuit-wake-up"),
        constraints = ScheduleConstraints(),
        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        continuationDelay = SchedulingDelay.ZERO,
        recoverExpiredLeasesBeforeProcessing = false,
        schedulerProviderTimeout = schedulerTimeout,
    )

    private fun runRequest(): QueueWorkerRunRequest = QueueWorkerRunRequest(
        processingRequest = QueueProcessingRequest(
            QueueAcquireRequest(
                consumerId = QueueConsumerId("scheduler-circuit-consumer"),
                leaseId = QueueLeaseId("scheduler-circuit-lease"),
                acquiredAt = DataLoomInstant(100L),
                leaseExpiresAt = DataLoomInstant(200L),
                maxEntries = 1,
            ),
        ),
        recoveryRequest = null,
    )

    private fun closedFailureRecord(
        scope: CircuitBreakerScope,
    ): CircuitBreakerStateRecord = CircuitBreakerStateRecord(
        state = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.CLOSED,
            consecutiveFailures = 1,
            failureWindowStartedAt = DataLoomInstant(50L),
            openUntil = null,
            probeGeneration = 0L,
            probeInFlight = false,
            updatedAt = DataLoomInstant(90L),
        ),
        version = 0L,
    )

    private fun openRecord(
        scope: CircuitBreakerScope,
    ): CircuitBreakerStateRecord = CircuitBreakerStateRecord(
        state = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.OPEN,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = DataLoomInstant(1_000L),
            probeGeneration = 0L,
            probeInFlight = false,
            updatedAt = DataLoomInstant(90L),
        ),
        version = 0L,
    )

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(100L)
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Sanitized scheduler circuit test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class RecordingCircuitStore(
        initial: CircuitBreakerStateRecord? = null,
        private val loadFailure: DataLoomError? = null,
        private val compareFailure: DataLoomError? = null,
    ) : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()
        var loadCalls: Int = 0
            private set
        var compareAndSetCalls: Int = 0
            private set

        init {
            if (initial != null) records[initial.state.scope] = initial
        }

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls += 1
            loadFailure?.let { return ProviderOperationResult.Failure(it) }
            return ProviderOperationResult.Success(
                records[scope]?.let(CircuitBreakerLoadResult::Found)
                    ?: CircuitBreakerLoadResult.Missing,
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            compareAndSetCalls += 1
            compareFailure?.let { return ProviderOperationResult.Failure(it) }
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(updated),
            )
        }
    }

    private class RecordingScheduler(
        providerId: ProviderId,
        private val scheduleResult: ProviderOperationResult<ScheduleReceipt>? = null,
    ) : SchedulerProvider {
        override val descriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Scheduler Circuit Test"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )
        var scheduleCalls: Int = 0
            private set

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
            return scheduleResult ?: ProviderOperationResult.Success(ScheduleReceipt(request.id))
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private class RecordingQueueProvider(
        providerId: ProviderId,
    ) : QueueProvider {
        override val descriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Queue Circuit Test"),
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
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Success(QueueAcquireResult.NoEntries)

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun reschedule(
            request: QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun defer(
            request: QueueDeferralRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun fail(
            request: QueueFailureRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun cancel(
            request: QueueCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0))
    }

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
