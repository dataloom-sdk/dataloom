package io.dataloom.runtime.worker

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.ProviderId
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
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class QueueWorkerSchedulerTimeoutTest {

    @Test
    fun `configuration defaults to historical unbounded scheduler behavior`() {
        val configuration = configuration()

        assertNull(configuration.schedulerProviderTimeout)
    }

    @Test
    fun `configuration preserves scheduler timeout through value semantics`() {
        val timeout = SchedulingDelay(500L)
        val configuration = configuration(timeout)

        assertEquals(timeout, configuration.schedulerProviderTimeout)
        assertEquals(configuration, configuration.copy())
        assertEquals(timeout, configuration.copy().schedulerProviderTimeout)
    }

    @Test
    fun `zero timeout prevents scheduling without rolling back durable completion`() = runTest {
        val queue = CompletingQueueProvider()
        val scheduler = DelayingSchedulerProvider(delayMilliseconds = 1_000L)
        val coordinator = coordinator(
            queue = queue,
            scheduler = scheduler,
            configuration = configuration(SchedulingDelay.ZERO),
        )

        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(
            coordinator.run(runRequest()),
        )
        val processing = assertIs<QueueProcessingResult.Processed>(completed.processingResult)
        val scheduling = assertIs<QueueWorkerSchedulingResult.SchedulerFailed>(
            completed.schedulingResult,
        )

        assertEquals(1, processing.completed)
        assertEquals(1, queue.completeCallCount)
        assertEquals(0, scheduler.scheduleCallCount)
        assertEquals("SCHEDULER_PROVIDER_TIMEOUT", scheduling.error.code.value)
        assertEquals(workerScheduleId, scheduling.plan.scheduleId)
    }

    @Test
    fun `bounded scheduler completes once when inside timeout`() = runTest {
        val queue = CompletingQueueProvider()
        val scheduler = DelayingSchedulerProvider(delayMilliseconds = 100L)
        val coordinator = coordinator(
            queue = queue,
            scheduler = scheduler,
            configuration = configuration(SchedulingDelay(500L)),
        )

        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(
            coordinator.run(runRequest()),
        )
        val scheduling = assertIs<QueueWorkerSchedulingResult.Scheduled>(
            completed.schedulingResult,
        )

        assertEquals(1, scheduler.scheduleCallCount)
        assertEquals(1, queue.completeCallCount)
        assertEquals(workerScheduleId, scheduling.receipt.id)
        assertEquals(continuationDelay, scheduler.lastRequest?.delay)
    }

    @Test
    fun `null timeout preserves direct scheduler invocation`() = runTest {
        val queue = CompletingQueueProvider()
        val scheduler = DelayingSchedulerProvider(delayMilliseconds = 1_000L)
        val coordinator = coordinator(
            queue = queue,
            scheduler = scheduler,
            configuration = configuration(),
        )

        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(
            coordinator.run(runRequest()),
        )

        assertIs<QueueWorkerSchedulingResult.Scheduled>(completed.schedulingResult)
        assertEquals(1, scheduler.scheduleCallCount)
        assertEquals(1, queue.completeCallCount)
    }

    @Test
    fun `caller cancellation during bounded scheduling propagates and preserves completed queue state`() = runTest {
        val queue = CompletingQueueProvider()
        val scheduler = DelayingSchedulerProvider(delayMilliseconds = 10_000L)
        val coordinator = coordinator(
            queue = queue,
            scheduler = scheduler,
            configuration = configuration(SchedulingDelay(20_000L)),
        )
        val execution = backgroundScope.async {
            coordinator.run(runRequest())
        }
        scheduler.started.await()

        execution.cancel(CancellationException("caller cancelled"))
        val failure = captureFailure { execution.await() }

        assertIs<CancellationException>(failure)
        assertEquals("caller cancelled", failure.message)
        assertEquals(1, scheduler.scheduleCallCount)
        assertEquals(1, queue.completeCallCount)
        assertTrue(scheduler.finallyExecuted)
    }

    @Test
    fun `configured timeout performs no clock read when no wake-up is required`() = runTest {
        val clock = CountingClock(now)
        val queue = CompletingQueueProvider(acquireResult = QueueAcquireResult.NoEntries)
        val scheduler = DelayingSchedulerProvider(delayMilliseconds = 1_000L)
        val coordinator = coordinator(
            queue = queue,
            scheduler = scheduler,
            configuration = configuration(SchedulingDelay(500L)),
            clock = clock,
        )

        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(
            coordinator.run(runRequest()),
        )

        assertIs<QueueWorkerSchedulingResult.NotRequired>(completed.schedulingResult)
        assertEquals(0, scheduler.scheduleCallCount)
        assertEquals(0, clock.readCount)
    }

    private fun coordinator(
        queue: CompletingQueueProvider,
        scheduler: SchedulerProvider,
        configuration: QueueWorkerConfiguration,
        clock: DataLoomClock = CountingClock(now),
    ): QueueWorkerCoordinator = QueueWorkerCoordinator(
        queueProvider = queue,
        queueProcessor = DurableQueueExecutionProcessor(
            queueProvider = queue,
            executionHandler = QueueEntryExecutionHandler {
                QueueEntryExecutionOutcome.Completed(completedAt = completedAt)
            },
        ),
        schedulerProvider = scheduler,
        clock = clock,
        configuration = configuration,
    )

    private fun configuration(
        timeout: SchedulingDelay? = null,
    ): QueueWorkerConfiguration = QueueWorkerConfiguration(
        scheduleId = workerScheduleId,
        constraints = ScheduleConstraints(),
        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        continuationDelay = continuationDelay,
        recoverExpiredLeasesBeforeProcessing = false,
        schedulerProviderTimeout = timeout,
    )

    private fun runRequest(): QueueWorkerRunRequest = QueueWorkerRunRequest(
        processingRequest = QueueProcessingRequest(
            QueueAcquireRequest(
                consumerId = consumerId,
                leaseId = leaseId,
                acquiredAt = now,
                leaseExpiresAt = leaseExpiresAt,
                maxEntries = 1,
            ),
        ),
        recoveryRequest = null,
    )

    private class CompletingQueueProvider(
        private val acquireResult: QueueAcquireResult = acquiredEntries(),
    ) : QueueProvider {
        var completeCallCount: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("queue-timeout-test"),
            name = ProviderName("Queue Timeout Test"),
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
        ): ProviderOperationResult<QueueAcquireResult> = ProviderOperationResult.Success(acquireResult)

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> {
            completeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

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
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recoveredEntries = 0))
    }

    private class DelayingSchedulerProvider(
        private val delayMilliseconds: Long,
    ) : SchedulerProvider {
        var scheduleCallCount: Int = 0
            private set
        var lastRequest: ScheduleRequest? = null
            private set
        var finallyExecuted: Boolean = false
            private set
        val started: CompletableDeferred<Unit> = CompletableDeferred()

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-timeout-test"),
            name = ProviderName("Scheduler Timeout Test"),
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
            scheduleCallCount++
            lastRequest = request
            started.complete(Unit)
            return try {
                if (delayMilliseconds > 0L) delay(delayMilliseconds)
                ProviderOperationResult.Success(ScheduleReceipt(request.id))
            } finally {
                finallyExecuted = true
            }
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private class CountingClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        var readCount: Int = 0
            private set

        override fun now(): DataLoomInstant {
            readCount++
            return instant
        }
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected block to fail.")
    } catch (failure: Throwable) {
        failure
    }

    private companion object {
        val now = DataLoomInstant(1_000L)
        val leaseExpiresAt = DataLoomInstant(2_000L)
        val completedAt = DataLoomInstant(3_000L)
        val consumerId = QueueConsumerId("consumer-1")
        val leaseId = QueueLeaseId("lease-1")
        val workerScheduleId = ScheduleId("worker-1")
        val continuationDelay = SchedulingDelay(100L)

        fun acquiredEntries(): QueueAcquireResult.Entries {
            val lease = QueueLease(
                id = leaseId,
                consumerId = consumerId,
                acquiredAt = now,
                expiresAt = leaseExpiresAt,
            )
            val request = SynchronizationRequest(
                workflowId = WorkflowId("workflow-1"),
                sessionId = SynchronizationSessionId("session-1"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("execution-1"),
                    correlationId = CorrelationId("correlation-1"),
                ),
            )
            val entry = QueueEntry(
                id = QueueEntryId("entry-1"),
                synchronizationRequest = request,
                state = QueueEntryState.LEASED,
                enqueuedAt = now,
                availableAt = now,
                lease = lease,
            )
            return QueueAcquireResult.Entries(
                lease = lease,
                entries = listOf(entry),
            )
        }
    }
}
