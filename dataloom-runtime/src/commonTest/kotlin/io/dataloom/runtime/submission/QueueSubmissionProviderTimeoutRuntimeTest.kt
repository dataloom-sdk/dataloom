package io.dataloom.runtime.submission

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.identifier.QueueEntryId
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
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.runtime.queue.QueuedSynchronizationWork
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

class QueueSubmissionProviderTimeoutRuntimeTest {

    @Test
    fun `zero timeout encodes once and rejects before enqueue invocation`() = runTest {
        val provider = RecordingQueueProvider(delayMilliseconds = 1_000L)
        val encoder = RecordingEncoder()
        val submission = submission()
        val runtime = runtime(provider, encoder, SchedulingDelay.ZERO)

        val result = assertIs<QueueSubmissionResult.QueueProviderFailure>(
            runtime.submit(submission),
        )

        assertEquals(1, encoder.callCount)
        assertSame(submission, encoder.lastSubmission)
        assertEquals(0, provider.enqueueCallCount)
        assertEquals(queueEntryId, result.queueEntryId)
        assertEquals(QueueSubmissionFailureStage.QUEUE_PROVIDER_ENQUEUE, result.failureStage)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", result.error.code.value)
        assertEquals(ErrorCategory.QUEUE, result.error.category)
        assertEquals(Recoverability.UNKNOWN, result.error.recoverability)
    }

    @Test
    fun `successful enqueue inside timeout preserves exact provider success`() = runTest {
        val success = ProviderOperationResult.Success(Unit)
        val provider = RecordingQueueProvider(
            delayMilliseconds = 100L,
            enqueueResult = success,
        )
        val encoder = RecordingEncoder()
        val runtime = runtime(provider, encoder, SchedulingDelay(500L))

        val result = assertIs<QueueSubmissionResult.Enqueued>(
            runtime.submit(submission()),
        )

        assertEquals(1, encoder.callCount)
        assertEquals(1, provider.enqueueCallCount)
        assertSame(success, result.providerResult)
        assertEquals(queueEntryId, result.queueEntryId)
        assertEquals(queueEntryId, provider.lastRequest?.entry?.id)
    }

    @Test
    fun `provider timeout cancels cooperative enqueue and reports durable ambiguity`() = runTest {
        val provider = RecordingQueueProvider(delayMilliseconds = 1_000L)
        val runtime = runtime(provider, RecordingEncoder(), SchedulingDelay(100L))

        val result = assertIs<QueueSubmissionResult.QueueProviderFailure>(
            runtime.submit(submission()),
        )

        assertEquals(1, provider.enqueueCallCount)
        assertTrue(provider.enqueueFinallyExecuted)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", result.error.code.value)
        assertEquals(Recoverability.UNKNOWN, result.error.recoverability)
        assertEquals(queueEntryId, result.queueEntryId)
        assertNull(result.error.cause)
    }

    @Test
    fun `canonical enqueue failure is preserved exactly`() = runTest {
        val expected = FakeError(code = ErrorCode("QUEUE_EXPECTED_FAILURE"))
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(expected),
        )
        val runtime = runtime(provider, RecordingEncoder(), SchedulingDelay(500L))

        val result = assertIs<QueueSubmissionResult.QueueProviderFailure>(
            runtime.submit(submission()),
        )

