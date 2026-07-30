package io.dataloom.runtime.worker

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueueEntryExecutionHandler
import io.dataloom.runtime.queue.QueueEntryExecutionOutcome
import io.dataloom.runtime.queue.QueueProcessingFailureStage
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class QueueWorkerProviderTimeoutRuntimeTest {

    @Test
    fun `zero timeout prevents acquisition and returns truthful acquisition failure`() = runTest {
        val provider = RecordingQueueProvider()
        val coordinator = runtime(
            provider = provider,
            timeout = SchedulingDelay.ZERO,
            configuration = configuration(recover = false),
        )

        val result = coordinator.run(runRequest(recovery = false))
        val failed = assertIs<QueueWorkerRunResult.ProcessingFailed>(result)
        val processing = assertIs<QueueProcessingResult.QueueProviderFailure>(failed.processingResult)

        assertEquals(QueueProcessingFailureStage.ACQUISITION, processing.stage)
        assertEquals(0, processing.summary.acquired)
        assertEquals(0, processing.summary.executed)
        assertEquals(0, provider.acquireCalls)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", processing.error.code.value)
        assertEquals(ErrorCategory.QUEUE, processing.error.category)
        assertEquals(Recoverability.UNKNOWN, processing.error.recoverability)
    }

    @Test
    fun `recovery timeout stops before acquisition`() = runTest {
        val provider = RecordingQueueProvider(recoveryDelayMilliseconds = 1_000L)
        val coordinator = runtime(
            provider = provider,
            timeout = SchedulingDelay(100L),
            configuration = configuration(recover = true),
        )

        val result = coordinator.run(runRequest(recovery = true))
        val failed = assertIs<QueueWorkerRunResult.RecoveryFailed>(result)

        assertEquals(1, provider.recoveryCalls)
        assertTrue(provider.recoveryFinallyExecuted)
        assertEquals(0, provider.acquireCalls)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", failed.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failed.error.recoverability)
    }

    @Test
    fun `completion timeout preserves confirmed counters and performs no replay`() = runTest {
        val provider = RecordingQueueProvider(completeDelayMilliseconds = 1_000L)
        val coordinator = runtime(
            provider = provider,
            timeout = SchedulingDelay(100L),
            configuration = configuration(recover = false),
        )

        val result = coordinator.run(runRequest(recovery = false))
        val failed = assertIs<QueueWorkerRunResult.ProcessingFailed>(result)
        val processing = assertIs<QueueProcessingResult.QueueProviderFailure>(failed.processingResult)

        assertEquals(QueueProcessingFailureStage.COMPLETION_TRANSITION, processing.stage)
        assertEquals(1, processing.summary.acquired)
        assertEquals(1, processing.summary.executed)
        assertEquals(0, processing.summary.completed)
        assertEquals(entryId, processing.affectedEntryId)
        assertEquals(leaseId, processing.leaseId)
        assertEquals(1, provider.completeCalls)
        assertTrue(provider.completeFinallyExecuted)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", processing.error.code.value)
        assertEquals(Recoverability.UNKNOWN, processing.error.recoverability)
    }

    @Test
    fun `successful bounded queue cycle preserves completion`() = runTest {
        val provider = RecordingQueueProvider()
        val coordinator = runtime(
            provider = provider,
            timeout = SchedulingDelay(1_000L),
            configuration = configuration(recover = false),
        )

        val result = coordinator.run(runRequest(recovery = false))
        val completed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        val processing = assertIs<QueueProcessingResult.Processed>(completed.processingResult)

        assertEquals(1, processing.summary.acquired)
        assertEquals(1, processing.summary.executed)
        assertEquals(1, processing.summary.completed)
        assertEquals(1, provider.acquireCalls)
        assertEquals(1, provider.completeCalls)
        assertIs<QueueWorkerSchedulingResult.Scheduled>(completed.schedulingResult)
    }

    private fun runtime(
        provider: QueueProvider,
        timeout: SchedulingDelay,
        configuration: QueueWorkerConfiguration,
    ): QueueWorkerCoordinator = QueueWorkerProviderTimeoutRuntime.create(
        queueProvider = provider,
        executionHandler = QueueEntryExecutionHandler {
            QueueEntryExecutionOutcome.Completed(completedAt = completedAt)
        },
        schedulerProvider = ImmediateSchedulerProvider,
        clock = FixedClock,
        configuration = configuration,
        queueProviderTimeout = timeout,
    )

    private fun configuration(recover: Boolean): QueueWorkerConfiguration =
        QueueWorkerConfiguration(
            scheduleId = scheduleId,
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay.ZERO,
            recoverExpiredLeasesBeforeProcessing = recover,
        )

    private fun runRequest(recovery: Boolean): QueueWorkerRunRequest = QueueWorkerRunRequest(
        processingRequest = QueueProcessingRequest(acquireRequest),
        recoveryRequest = if (recovery) {
            ExpiredLeaseRecoveryRequest(currentTime = now)
        } else {
            null
        },
    )

    private class RecordingQueueProvider(
        private val recoveryDelayMilliseconds: Long = 0L,
        private val completeDelayMilliseconds: Long = 0L,
    ) : QueueProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("queue-worker-timeout-provider"),
            name = ProviderName("Queue Worker Timeout Provider"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        var recoveryCalls: Int = 0
            private set
        var acquireCalls: Int = 0
            private set
        var completeCalls: Int = 0
            private set
        var recoveryFinallyExecuted: Boolean = false
            private set
        var completeFinallyExecuted: Boolean = false
            private set

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
            return ProviderOperationResult.Success(acquiredEntries)
        }

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> {
            completeCalls++
            return try {
                if (completeDelayMilliseconds > 0L) delay(completeDelayMilliseconds)
                ProviderOperationResult.Success(Unit)
            } finally {
                completeFinallyExecuted = true
            }
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
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
            recoveryCalls++
            return try {
                if (recoveryDelayMilliseconds > 0L) delay(recoveryDelayMilliseconds)
                ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0))
            } finally {
                recoveryFinallyExecuted = true
            }
        }
    }

    private object ImmediateSchedulerProvider : io.dataloom.api.scheduling.SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("queue-worker-timeout-scheduler"),
            name = ProviderName("Queue Worker Timeout Scheduler"),
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
            request: io.dataloom.api.scheduling.ScheduleRequest,
        ): ProviderOperationResult<io.dataloom.api.scheduling.ScheduleReceipt> =
            ProviderOperationResult.Success(io.dataloom.api.scheduling.ScheduleReceipt(request.id))

        override suspend fun cancel(
            request: io.dataloom.api.scheduling.ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = now
    }

    private companion object {
        val now = DataLoomInstant(1_000L)
        val completedAt = DataLoomInstant(3_000L)
        val entryId = QueueEntryId("entry-1")
        val leaseId = QueueLeaseId("lease-1")
        val consumerId = QueueConsumerId("consumer-1")
        val scheduleId = ScheduleId("queue-worker-schedule")
        val lease = QueueLease(
            id = leaseId,
            consumerId = consumerId,
            acquiredAt = now,
            expiresAt = DataLoomInstant(2_000L),
        )
        val synchronizationRequest = SynchronizationRequest(
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
            id = entryId,
            synchronizationRequest = synchronizationRequest,
            state = QueueEntryState.LEASED,
            enqueuedAt = now,
            availableAt = now,
            lease = lease,
        )
        val acquiredEntries = QueueAcquireResult.Entries(
            lease = lease,
            entries = listOf(entry),
        )
        val acquireRequest = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = now,
            leaseExpiresAt = DataLoomInstant(2_000L),
            maxEntries = 1,
        )
    }
}
