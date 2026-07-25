package io.dataloom.runtime.worker

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderId
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
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.DurableQueueExecutionProcessor
import io.dataloom.runtime.queue.QueueEntryExecutionHandler
import io.dataloom.runtime.queue.QueueEntryExecutionOutcome
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
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
 * Deterministic common tests for DL-032 queue worker coordinator.
 *
 * All fakes are stateless or deterministically stateful. No real queue
 * provider, real database, filesystem, Thread.sleep, arbitrary delay,
 * Android APIs, JVM-only APIs, reflection, ServiceLoader, system clock,
 * random identifiers, or production credentials are used.
 *
 * Suspend functions are exercised using [kotlin.coroutines.startCoroutine]
 * primitives from the Kotlin standard library, without requiring
 * kotlinx.coroutines.
 */
class QueueWorkerCoordinatorTest {

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
    // Shared time instants
    // =========================================================================

    private val t0 = DataLoomInstant(1_000_000L)
    private val t1 = DataLoomInstant(2_000_000L)
    private val t2 = DataLoomInstant(3_000_000L)
    private val t3 = DataLoomInstant(4_000_000L)
    private val t4 = DataLoomInstant(5_000_000L)

    // =========================================================================
    // Shared identifiers
    // =========================================================================

    private val consumerId = QueueConsumerId("consumer-001")
    private val leaseId = QueueLeaseId("lease-001")
    private val workerScheduleId = ScheduleId("worker-schedule-001")

    // =========================================================================
    // Shared configuration
    // =========================================================================

    private val continuationDelay = SchedulingDelay(30_000L)
    private val defaultConstraints = ScheduleConstraints()
    private val defaultPolicy = ExistingSchedulePolicy.REPLACE

    private val defaultConfiguration = QueueWorkerConfiguration(
        scheduleId = workerScheduleId,
        constraints = defaultConstraints,
        existingSchedulePolicy = defaultPolicy,
        continuationDelay = continuationDelay,
        recoverExpiredLeasesBeforeProcessing = false,
    )

    private val recoveryEnabledConfiguration = QueueWorkerConfiguration(
        scheduleId = workerScheduleId,
        constraints = defaultConstraints,
        existingSchedulePolicy = defaultPolicy,
        continuationDelay = continuationDelay,
        recoverExpiredLeasesBeforeProcessing = true,
    )

    // =========================================================================
    // Shared requests
    // =========================================================================

    private val sampleAcquireRequest = QueueAcquireRequest(
        consumerId = consumerId,
        leaseId = leaseId,
        acquiredAt = t0,
        leaseExpiresAt = t1,
        maxEntries = 5,
    )

    private val sampleProcessingRequest = QueueProcessingRequest(sampleAcquireRequest)

    private val sampleRecoveryRequest = ExpiredLeaseRecoveryRequest(currentTime = t0)

    private val noRecoveryRunRequest = QueueWorkerRunRequest(
        processingRequest = sampleProcessingRequest,
        recoveryRequest = null,
    )

    private val withRecoveryRunRequest = QueueWorkerRunRequest(
        processingRequest = sampleProcessingRequest,
        recoveryRequest = sampleRecoveryRequest,
    )

    // =========================================================================
    // Sample provider result helpers
    // =========================================================================

    private val sampleLease = QueueLease(
        id = leaseId,
        consumerId = consumerId,
        acquiredAt = t0,
        expiresAt = t1,
    )

    private fun sampleError(code: String = "DL-FAKE-001"): DataLoomError = FakeError(
        code = ErrorCode(code),
    )

    private fun sampleSyncRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("exec-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun leasedEntry(
        entryId: String = "entry-001",
        availableAt: DataLoomInstant = t0,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId(entryId),
        synchronizationRequest = sampleSyncRequest(),
        state = QueueEntryState.LEASED,
        enqueuedAt = t0,
        availableAt = availableAt,
        lease = sampleLease,
    )

    private fun entriesResult(vararg entries: QueueEntry): ProviderOperationResult<QueueAcquireResult> =
        ProviderOperationResult.Success(QueueAcquireResult.Entries(sampleLease, entries.toList()))

    private fun failureResult(error: DataLoomError = sampleError()): ProviderOperationResult<Nothing> =
        ProviderOperationResult.Failure(error)

