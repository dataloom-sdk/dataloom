from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1))


def write_new(path: str, content: str) -> None:
    file = Path(path)
    if file.exists():
        raise SystemExit(f"Refusing to replace existing file {path}")
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(content)


# ---------------------------------------------------------------------------
# Runtime tests for exact scheduler circuit evidence.
# ---------------------------------------------------------------------------

write_new(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerSchedulerCircuitTest.kt",
    """package io.dataloom.runtime.worker

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
            directSchedulerProvider = null,
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
""",
)


# ---------------------------------------------------------------------------
# Builder tests for structural assembly and scope validation.
# ---------------------------------------------------------------------------

builder_test_path = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/facade/"
    "DataLoomBuilderCircuitQueueWorkerTest.kt"
)
replace_once(
    builder_test_path,
    """import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
""",
    """import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
""",
)
replace_once(
    builder_test_path,
    """import io.dataloom.runtime.retry.QueueCircuitOperation
""",
    """import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.SchedulerCircuitOperation
""",
)
replace_once(
    builder_test_path,
    """    private fun builder(
        queue: RecordingQueueProvider,
        clock: RecordingClock = RecordingClock(),
    ): DataLoomBuilder {
        val storage = TestStorageProvider()
        val transport = TestTransportProvider()
        return DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies(clock))
            .providers(storage, transport, queue)
            .defaultProviderBindings(
                SynchronizationProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                    queueProviderId = queue.descriptor.id,
                ),
            )
    }
""",
    """    private fun builder(
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
""",
)
replace_once(
    builder_test_path,
    """    private fun circuitSpec(
""",
    """    @Test
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
""",
)
replace_once(
    builder_test_path,
    """    private class TestStorageProvider : StorageProvider {
""",
    """    private class RecordingSchedulerProvider : SchedulerProvider {
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
""",
)


# ---------------------------------------------------------------------------
# External consumer probe.
# ---------------------------------------------------------------------------

write_new(
    "runtime-external-consumer/src/commonMain/kotlin/io/dataloom/consumer/"
    "BuilderCircuitQueueWorkerSchedulerExternalConsumerProbe.kt",
    """package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomCircuitQueueWorkerSchedulerSpec
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult

/** External-consumer probe for builder scheduler-circuit configuration and evidence. */
public object BuilderCircuitQueueWorkerSchedulerExternalConsumerProbe {

    public fun configure(
        builder: DataLoomBuilder,
        configuration: CircuitBreakerConfiguration,
        stateStore: CircuitBreakerStateStore,
        scope: CircuitBreakerScope,
        classifier: CircuitBreakerFailureClassifier =
            DefaultCircuitBreakerFailureClassifier,
    ): DataLoomBuilder = builder.circuitQueueWorkerSchedulerConfiguration(
        DataLoomCircuitQueueWorkerSchedulerSpec(
            circuitBreakerConfiguration = configuration,
            circuitBreakerStateStore = stateStore,
            scope = scope,
            failureClassifier = classifier,
        ),
    )

    public fun circuitExecution(
        result: QueueWorkerSchedulingResult,
    ): CircuitBreakerExecutionResult<ScheduleReceipt>? =
        (result as? QueueWorkerSchedulingResult.CircuitProtected)?.executionResult
}
""",
)


# ---------------------------------------------------------------------------
# API and audit documentation.
# ---------------------------------------------------------------------------

