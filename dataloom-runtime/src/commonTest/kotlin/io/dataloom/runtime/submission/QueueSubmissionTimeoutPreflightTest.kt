package io.dataloom.runtime.submission

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
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
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class QueueSubmissionTimeoutPreflightTest {

    @Test
    fun `encoding rejection bypasses timeout clock and queue provider`() = runTest {
        val expected = FakeError(ErrorCode("ENCODER_REJECTED"))
        val provider = CountingQueueProvider()
        val clock = CountingClock()
        var encoderCalls = 0
        val runtime = QueueSubmissionProviderTimeoutRuntime.create(
            queueProvider = provider,
            encoder = QueuedSynchronizationWorkEncoder {
                encoderCalls++
                QueuedSynchronizationWorkEncodingResult.Rejected(expected)
            },
            clock = clock,
            queueProviderTimeout = SchedulingDelay.ZERO,
        )

        val result = assertIs<QueueSubmissionResult.EncodingRejected>(
            runtime.submit(submission()),
        )

        assertSame(expected, result.error)
        assertEquals(1, encoderCalls)
        assertEquals(0, provider.enqueueCalls)
        assertEquals(0, clock.readCount)
    }

    @Test
    fun `encoded request contract violation bypasses timeout clock and queue provider`() = runTest {
        val provider = CountingQueueProvider()
        val clock = CountingClock()
        var encoderCalls = 0
        val runtime = QueueSubmissionProviderTimeoutRuntime.create(
            queueProvider = provider,
            encoder = QueuedSynchronizationWorkEncoder { submission ->
                encoderCalls++
                QueuedSynchronizationWorkEncodingResult.Encoded(
                    QueueEnqueueRequest(
                        QueueEntry(
                            id = QueueEntryId("different-entry"),
                            synchronizationRequest = submission.work.request,
                            state = QueueEntryState.PENDING,
                            enqueuedAt = submission.availableAt,
                            availableAt = submission.availableAt,
                        ),
                    ),
                )
            },
            clock = clock,
            queueProviderTimeout = SchedulingDelay.ZERO,
        )

        val result = assertIs<QueueSubmissionResult.ContractViolation>(
            runtime.submit(submission()),
        )

        assertEquals(queueEntryId, result.queueEntryId)
        assertEquals(1, encoderCalls)
        assertEquals(0, provider.enqueueCalls)
        assertEquals(0, clock.readCount)
    }

    private class CountingQueueProvider : QueueProvider {
        var enqueueCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("preflight-queue"),
            name = ProviderName("Preflight Queue"),
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

    private class CountingClock : DataLoomClock {
        var readCount: Int = 0
            private set

        override fun now(): DataLoomInstant {
            readCount++
            return now
        }
    }

    private data class FakeError(
        override val code: ErrorCode,
        override val category: ErrorCategory = ErrorCategory.VALIDATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Preflight rejected.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private fun submission(): QueuedSynchronizationSubmission =
        QueuedSynchronizationSubmission(
            queueEntryId = queueEntryId,
            work = QueuedSynchronizationWork(
                request = SynchronizationRequest(
                    workflowId = WorkflowId("workflow-preflight"),
                    sessionId = SynchronizationSessionId("session-preflight"),
                    direction = SynchronizationDirection.PUSH,
                    mode = SynchronizationMode.DELTA,
                    context = ExecutionContext(
                        executionId = ExecutionId("execution-preflight"),
                        correlationId = CorrelationId("correlation-preflight"),
                    ),
                ),
                bindings = SynchronizationProviderBindings(
                    storageProviderId = ProviderId("storage-preflight"),
                    transportProviderId = ProviderId("transport-preflight"),
                ),
            ),
            availableAt = now,
        )

    private companion object {
        val queueEntryId = QueueEntryId("queue-entry-preflight")
        val now = DataLoomInstant(1_000L)
    }
}