    private val sampleScheduleReceipt = ScheduleReceipt(id = workerScheduleId)

    // =========================================================================
    // Fake implementations
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private open class FakeQueueProvider(
        private val acquireResponse: ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        private val rescheduleResponse: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val completeResponse: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val recoveryResponse: ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recoveredEntries = 0)),
    ) : QueueProvider {

        val acquireCallCount: Int get() = _acquireCallCount
        val recoveryRequests = mutableListOf<ExpiredLeaseRecoveryRequest>()

        private var _acquireCallCount = 0

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("fake-queue"),
            name = ProviderName("Fake Queue Provider"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("0.0.1"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
            _acquireCallCount++
            return acquireResponse
        }

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
            completeResponse

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            rescheduleResponse

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
            recoveryRequests.add(request)
            return recoveryResponse
        }
    }

    private class FixedOutcomeHandler(
        private val outcome: QueueEntryExecutionOutcome,
    ) : QueueEntryExecutionHandler {
        override suspend fun execute(entry: QueueEntry): QueueEntryExecutionOutcome = outcome
    }

    private open class FakeSchedulerProvider(
        private val scheduleResponse: ProviderOperationResult<ScheduleReceipt> =
            ProviderOperationResult.Success(ScheduleReceipt(id = ScheduleId("default-receipt"))),
    ) : SchedulerProvider {

        val scheduleRequests = mutableListOf<ScheduleRequest>()
        val scheduleCallCount: Int get() = scheduleRequests.size

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("fake-scheduler"),
            name = ProviderName("Fake Scheduler Provider"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("0.0.1"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> {
            scheduleRequests.add(request)
            return scheduleResponse
        }

        override suspend fun cancel(request: ScheduleCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        var readCount = 0
            private set

        override fun now(): DataLoomInstant {
            readCount++
            return instant
        }
    }

    // =========================================================================
    // Helper: build coordinator
    // =========================================================================

    private fun coordinator(
        queueProvider: FakeQueueProvider = FakeQueueProvider(),
        handler: QueueEntryExecutionHandler = FixedOutcomeHandler(
            QueueEntryExecutionOutcome.Completed(completedAt = t2),
        ),
        schedulerProvider: FakeSchedulerProvider? = null,
        clock: DataLoomClock = FixedClock(t0),
        configuration: QueueWorkerConfiguration = defaultConfiguration,
    ): QueueWorkerCoordinator = QueueWorkerCoordinator(
        queueProvider = queueProvider,
        queueProcessor = DurableQueueExecutionProcessor(queueProvider, handler),
        schedulerProvider = schedulerProvider,
        clock = clock,
        configuration = configuration,
    )

    // =========================================================================
    // Configuration tests
    // =========================================================================

    @Test
    fun `configuration preserves exact schedule ID`() {
        val id = ScheduleId("my-worker-id")
        val config = QueueWorkerConfiguration(
            scheduleId = id,
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
            continuationDelay = SchedulingDelay(5_000L),
            recoverExpiredLeasesBeforeProcessing = false,
        )
        assertEquals(id, config.scheduleId)
    }
    @Test
    fun `configuration preserves exact constraints and policy`() {
        val constraints = ScheduleConstraints()
        val policy = ExistingSchedulePolicy.REPLACE
        val config = QueueWorkerConfiguration(
            scheduleId = ScheduleId("id"),
            constraints = constraints,
            existingSchedulePolicy = policy,
            continuationDelay = SchedulingDelay(0L),
            recoverExpiredLeasesBeforeProcessing = false,
        )
        assertEquals(constraints, config.constraints)
        assertEquals(policy, config.existingSchedulePolicy)
    }

    @Test
    fun `configuration preserves continuation delay`() {
        val delay = SchedulingDelay(60_000L)
        val config = QueueWorkerConfiguration(
            scheduleId = ScheduleId("id"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
            continuationDelay = delay,
            recoverExpiredLeasesBeforeProcessing = false,
        )
        assertEquals(delay, config.continuationDelay)
    }

    @Test
    fun `configuration preserves recovery-enabled flag`() {
        val config = QueueWorkerConfiguration(
            scheduleId = ScheduleId("id"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
            continuationDelay = SchedulingDelay(0L),
            recoverExpiredLeasesBeforeProcessing = true,
        )
        assertTrue(config.recoverExpiredLeasesBeforeProcessing)
    }

    @Test
    fun `run request preserves exact processing request`() {
        val req = QueueWorkerRunRequest(
            processingRequest = sampleProcessingRequest,
            recoveryRequest = null,
        )
        assertSame(sampleProcessingRequest, req.processingRequest)
    }

    @Test
    fun `run request with recovery preserves exact recovery request`() {
        val req = QueueWorkerRunRequest(
            processingRequest = sampleProcessingRequest,
            recoveryRequest = sampleRecoveryRequest,
        )
        assertSame(sampleRecoveryRequest, req.recoveryRequest)
    }

    @Test
    fun `recovery-enabled configuration with missing recovery request throws at run`() {
        val c = coordinator(configuration = recoveryEnabledConfiguration)
        assertFailsWith<IllegalArgumentException> {
            runSuspend { c.run(noRecoveryRunRequest) }
        }
    }

    // =========================================================================
    // Recovery disabled
    // =========================================================================

    @Test
    fun `recovery disabled — QueueProvider recovery is not invoked`() {
        val provider = FakeQueueProvider()
        val c = coordinator(queueProvider = provider, configuration = defaultConfiguration)
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(0, provider.recoveryRequests.size)
    }

    @Test
    fun `recovery disabled — processing occurs exactly once`() {
        val provider = FakeQueueProvider()
        val c = coordinator(queueProvider = provider, configuration = defaultConfiguration)
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(1, provider.acquireCallCount)
    }

    @Test
    fun `recovery disabled — scheduling follows processing evidence`() {
        val provider = FakeQueueProvider()
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            configuration = defaultConfiguration,
            schedulerProvider = scheduler,
        )
        runSuspend { c.run(noRecoveryRunRequest) }
        // NoWork with no continuation evidence — no scheduling
        assertEquals(0, scheduler.scheduleCallCount)
    }

    // =========================================================================
    // Recovery enabled — success
    // =========================================================================

    @Test
    fun `recovery enabled — exact recovery request reaches QueueProvider`() {
        val provider = FakeQueueProvider()
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        runSuspend { c.run(withRecoveryRunRequest) }
        assertEquals(1, provider.recoveryRequests.size)
        assertSame(sampleRecoveryRequest, provider.recoveryRequests[0])
    }

    @Test
    fun `recovery enabled — recovery occurs exactly once`() {
        val provider = FakeQueueProvider()
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        runSuspend { c.run(withRecoveryRunRequest) }
        assertEquals(1, provider.recoveryRequests.size)
    }

    @Test
    fun `recovery enabled — recovery occurs before processing`() {
        val callOrder = mutableListOf<String>()
        val provider = object : FakeQueueProvider() {
            override suspend fun recoverExpiredLeases(
                request: ExpiredLeaseRecoveryRequest,
            ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
                callOrder.add("recovery")
                return super.recoverExpiredLeases(request)
            }

            override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
                callOrder.add("acquire")
                return super.acquire(request)
            }
        }
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        runSuspend { c.run(withRecoveryRunRequest) }
        assertEquals(listOf("recovery", "acquire"), callOrder)
    }

    @Test
    fun `recovery enabled — successful recovery result is preserved`() {
        val recoveryResult = ExpiredLeaseRecoveryResult(recoveredEntries = 3)
        val provider = FakeQueueProvider(
            recoveryResponse = ProviderOperationResult.Success(recoveryResult),
        )
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        val result = runSuspend { c.run(withRecoveryRunRequest) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        assertEquals(recoveryResult, completed.recoveryResult)
    }

    @Test
    fun `recovery enabled — zero recovered entries still permits processing`() {
        val provider = FakeQueueProvider(
            recoveryResponse = ProviderOperationResult.Success(
                ExpiredLeaseRecoveryResult(recoveredEntries = 0),
            ),
        )
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        val result = runSuspend { c.run(withRecoveryRunRequest) }
        assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        assertEquals(1, provider.acquireCallCount)
    }

    // =========================================================================
    // Recovery failure
    // =========================================================================

    @Test
    fun `recovery failure — exact DataLoomError is preserved`() {
        val error = sampleError("DL-RECOVERY-FAIL")
        val provider = FakeQueueProvider(
            recoveryResponse = failureResult(error),
        )
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        val result = runSuspend { c.run(withRecoveryRunRequest) }
        val failed = assertIs<QueueWorkerRunResult.RecoveryFailed>(result)
        assertSame(error, failed.error)
    }

    @Test
    fun `recovery failure — queue processor is not invoked`() {
        val error = sampleError("DL-RECOVERY-FAIL")
        val provider = FakeQueueProvider(
            recoveryResponse = failureResult(error),
        )
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        runSuspend { c.run(withRecoveryRunRequest) }
        assertEquals(0, provider.acquireCallCount)
    }

    @Test
    fun `recovery failure — scheduler is not invoked`() {
        val error = sampleError("DL-RECOVERY-FAIL")
        val provider = FakeQueueProvider(
            recoveryResponse = failureResult(error),
        )
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            configuration = recoveryEnabledConfiguration,
            schedulerProvider = scheduler,
        )
        runSuspend { c.run(withRecoveryRunRequest) }
        assertEquals(0, scheduler.scheduleCallCount)
    }

    // =========================================================================
    // No work
    // =========================================================================

    @Test
    fun `no work — processor returns NoWork`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        )
        val c = coordinator(queueProvider = provider)
        val result = runSuspend { c.run(noRecoveryRunRequest) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        assertIs<QueueProcessingResult.NoWork>(completed.processingResult)
    }

    @Test
    fun `no work — no scheduler call occurs`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        )
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(queueProvider = provider, schedulerProvider = scheduler)
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(0, scheduler.scheduleCallCount)
    }

    @Test
    fun `no work — scheduling result is NotRequired`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        )
        val c = coordinator(queueProvider = provider)
        val result = runSuspend { c.run(noRecoveryRunRequest) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        assertIs<QueueWorkerSchedulingResult.NotRequired>(completed.schedulingResult)
    }

    @Test
    fun `no work — clock is not read`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        )
        val clock = FixedClock(t0)
        val c = coordinator(queueProvider = provider, clock = clock)
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(0, clock.readCount)
    }

    // =========================================================================
    // Acquisition limit continuation
    // =========================================================================

    @Test
    fun `acquisition limit reached — creates continuation plan`() {
        // maxEntries = 1, provider returns exactly 1 entry → limit reached
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        // Use a request with maxEntries = 1
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `acquisition limit reached — configured continuation delay is used`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(1, scheduler.scheduleRequests.size)
        assertEquals(continuationDelay, scheduler.scheduleRequests[0].delay)
    }

    @Test
    fun `acquisition limit reached — scheduler called exactly once`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `acquisition limit reached — exact worker schedule ID is preserved`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(workerScheduleId, scheduler.scheduleRequests[0].id)
    }

    // =========================================================================
    // Rescheduled entry wake-up
    // =========================================================================

    @Test
    fun `rescheduled entry — earliest persisted timestamp is in wake-up plan`() {
        // Entry rescheduled to t3. Clock at t1. Expected delay = t3 - t1 = 2_000_000 ms.
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(t1) // now = t1
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = t3, // future instant
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        val result = runSuspend { c.run(noRecoveryRunRequest) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        val scheduled = assertIs<QueueWorkerSchedulingResult.Scheduled>(completed.schedulingResult)
        // delay = t3.epochMilliseconds - t1.epochMilliseconds = 4_000_000 - 2_000_000 = 2_000_000
        assertEquals(SchedulingDelay(2_000_000L), scheduled.plan.delay)
    }

    @Test
    fun `rescheduled entry — failed reschedule transition contributes no timestamp`() {
        // Provider makes reschedule fail → no earliestRescheduledAt → no wake-up
        val entry = leasedEntry()
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry),
            rescheduleResponse = failureResult(sampleError()),
        )
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = t3,
                    error = sampleError(),
                ),
            ),
            configuration = defaultConfiguration,
        )
        val result = runSuspend { c.run(noRecoveryRunRequest) }
        val failed = assertIs<QueueWorkerRunResult.ProcessingFailed>(result)
        assertIs<QueueProcessingResult.QueueProviderFailure>(failed.processingResult)
        assertEquals(0, scheduler.scheduleCallCount)
    }

    @Test
    fun `rescheduled entry — clock is read exactly once`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val clock = FixedClock(t1)
        val c = coordinator(
            queueProvider = provider,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = t3,
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(1, clock.readCount)
    }

    @Test
    fun `rescheduled entry — delay equals max(0, earliestAvailableAt - now)`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(DataLoomInstant(1_000L)) // now = 1_000 ms
        val availableAt = DataLoomInstant(3_000L)       // future = 3_000 ms
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = availableAt,
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        runSuspend { c.run(noRecoveryRunRequest) }
        val req = scheduler.scheduleRequests[0]
        assertEquals(SchedulingDelay(2_000L), req.delay) // 3_000 - 1_000 = 2_000
    }

    @Test
    fun `rescheduled entry — scheduler called once`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(t1)
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = t3,
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(1, scheduler.scheduleCallCount)
    }

    // =========================================================================
    // Combined continuation
    // =========================================================================

    @Test
    fun `combined — acquisition limit and rescheduled entry produces BOTH wake-up reason`() {
        // maxEntries=1, 1 entry returned → limit reached. Entry rescheduled.
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(DataLoomInstant(1_000L))
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(100_000L), // far future
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        val scheduled = assertIs<QueueWorkerSchedulingResult.Scheduled>(completed.schedulingResult)
        assertEquals(QueueWorkerWakeUpReason.BOTH, scheduled.plan.reason)
    }

    @Test
    fun `combined — earlier candidate delay is selected`() {
        // continuationDelay = 30_000 ms
        // reschedDelay = 100_000 ms → continuation is earlier
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(DataLoomInstant(0L))
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(100_000L), // delay = 100_000 ms
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration.copy(
                continuationDelay = SchedulingDelay(30_000L), // earlier
            ),
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        val scheduled = scheduler.scheduleRequests[0]
        assertEquals(SchedulingDelay(30_000L), scheduled.delay)
    }

    @Test
    fun `combined — no second schedule call occurs`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(t1)
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = t3,
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(1, scheduler.scheduleCallCount)
    }

    // =========================================================================
    // No continuation
    // =========================================================================

    @Test
    fun `partial acquisition below limit with no reschedule creates NoWakeUp`() {
        // maxEntries=5, only 1 entry returned → limit not reached; completed → no reschedule
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(0, scheduler.scheduleCallCount)
    }

    @Test
    fun `partial acquisition — clock is not read`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val clock = FixedClock(t0)
        val c = coordinator(
            queueProvider = provider,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            clock = clock,
            configuration = defaultConfiguration,
        )
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(0, clock.readCount)
    }

    // =========================================================================
    // Scheduler missing
    // =========================================================================

    @Test
    fun `scheduler missing — SchedulerNotConfigured is returned`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = null,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        assertIs<QueueWorkerSchedulingResult.SchedulerNotConfigured>(completed.schedulingResult)
    }

    @Test
    fun `scheduler missing — wake-up plan is preserved`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = null,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        val notConfigured = assertIs<QueueWorkerSchedulingResult.SchedulerNotConfigured>(
            completed.schedulingResult,
        )
        assertEquals(QueueWorkerWakeUpReason.ACQUISITION_LIMIT_REACHED, notConfigured.plan.reason)
        assertEquals(continuationDelay, notConfigured.plan.delay)
    }

    @Test
    fun `scheduler missing — durable queue-processing result remains preserved`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = null,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        assertIs<QueueProcessingResult.Processed>(completed.processingResult)
    }

    // =========================================================================
    // Scheduler success
    // =========================================================================

    @Test
    fun `scheduler success — exact ScheduleRequest values are preserved`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        val schedReq = scheduler.scheduleRequests[0]
        assertEquals(workerScheduleId, schedReq.id)
        assertEquals(defaultConstraints, schedReq.constraints)
        assertEquals(defaultPolicy, schedReq.existingPolicy)
        assertEquals(continuationDelay, schedReq.delay)
        assertNull(schedReq.synchronizationRequest)
    }

    @Test
    fun `scheduler success — scheduler executes once`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `scheduler success — exact ScheduleReceipt is preserved`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val receipt = ScheduleReceipt(id = workerScheduleId)
        val scheduler = FakeSchedulerProvider(
            scheduleResponse = ProviderOperationResult.Success(receipt),
        )
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        val scheduled = assertIs<QueueWorkerSchedulingResult.Scheduled>(completed.schedulingResult)
        assertSame(receipt, scheduled.receipt)
    }

    @Test
    fun `scheduler success — queue processor is not rerun`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(1, provider.acquireCallCount)
    }

    // =========================================================================
    // Scheduler failure
    // =========================================================================

    @Test
    fun `scheduler failure — exact scheduler DataLoomError is preserved`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val schedulerError = sampleError("DL-SCHEDULER-FAIL")
        val scheduler = FakeSchedulerProvider(
            scheduleResponse = failureResult(schedulerError),
        )
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        val schedFailed = assertIs<QueueWorkerSchedulingResult.SchedulerFailed>(
            completed.schedulingResult,
        )
        assertSame(schedulerError, schedFailed.error)
    }

    @Test
    fun `scheduler failure — queue-processing success remains preserved`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider(
            scheduleResponse = failureResult(sampleError()),
        )
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
    }

    @Test
    fun `scheduler failure — scheduling is not retried`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider(
            scheduleResponse = failureResult(sampleError()),
        )
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `scheduler failure — queue processor is not rerun`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider(
            scheduleResponse = failureResult(sampleError()),
        )
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(1, provider.acquireCallCount)
    }

    // =========================================================================
    // Processing failure
    // =========================================================================

    @Test
    fun `processing failure — QueueProviderFailure returns ProcessingFailed`() {
        val error = sampleError("DL-QUEUE-FAIL")
        val provider = FakeQueueProvider(
            acquireResponse = failureResult(error),
        )
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(queueProvider = provider, schedulerProvider = scheduler)
        val result = runSuspend { c.run(noRecoveryRunRequest) }
        assertIs<QueueWorkerRunResult.ProcessingFailed>(result)
    }

    @Test
    fun `processing failure — no scheduler call occurs`() {
        val error = sampleError("DL-QUEUE-FAIL")
        val provider = FakeQueueProvider(
            acquireResponse = failureResult(error),
        )
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(queueProvider = provider, schedulerProvider = scheduler)
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(0, scheduler.scheduleCallCount)
    }

    @Test
    fun `processing failure — exact queue-processing result is preserved`() {
        val error = sampleError("DL-QUEUE-FAIL")
        val provider = FakeQueueProvider(
            acquireResponse = failureResult(error),
        )
        val c = coordinator(queueProvider = provider)
        val result = runSuspend { c.run(noRecoveryRunRequest) }
        val failed = assertIs<QueueWorkerRunResult.ProcessingFailed>(result)
        val queueFailure = assertIs<QueueProcessingResult.QueueProviderFailure>(
            failed.processingResult,
        )
        assertSame(error, queueFailure.error)
    }

    // =========================================================================
    // Timestamp safety
    // =========================================================================

    @Test
    fun `already-due availability produces zero delay`() {
        // availableAt <= now → delay = 0
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(DataLoomInstant(5_000L)) // now = 5_000 ms
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(3_000L), // past
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(SchedulingDelay.ZERO, scheduler.scheduleRequests[0].delay)
    }

    @Test
    fun `positive future availability produces exact delay`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(DataLoomInstant(1_000L))
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(4_000L),
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(SchedulingDelay(3_000L), scheduler.scheduleRequests[0].delay)
    }

    // =========================================================================
    // Cancellation and exceptions
    // =========================================================================

    @Test
    fun `recovery cancellation propagates`() {
        val provider = object : FakeQueueProvider() {
            override suspend fun recoverExpiredLeases(
                request: ExpiredLeaseRecoveryRequest,
            ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
                throw CancellationException("cancelled during recovery")
            }
        }
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        assertFailsWith<CancellationException> {
            runSuspend { c.run(withRecoveryRunRequest) }
        }
    }

    @Test
    fun `processor cancellation propagates`() {
        val provider = object : FakeQueueProvider() {
            override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
                throw CancellationException("cancelled during acquire")
            }
        }
        val c = coordinator(queueProvider = provider)
        assertFailsWith<CancellationException> {
            runSuspend { c.run(noRecoveryRunRequest) }
        }
    }

    @Test
    fun `scheduler cancellation propagates`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = object : FakeSchedulerProvider() {
            override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> {
                throw CancellationException("cancelled during schedule")
            }
        }
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        assertFailsWith<CancellationException> {
            runSuspend { c.run(request) }
        }
    }

    @Test
    fun `cancellation does not create structured failure`() {
        val provider = object : FakeQueueProvider() {
            override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
                throw CancellationException("cancelled")
            }
        }
        val c = coordinator(queueProvider = provider)
        var caught: Throwable? = null
        try {
            runSuspend { c.run(noRecoveryRunRequest) }
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull(caught)
        assertIs<CancellationException>(caught)
    }

    // =========================================================================
    // Bounded operation count
    // =========================================================================

    @Test
    fun `recovery at most once`() {
        val provider = FakeQueueProvider()
        val c = coordinator(queueProvider = provider, configuration = recoveryEnabledConfiguration)
        runSuspend { c.run(withRecoveryRunRequest) }
        assertEquals(1, provider.recoveryRequests.size)
    }

    @Test
    fun `processing at most once`() {
        val provider = FakeQueueProvider()
        val c = coordinator(queueProvider = provider)
        runSuspend { c.run(noRecoveryRunRequest) }
        assertEquals(1, provider.acquireCallCount)
    }

    @Test
    fun `scheduling at most once`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(1, scheduler.scheduleCallCount)
    }

    // =========================================================================
    // Wake-up plan reason tests
    // =========================================================================

    @Test
    fun `wake-up plan reason is ACQUISITION_LIMIT_REACHED when only limit reached`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = defaultConfiguration,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        val result = runSuspend { c.run(request) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        val scheduled = assertIs<QueueWorkerSchedulingResult.Scheduled>(completed.schedulingResult)
        assertEquals(QueueWorkerWakeUpReason.ACQUISITION_LIMIT_REACHED, scheduled.plan.reason)
    }

    @Test
    fun `wake-up plan reason is RESCHEDULED_ENTRY_AVAILABLE when only reschedule`() {
        // maxEntries=5, 1 entry (limit not reached), rescheduled
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val clock = FixedClock(t1)
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = t3,
                    error = sampleError(),
                ),
            ),
            clock = clock,
            configuration = defaultConfiguration,
        )
        val result = runSuspend { c.run(noRecoveryRunRequest) }
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        val scheduled = assertIs<QueueWorkerSchedulingResult.Scheduled>(completed.schedulingResult)
        assertEquals(QueueWorkerWakeUpReason.RESCHEDULED_ENTRY_AVAILABLE, scheduled.plan.reason)
    }

    // =========================================================================
    // Schedule request carries correct scheduling parameters
    // =========================================================================

    @Test
    fun `schedule request carries exact constraints from configuration`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val customConstraints = ScheduleConstraints()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val config = defaultConfiguration.copy(constraints = customConstraints)
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = config,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(customConstraints, scheduler.scheduleRequests[0].constraints)
    }

    @Test
    fun `schedule request carries exact existing policy from configuration`() {
        val acquireReq = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val scheduler = FakeSchedulerProvider()
        val config = defaultConfiguration.copy(existingSchedulePolicy = ExistingSchedulePolicy.KEEP)
        val c = coordinator(
            queueProvider = provider,
            schedulerProvider = scheduler,
            handler = FixedOutcomeHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
            configuration = config,
        )
        val request = QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(acquireReq),
            recoveryRequest = null,
        )
        runSuspend { c.run(request) }
        assertEquals(ExistingSchedulePolicy.KEEP, scheduler.scheduleRequests[0].existingPolicy)
    }

    // =========================================================================
    // Security — no queue payload in diagnostics
    // =========================================================================

    @Test
    fun `ProcessingCompleted toString does not expose payload`() {
        val result = QueueWorkerRunResult.ProcessingCompleted(
            recoveryResult = null,
            processingResult = QueueProcessingResult.NoWork,
            schedulingResult = QueueWorkerSchedulingResult.NotRequired,
        )
        val str = result.toString()
        assertTrue(!str.contains("password"), "Should not contain credentials")
        assertTrue(!str.contains("secret"), "Should not contain secrets")
    }

    @Test
    fun `RecoveryFailed toString does not expose stack trace`() {
        val error = sampleError()
        val result = QueueWorkerRunResult.RecoveryFailed(error = error)
        val str = result.toString()
        assertTrue(!str.contains("StackTrace"), "Should not expose stack trace in result toString")
    }
}