write_new(
    "docs/api/circuit-queue-worker-scheduler.md",
    """# Circuit-protected queue-worker scheduling

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Follow-up queue-worker scheduling can use a
> separately configured timeout and circuit boundary while preserving exact
> provider and post-execution circuit-recording evidence.

## Purpose

A queue transition may already be durable before the worker requests another
platform wake-up. Scheduler execution is therefore a separate dependency
boundary and must not reuse queue-provider circuit policy implicitly.

The circuit-aware path preserves all of these cases:

- the scheduler circuit rejects before provider invocation;
- circuit state cannot be loaded before provider invocation;
- permission contention is exhausted;
- the scheduler returns a canonical failure;
- the scheduler accepts the request and circuit recording is accepted; and
- the scheduler accepts the request but later circuit recording is unconfirmed.

The last case is especially important: an accepted schedule must not be
submitted again merely because a later circuit-state write failed.

## Public contracts

- `SchedulerCircuitOperation.SCHEDULE`
- `DataLoomCircuitQueueWorkerSchedulerSpec`
- `DataLoomBuilder.circuitQueueWorkerSchedulerConfiguration(...)`
- `CircuitBreakerQueueWorkerRuntime.createWithSchedulerCircuit(...)`
- `QueueWorkerSchedulingResult.CircuitProtected`

`CircuitProtected.executionResult` contains the complete
`CircuitBreakerExecutionResult<ScheduleReceipt>`.

## Composition order

When both controls are configured, scheduling is assembled as:

```text
SchedulerProvider
    ↓ provider timeout
TimeoutEnforcingSchedulerProvider
    ↓ circuit permission / classification / recording
CircuitBreakerRetrySchedulingAdapter
    ↓ enriched worker result
QueueWorkerSchedulingResult.CircuitProtected
```

A zero timeout prevents delegate invocation and returns the stable
`SCHEDULER_PROVIDER_TIMEOUT` failure through the circuit adapter. The configured
scheduler classifier decides whether the canonical provider failure contributes
to circuit health.

## Explicit policy

The application supplies a separate:

- `CircuitBreakerConfiguration`;
- durable `CircuitBreakerStateStore`;
- exact global, workflow, provider, or `scheduler.schedule` scope; and
- optional scheduler failure classifier.

The builder never reuses queue circuit state, thresholds, scopes, or
classification. Provider-bearing scopes must match the bound scheduler.
Operation-bearing scopes must be exactly `scheduler.schedule`.

## Evidence and replay safety

`QueueWorkerSchedulingResult.CircuitProtected` does not collapse the execution
into `Scheduled` or `SchedulerFailed`.

When the nested execution result is `Executed`, callers can inspect both:

1. the scheduler provider outcome; and
2. the later `CircuitBreakerRecordResult`.

A successful `ScheduleReceipt` therefore remains visible even when recording
returns persistence failure, contention exhaustion, stale-probe evidence, clock
regression, or an expired probe lease. The worker performs at most one scheduler
call and never automatically retries inside the same cycle.

## Side-effect boundary

Configuration and builder assembly perform no store access, provider operation,
timeout execution, clock read, scheduling, identifier generation, or coroutine
launch. No-wake-up processing returns `NotRequired` without consulting the
scheduler circuit.

## Remaining V1 work

This slice does not complete DL-040. Transport/storage circuit and timeout
assembly, protocol connection/request/idle adapters, production KMP iOS
persistence, authorized operations, complete observability, contention/restart
qualification, and `AC-FUNC-004` remain open.
""",
)

write_new(
    "docs/audits/DL-040-circuit-queue-worker-scheduler-checkpoint.md",
    """# DL-040 Circuit-protected Queue-worker Scheduler Checkpoint

## Decision

Queue-worker scheduling may use a separately configured circuit without
collapsing an accepted schedule into a failure when later circuit persistence is
unconfirmed.

This checkpoint advances the scheduling integration portion of FR-RETRY-007,
FR-RETRY-009, and FR-RETRY-010. It does not complete DL-040 or DataLoom V1.

## Invariants

- Queue circuit policy is never reused implicitly for the scheduler.
- Provider-bearing scheduler scopes identify the exact scheduler provider.
- Operation-bearing scopes use `scheduler.schedule`.
- Scheduler timeout is applied before scheduler circuit classification.
- A provider call occurs at most once after permission.
- No-wake-up processing accesses neither scheduler nor scheduler circuit state.
- Caller cancellation and unexpected exceptions propagate.
- Confirmed queue transitions are never rolled back after scheduling outcomes.
- Scheduler acceptance remains visible when post-execution circuit recording is
  unconfirmed.
- The worker never automatically resubmits an accepted schedule in the same
  cycle.

## Public evidence

`QueueWorkerSchedulingResult.CircuitProtected` preserves the complete
`CircuitBreakerExecutionResult<ScheduleReceipt>`, including pre-execution
rejection, provider failure/success, and the exact `CircuitBreakerRecordResult`.

## Required qualification

The review branch must prove:

- exact provider and operation scope validation;
- open-circuit and persistence-failure rejection before provider invocation;
- recoverable scheduler failure opens the selected circuit;
- zero timeout prevents delegate invocation and contributes to circuit health;
- accepted scheduling plus failed circuit recording preserves both facts;
- no-wake-up performs no scheduler/store access;
- builder configuration requires a circuit worker and valid scheduler binding;
- builder assembly is side-effect free;
- existing direct scheduling behavior remains compatible;
- external JVM and all current iOS consumers compile;
- JVM/Kotlin-Native ABI and public boundary checks pass;
- Apple XCFramework assembly succeeds; and
- permanent PR, Android, and Apple validation pass on one clean head.

## Remaining work

Transport/storage integration, protocol-specific timeouts, KMP iOS persistence,
manual retry/reclassification and circuit administration, complete
observability, multi-process/restart/contention tests, and full AC-FUNC-004
evidence remain open.
""",
)