        assertEquals(1, provider.enqueueCallCount)
        assertSame(expected, result.error)
        assertEquals(queueEntryId, result.queueEntryId)
    }

    @Test
    fun `caller cancellation propagates and is not converted to timeout failure`() = runTest {
        val provider = RecordingQueueProvider(delayMilliseconds = 10_000L)
        val runtime = runtime(provider, RecordingEncoder(), SchedulingDelay(20_000L))
        val execution = backgroundScope.async {
            runtime.submit(submission())
        }
        provider.enqueueStarted.await()

        execution.cancel(CancellationException("caller cancelled"))
        val failure = captureFailure { execution.await() }

        assertIs<CancellationException>(failure)
        assertEquals("caller cancelled", failure.message)
        assertEquals(1, provider.enqueueCallCount)
        assertTrue(provider.enqueueFinallyExecuted)
    }

    @Test
    fun `construction performs no clock encoder or provider operation`() {
        val clock = CountingClock(now)
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder()

        QueueSubmissionProviderTimeoutRuntime.create(
            queueProvider = provider,
            encoder = encoder,
            clock = clock,
            queueProviderTimeout = SchedulingDelay(500L),
        )

        assertEquals(0, clock.readCount)
        assertEquals(0, encoder.callCount)
        assertEquals(0, provider.enqueueCallCount)
    }

    private fun runtime(
        provider: QueueProvider,
        encoder: QueuedSynchronizationWorkEncoder,
        timeout: SchedulingDelay,
    ): DataLoomQueueSubmission = QueueSubmissionProviderTimeoutRuntime.create(
        queueProvider = provider,
        encoder = encoder,
        clock = CountingClock(now),
        queueProviderTimeout = timeout,
    )

    private class RecordingEncoder : QueuedSynchronizationWorkEncoder {
        var callCount: Int = 0
            private set
        var lastSubmission: QueuedSynchronizationSubmission? = null
            private set

        override fun encode(
            submission: QueuedSynchronizationSubmission,
        ): QueuedSynchronizationWorkEncodingResult {
            callCount++
            lastSubmission = submission
            return QueuedSynchronizationWorkEncodingResult.Encoded(
                QueueEnqueueRequest(
                    QueueEntry(
                        id = submission.queueEntryId,
                        synchronizationRequest = submission.work.request,
                        state = QueueEntryState.PENDING,
                        enqueuedAt = submission.availableAt,
                        availableAt = submission.availableAt,
                    ),
                ),
            )
        }
    }

    private class RecordingQueueProvider(
        private val delayMilliseconds: Long = 0L,
        private val enqueueResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    ) : QueueProvider {
        var enqueueCallCount: Int = 0
            private set
        var lastRequest: QueueEnqueueRequest? = null
            private set
        var enqueueFinallyExecuted: Boolean = false
            private set
        val enqueueStarted: CompletableDeferred<Unit> = CompletableDeferred()

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("queue-submission-timeout-provider"),
            name = ProviderName("Queue Submission Timeout Provider"),
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
            enqueueCallCount++
            lastRequest = request
            enqueueStarted.complete(Unit)
            return try {
                if (delayMilliseconds > 0L) delay(delayMilliseconds)
                enqueueResult
            } finally {
                enqueueFinallyExecuted = true
            }
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

    private data class FakeError(
        override val code: ErrorCode,
        override val category: ErrorCategory = ErrorCategory.QUEUE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Queue submission timeout test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected block to fail.")
    } catch (failure: Throwable) {
        failure
    }

    private fun submission(): QueuedSynchronizationSubmission =
        QueuedSynchronizationSubmission(
            queueEntryId = queueEntryId,
            work = QueuedSynchronizationWork(
                request = SynchronizationRequest(
                    workflowId = WorkflowId("workflow-1"),
                    sessionId = SynchronizationSessionId("session-1"),
                    direction = SynchronizationDirection.PUSH,
                    mode = SynchronizationMode.DELTA,
                    context = ExecutionContext(
                        executionId = ExecutionId("execution-1"),
                        correlationId = CorrelationId("correlation-1"),
                    ),
                ),
                bindings = SynchronizationProviderBindings(
                    storageProviderId = ProviderId("storage-1"),
                    transportProviderId = ProviderId("transport-1"),
                ),
            ),
            availableAt = now,
        )

    private companion object {
        val queueEntryId = QueueEntryId("queue-entry-1")
        val now = DataLoomInstant(1_000L)
    }
}
