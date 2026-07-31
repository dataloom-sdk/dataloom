package io.dataloom.runtime.worker

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.ProviderId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
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
import io.dataloom.api.scheduling.ExistingSchedulePolicy
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
import io.dataloom.runtime.queue.QueueCircuitPreExecutionDecision
import io.dataloom.runtime.queue.QueueProcessingFailureStage
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingSummary
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.QueueCircuitOperation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class CircuitBreakerQueueWorkerCoordinatorTest {

    @Test
    fun `constructor rejects recovery scope mismatch before state or provider access`() {
        val provider = RecordingQueueProvider()
        val store = MapCircuitStore()
        val processing = RecordingProcessingEngine(noWork())
        val wrongScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = QueueCircuitOperation.ACQUIRE.retryOperation,
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator(provider, store, processing, recoveryScope = wrongScope)
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.recoveryCalls)
        assertEquals(0, processing.calls)
    }

    @Test
    fun `disabled recovery performs no circuit or provider operation`() = runTest {
        val provider = RecordingQueueProvider()
        val store = MapCircuitStore()
        val processing = RecordingProcessingEngine(noWork())
        val clock = CountingClock(now)
        val worker = coordinator(
            provider = provider,
            store = store,
            processing = processing,
            clock = clock,
            configuration = configuration(recover = false),
        )

        val result = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            worker.run(runRequest(includeRecovery = false)),
        )

        assertIs<CircuitBreakerQueueWorkerRecoveryResult.NotRequested>(result.recoveryResult)
        assertIs<CircuitBreakerQueueProcessingResult.NoWork>(result.processingResult)
        assertIs<QueueWorkerSchedulingResult.NotRequired>(result.schedulingResult)
        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.recoveryCalls)
        assertEquals(1, processing.calls)
        assertEquals(0, clock.readCalls)
    }

    @Test
    fun `enabled recovery requires request before circuit or processing`() = runTest {
        val provider = RecordingQueueProvider()
        val store = MapCircuitStore()
        val processing = RecordingProcessingEngine(noWork())
        val worker = coordinator(provider, store, processing)

        assertFailsWith<IllegalArgumentException> {
            worker.run(runRequest(includeRecovery = false))
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.recoveryCalls)
        assertEquals(0, processing.calls)
    }

    @Test
    fun `open recovery circuit stops before provider processing and scheduling`() = runTest {
        val scope = recoveryScope()
        val store = MapCircuitStore(
            initialRecords = mapOf(scope to openRecord(scope)),
        )
        val provider = RecordingQueueProvider()
        val processing = RecordingProcessingEngine(noWork())
        val scheduler = RecordingSchedulerProvider()
        val worker = coordinator(provider, store, processing, scheduler = scheduler)

        val stopped = assertIs<CircuitBreakerQueueWorkerRunResult.RecoveryStopped>(
            worker.run(runRequest()),
        )
        val recovery = assertIs<CircuitBreakerQueueWorkerRecoveryResult.PreExecutionStopped>(
            stopped.recoveryResult,
        )
        val rejected = assertIs<QueueCircuitPreExecutionDecision.Rejected>(recovery.decision)

        assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)
        assertEquals(0, provider.recoveryCalls)
        assertEquals(0, processing.calls)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `recovery provider failure preserves classification and stops cycle`() = runTest {
        val providerError = error("RECOVERY_UNAVAILABLE", ErrorCategory.QUEUE)
        val provider = RecordingQueueProvider(
            recoveryResult = ProviderOperationResult.Failure(providerError),
        )
        val processing = RecordingProcessingEngine(noWork())
        val worker = coordinator(provider, MapCircuitStore(), processing)

        val stopped = assertIs<CircuitBreakerQueueWorkerRunResult.RecoveryStopped>(
            worker.run(runRequest()),
        )
        val recovery = assertIs<CircuitBreakerQueueWorkerRecoveryResult.ProviderFailure>(
            stopped.recoveryResult,
        )

        assertSame(providerError, recovery.error)
        assertEquals(io.dataloom.runtime.queue.QueueCircuitProviderFailureDisposition.CIRCUIT_FAILURE, recovery.disposition)
        assertIs<CircuitBreakerRecordResult.Recorded>(recovery.recordResult)
        assertEquals(1, provider.recoveryCalls)
        assertEquals(0, processing.calls)
    }

    @Test
    fun `recovery success with failed record preserves result and stops before processing`() = runTest {
        val scope = recoveryScope()
        val storeError = error("RECOVERY_RECORD_FAILED", ErrorCategory.STORAGE)
        val store = MapCircuitStore(
            initialRecords = mapOf(scope to closedFailureRecord(scope)),
            compareFailures = mapOf(scope to storeError),
        )
        val recoveryValue = ExpiredLeaseRecoveryResult(3)
        val provider = RecordingQueueProvider(
            recoveryResult = ProviderOperationResult.Success(recoveryValue),
        )
        val processing = RecordingProcessingEngine(noWork())
        val worker = coordinator(provider, store, processing)

        val stopped = assertIs<CircuitBreakerQueueWorkerRunResult.RecoveryStopped>(
            worker.run(runRequest()),
        )
        val recovery = assertIs<CircuitBreakerQueueWorkerRecoveryResult.CircuitRecordingUnconfirmed>(
            stopped.recoveryResult,
        )

        assertSame(recoveryValue, recovery.result)
        assertSame(
            storeError,
            assertIs<CircuitBreakerRecordResult.PersistenceFailure>(recovery.recordResult).error,
        )
        assertEquals(1, provider.recoveryCalls)
        assertEquals(0, processing.calls)
    }

    @Test
    fun `accepted recovery and terminal processing perform no scheduling`() = runTest {
        val recoveryValue = ExpiredLeaseRecoveryResult(2)
        val provider = RecordingQueueProvider(
            recoveryResult = ProviderOperationResult.Success(recoveryValue),
        )
        val terminal = CircuitBreakerQueueProcessingResult.PreExecutionStopped(
            operation = QueueCircuitOperation.ACQUIRE,
            stage = QueueProcessingFailureStage.ACQUISITION,
            decision = QueueCircuitPreExecutionDecision.Rejected(
                CircuitBreakerRejectionReason.OPEN,
            ),
            summary = QueueProcessingSummary(),
        )
        val processing = RecordingProcessingEngine(terminal)
        val scheduler = RecordingSchedulerProvider()
        val worker = coordinator(provider, MapCircuitStore(), processing, scheduler = scheduler)

        val stopped = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingStopped>(
            worker.run(runRequest()),
        )

        assertSame(terminal, stopped.processingResult)
        val recovery = assertIs<CircuitBreakerQueueWorkerRecoveryResult.Completed>(
            stopped.recoveryResult,
        )
        assertSame(recoveryValue, recovery.result)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `normal processed result schedules once using continuation evidence`() = runTest {
        val processed = processed(
            acquisitionLimitReached = true,
            earliestRescheduledAt = null,
            earliestDeferredAt = null,
        )
        val processing = RecordingProcessingEngine(processed)
        val scheduler = RecordingSchedulerProvider()
        val worker = coordinator(
            provider = RecordingQueueProvider(),
            store = MapCircuitStore(),
            processing = processing,
            scheduler = scheduler,
            configuration = configuration(recover = false),
        )

        val completed = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            worker.run(runRequest(includeRecovery = false)),
        )
        val scheduled = assertIs<QueueWorkerSchedulingResult.Scheduled>(
            completed.schedulingResult,
        )

        assertEquals(1, scheduler.scheduleCalls)
        assertEquals(SchedulingDelay(250L), scheduled.plan.delay)
        assertEquals(QueueWorkerWakeUpReason.ACQUISITION_LIMIT_REACHED, scheduled.plan.reason)
        assertEquals(SchedulingDelay(250L), scheduler.lastRequest?.delay)
    }

    @Test
    fun `future availability uses one clock read and scheduler failure preserves processing`() = runTest {
        val processed = processed(
            acquisitionLimitReached = false,
            earliestRescheduledAt = DataLoomInstant(1_600L),
            earliestDeferredAt = DataLoomInstant(1_400L),
        )
        val schedulerError = error("SCHEDULER_FAILED", ErrorCategory.SCHEDULER)
        val scheduler = RecordingSchedulerProvider(
            scheduleResult = ProviderOperationResult.Failure(schedulerError),
        )
        val clock = CountingClock(now)
        val worker = coordinator(
            provider = RecordingQueueProvider(),
            store = MapCircuitStore(),
            processing = RecordingProcessingEngine(processed),
            scheduler = scheduler,
            clock = clock,
            configuration = configuration(recover = false),
        )

        val completed = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            worker.run(runRequest(includeRecovery = false)),
        )
        val failed = assertIs<QueueWorkerSchedulingResult.SchedulerFailed>(
            completed.schedulingResult,
        )

        assertSame(processed, completed.processingResult)
        assertSame(schedulerError, failed.error)
        assertEquals(SchedulingDelay(400L), failed.plan.delay)
        assertEquals(QueueWorkerWakeUpReason.RETRY_AND_DEFERRAL_AVAILABLE, failed.plan.reason)
        assertEquals(1, clock.readCalls)
    }

    @Test
    fun `zero scheduler timeout prevents invocation after normal processing`() = runTest {
        val scheduler = RecordingSchedulerProvider()
        val worker = coordinator(
            provider = RecordingQueueProvider(),
            store = MapCircuitStore(),
            processing = RecordingProcessingEngine(processed(acquisitionLimitReached = true)),
            scheduler = scheduler,
            configuration = configuration(
                recover = false,
                schedulerTimeout = SchedulingDelay.ZERO,
            ),
        )

        val completed = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
            worker.run(runRequest(includeRecovery = false)),
        )
        val failed = assertIs<QueueWorkerSchedulingResult.SchedulerFailed>(
            completed.schedulingResult,
        )

        assertEquals("SCHEDULER_PROVIDER_TIMEOUT", failed.error.code.value)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `recovery cancellation propagates without processing or scheduling`() = runTest {
        val provider = RecordingQueueProvider(cancelRecovery = true)
        val processing = RecordingProcessingEngine(noWork())
        val scheduler = RecordingSchedulerProvider()
        val worker = coordinator(provider, MapCircuitStore(), processing, scheduler = scheduler)

        val failure = assertFailsWith<CancellationException> {
            worker.run(runRequest())
        }

        assertEquals("recovery cancelled", failure.message)
        assertEquals(0, processing.calls)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `runtime factory performs no provider store clock processing or scheduling work`() {
        val provider = RecordingQueueProvider()
        val store = MapCircuitStore()
        val clock = CountingClock(now)
        val scheduler = RecordingSchedulerProvider()
        val gate = CircuitBreakerExecutionGate(coordinator(store, clock))

        CircuitBreakerQueueWorkerRuntime.create(
            queueProvider = provider,
            executionGate = gate,
            recoveryScope = recoveryScope(),
            processingScopes = io.dataloom.runtime.queue.QueueProcessingCircuitScopes(
                acquisition = CircuitBreakerScope.providerOperation(
                    providerId,
                    QueueCircuitOperation.ACQUIRE.retryOperation,
                ),
                completion = CircuitBreakerScope.providerOperation(
                    providerId,
                    QueueCircuitOperation.COMPLETE.retryOperation,
                ),
                reschedule = CircuitBreakerScope.providerOperation(
                    providerId,
                    QueueCircuitOperation.RESCHEDULE.retryOperation,
                ),
                deferral = CircuitBreakerScope.providerOperation(
                    providerId,
                    QueueCircuitOperation.DEFER.retryOperation,
                ),
                failure = CircuitBreakerScope.providerOperation(
                    providerId,
                    QueueCircuitOperation.FAIL.retryOperation,
                ),
                cancellation = CircuitBreakerScope.providerOperation(
                    providerId,
                    QueueCircuitOperation.CANCEL.retryOperation,
                ),
            ),
            executionHandler = io.dataloom.runtime.queue.QueueEntryExecutionHandler {
                error("must not run during construction")
            },
            schedulerProvider = scheduler,
            clock = clock,
            configuration = configuration(recover = true),
        )

        assertEquals(0, provider.recoveryCalls)
        assertEquals(0, provider.acquireCalls)
        assertEquals(0, store.loadCalls)
        assertEquals(0, clock.readCalls)
        assertEquals(0, scheduler.scheduleCalls)
    }

    private fun coordinator(
        provider: QueueProvider,
        store: CircuitBreakerStateStore,
        processing: CircuitBreakerQueueProcessingEngine,
        scheduler: SchedulerProvider? = null,
        clock: CountingClock = CountingClock(now),
        configuration: QueueWorkerConfiguration = configuration(recover = true),
        recoveryScope: CircuitBreakerScope = recoveryScope(),
    ): CircuitBreakerQueueWorkerCoordinator = CircuitBreakerQueueWorkerCoordinator(
        queueOperationAdapter = CircuitBreakerQueueOperationAdapter(
            queueProvider = provider,
            executionGate = CircuitBreakerExecutionGate(coordinator(store, clock)),
        ),
        recoveryScope = recoveryScope,
        queueProcessor = processing,
        schedulerProvider = scheduler,
        clock = clock,
        configuration = configuration,
    )

    private fun coordinator(
        store: CircuitBreakerStateStore,
        clock: DataLoomClock,
    ): CircuitBreakerCoordinator = CircuitBreakerCoordinator(
        configuration = CircuitBreakerConfiguration(
            failureThreshold = 1,
            failureWindow = SchedulingDelay(1_000L),
            openDuration = SchedulingDelay(10_000L),
        ),
        clock = clock,
        stateStore = store,
    )

    private class RecordingProcessingEngine(
        private val result: CircuitBreakerQueueProcessingResult,
    ) : CircuitBreakerQueueProcessingEngine {
        var calls: Int = 0
            private set

        override suspend fun process(
            request: QueueProcessingRequest,
        ): CircuitBreakerQueueProcessingResult {
            calls++
            return result
        }
    }

    private class RecordingQueueProvider(
        private val recoveryResult: ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0)),
        private val cancelRecovery: Boolean = false,
    ) : QueueProvider {
        var recoveryCalls: Int = 0
            private set
        var acquireCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Circuit Worker Queue"),
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
        ): ProviderOperationResult<QueueAcquireResult> {
            acquireCalls++
            return ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
        }

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
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
            recoveryCalls++
            if (cancelRecovery) throw CancellationException("recovery cancelled")
            return recoveryResult
        }
    }

    private class RecordingSchedulerProvider(
        private val scheduleResult: ProviderOperationResult<ScheduleReceipt> =
            ProviderOperationResult.Success(ScheduleReceipt(scheduleId)),
    ) : SchedulerProvider {
        var scheduleCalls: Int = 0
            private set
        var lastRequest: ScheduleRequest? = null
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("circuit-worker-scheduler"),
            name = ProviderName("Circuit Worker Scheduler"),
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
            scheduleCalls++
            lastRequest = request
            return scheduleResult
        }

        override suspend fun cancel(
            request: io.dataloom.api.scheduling.ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private class CountingClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        var readCalls: Int = 0
            private set

        override fun now(): DataLoomInstant {
            readCalls++
            return instant
        }
    }

    private class MapCircuitStore(
        initialRecords: Map<CircuitBreakerScope, CircuitBreakerStateRecord> = emptyMap(),
        private val compareFailures: Map<CircuitBreakerScope, DataLoomError> = emptyMap(),
    ) : CircuitBreakerStateStore {
        private val records = initialRecords.toMutableMap()
        var loadCalls: Int = 0
            private set

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
            compareFailures[request.scope]?.let { return ProviderOperationResult.Failure(it) }
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

    private companion object {
        val providerId = ProviderId("circuit-worker-queue")
        val scheduleId = ScheduleId("circuit-worker-schedule")
        val now = DataLoomInstant(1_000L)

        fun recoveryScope(): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId,
            QueueCircuitOperation.RECOVER_EXPIRED_LEASES.retryOperation,
        )

        fun configuration(
            recover: Boolean,
            schedulerTimeout: SchedulingDelay? = null,
        ): QueueWorkerConfiguration = QueueWorkerConfiguration(
            scheduleId = scheduleId,
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay(250L),
            recoverExpiredLeasesBeforeProcessing = recover,
            schedulerProviderTimeout = schedulerTimeout,
        )

        fun runRequest(includeRecovery: Boolean = true): QueueWorkerRunRequest =
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    QueueAcquireRequest(
                        consumerId = QueueConsumerId("circuit-worker-consumer"),
                        leaseId = QueueLeaseId("circuit-worker-lease"),
                        acquiredAt = now,
                        leaseExpiresAt = DataLoomInstant(2_000L),
                        maxEntries = 2,
                    ),
                ),
                recoveryRequest = if (includeRecovery) {
                    ExpiredLeaseRecoveryRequest(now)
                } else {
                    null
                },
            )

        fun acquisitionRecord(): QueueCircuitOperationRecord = QueueCircuitOperationRecord(
            operation = QueueCircuitOperation.ACQUIRE,
            recordResult = CircuitBreakerRecordResult.Ignored,
        )

        fun noWork(): CircuitBreakerQueueProcessingResult =
            CircuitBreakerQueueProcessingResult.NoWork(acquisitionRecord())

        fun processed(
            acquisitionLimitReached: Boolean = false,
            earliestRescheduledAt: DataLoomInstant? = null,
            earliestDeferredAt: DataLoomInstant? = null,
        ): CircuitBreakerQueueProcessingResult.Processed =
            CircuitBreakerQueueProcessingResult.Processed(
                summary = QueueProcessingSummary(acquired = 1, executed = 1, completed = 1),
                acquisitionLimitReached = acquisitionLimitReached,
                earliestRescheduledAt = earliestRescheduledAt,
                earliestDeferredAt = earliestDeferredAt,
                operationRecords = listOf(acquisitionRecord()),
            )

        fun openRecord(scope: CircuitBreakerScope): CircuitBreakerStateRecord =
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.OPEN,
                    consecutiveFailures = 0,
                    failureWindowStartedAt = null,
                    openUntil = DataLoomInstant(10_000L),
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = now,
                ),
                version = 0L,
            )

        fun closedFailureRecord(scope: CircuitBreakerScope): CircuitBreakerStateRecord =
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.CLOSED,
                    consecutiveFailures = 1,
                    failureWindowStartedAt = now,
                    openUntil = null,
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = now,
                ),
                version = 0L,
            )

        fun error(code: String, category: ErrorCategory): DataLoomError = TestError(
            code = ErrorCode(code),
            category = category,
        )
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Circuit queue worker test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