readme = "docs/api/README.md"
replace_once(
    readme,
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [circuit-aware queue submission](./circuit-queue-submission.md), [circuit-aware queue processing](./circuit-queue-processing.md), [circuit-aware queue worker](./circuit-queue-worker.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [circuit-aware queue submission](./circuit-queue-submission.md), [circuit-aware queue processing](./circuit-queue-processing.md), [circuit-aware queue worker](./circuit-queue-worker.md), [circuit-protected worker scheduling](./circuit-queue-worker-scheduler.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
)
replace_once(
    readme,
    """| [Builder circuit-aware queue worker](./builder-circuit-queue-worker.md) | Partial V1 subsystem | Explicit facade assembly, durable state-store injection, scope validation, and mutually exclusive worker selection. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
""",
    """| [Builder circuit-aware queue worker](./builder-circuit-queue-worker.md) | Partial V1 subsystem | Explicit facade assembly, durable state-store injection, scope validation, and mutually exclusive worker selection. |
| [Circuit-protected worker scheduling](./circuit-queue-worker-scheduler.md) | Partial V1 subsystem | Separate scheduler timeout/circuit policy with exact accepted-schedule and recording evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
""",
)
replace_once(
    readme,
    """circuit-aware recovery/worker coordination and explicit builder/facade adoption
now exist. Scheduler-circuit policy, KMP iOS persistence, and end-to-end
qualification remain open.
""",
    """circuit-aware recovery/worker coordination, explicit builder/facade adoption,
and separately configured scheduler-circuit policy now exist. KMP iOS
persistence and end-to-end qualification remain open.
""",
)
replace_once(
    readme,
    """V1 retry work still requires scheduler-circuit policy, complete
transport/storage circuit assembly,
""",
    """V1 retry work still requires complete transport/storage circuit assembly,
""",
)

builder_doc = "docs/api/builder-circuit-queue-worker.md"
replace_once(
    builder_doc,
    """> circuit state. Scheduler-circuit policy, transport/storage assembly, KMP iOS
> persistence, observability, administration, and end-to-end qualification
""",
    """> circuit state and optional separately governed scheduler circuit policy.
> Transport/storage assembly, KMP iOS persistence, observability,
> administration, and end-to-end qualification
""",
)
replace_once(
    builder_doc,
    """- `DataLoomBuilder.circuitQueueWorkerConfiguration(...)`
- `DataLoom.circuitQueueWorker`
""",
    """- `DataLoomBuilder.circuitQueueWorkerConfiguration(...)`
- `DataLoomCircuitQueueWorkerSchedulerSpec`
- `DataLoomBuilder.circuitQueueWorkerSchedulerConfiguration(...)`
- `DataLoom.circuitQueueWorker`
""",
)
replace_once(
    builder_doc,
    """`QueueWorkerConfiguration.schedulerProviderTimeout` remains independent and
applies only to follow-up scheduling. This builder slice does not silently apply
a queue circuit scope to the scheduler.
""",
    """`QueueWorkerConfiguration.schedulerProviderTimeout` remains independent and
applies only to follow-up scheduling.

`DataLoomBuilder.circuitQueueWorkerSchedulerConfiguration(...)` may additionally
supply a separate scheduler circuit configuration, durable state store, exact
scope, and classifier. Timeout is applied before circuit adaptation. Queue
circuit state, scope, thresholds, and classification are never reused.
""",
)
replace_once(
    builder_doc,
    """    .defaultProviderBindings(bindings)
    .circuitQueueWorkerConfiguration(circuitSpec)
    .build()
""",
    """    .defaultProviderBindings(bindings)
    .circuitQueueWorkerConfiguration(circuitSpec)
    .circuitQueueWorkerSchedulerConfiguration(
        DataLoomCircuitQueueWorkerSchedulerSpec(
            circuitBreakerConfiguration = schedulerCircuitConfiguration,
            circuitBreakerStateStore = schedulerCircuitStateStore,
            scope = schedulerScheduleScope,
        ),
    )
    .build()
""",
)
replace_once(
    builder_doc,
    """- separately configured scheduler circuit protection with enriched scheduling
  evidence;
- transport and storage timeout/circuit assembly;
""",
    """- transport and storage timeout/circuit assembly;
""",
)

worker_doc = "docs/api/circuit-queue-worker.md"
replace_once(
    worker_doc,
    """> coordination path with explicit DataLoomBuilder/facade adoption.
> Scheduler-circuit policy, production KMP iOS persistence, observability,
> administration, and end-to-end
""",
    """> coordination path with explicit DataLoomBuilder/facade adoption and optional
> separately governed scheduler-circuit policy. Production KMP iOS persistence,
> observability, administration, and end-to-end
""",
)
replace_once(
    worker_doc,
    """- Queue-provider circuit scopes are not silently reused as scheduler circuit
  policy.

A scheduler failure after normal queue processing does not roll back confirmed
queue transitions.
""",
    """- Queue-provider circuit scopes are not silently reused as scheduler circuit
  policy.
- Optional scheduler circuit policy preserves complete permission, provider, and
  post-execution recording evidence.
- Scheduler acceptance remains visible when later circuit-state recording is
  unconfirmed.

A scheduler failure after normal queue processing does not roll back confirmed
queue transitions. An accepted schedule is never automatically resubmitted
because later circuit recording failed.
""",
)
replace_once(
    worker_doc,
    """`CircuitBreakerQueueWorkerRuntime.create(...)` constructs:
""",
    """`CircuitBreakerQueueWorkerRuntime.create(...)` constructs the direct
scheduler path. `createWithSchedulerCircuit(...)` additionally assembles timeout
before a separately governed scheduler circuit.

The direct path constructs:
""",
)
replace_once(
    worker_doc,
    """- circuit protection for queue-worker scheduling where configured;
- transport and storage circuit/timeout assembly;
""",
    """- transport and storage circuit/timeout assembly;
""",
)
