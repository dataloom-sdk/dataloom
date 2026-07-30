package io.dataloom.runtime.retry

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
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class TimeoutEnforcingQueueProviderTest {

    @Test
    fun `fast result and descriptor are preserved exactly`() = runTest {
        val expected = ProviderOperationResult.Success<QueueAcquireResult>(QueueAcquireResult.NoEntries)
        val delegate = RecordingQueueProvider(acquireResult = expected)
        val provider = protected(delegate, timeoutMilliseconds = 1_000L)

        assertSame(delegate.descriptor, provider.descriptor)
        assertSame(expected, provider.acquire(acquireRequest))
        assertEquals(1, delegate.acquireCalls)
    }

    @Test
    fun `zero timeout prevents acquisition and reports unknown durable completion`() = runTest {
        val delegate = RecordingQueueProvider()

        val failure = assertIs<ProviderOperationResult.Failure>(
            protected(delegate, timeoutMilliseconds = 0L).acquire(acquireRequest),
        )

        assertEquals(0, delegate.acquireCalls)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(ErrorCategory.QUEUE, failure.error.category)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
        assertEquals(
            "The queue provider acquire operation exceeded its configured timeout; " +
                "durable completion is not confirmed.",
            failure.error.message,
        )
    }

    @Test
    fun `read-only health timeout remains recoverable`() = runTest {
        val delegate = RecordingQueueProvider(delayMilliseconds = 1_000L)

        val failure = assertIs<ProviderOperationResult.Failure>(
            protected(delegate, timeoutMilliseconds = 100L).health(),
        )

        assertEquals(1, delegate.healthCalls)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(Recoverability.RECOVERABLE, failure.error.recoverability)
        assertEquals(
            "The queue provider health operation exceeded its configured timeout.",
            failure.error.message,
        )
    }

    @Test
    fun `transition timeout cancels delegate cleanup and is not automatically retryable`() = runTest {
        val delegate = RecordingQueueProvider(delayMilliseconds = 1_000L)

        val failure = assertIs<ProviderOperationResult.Failure>(
            protected(delegate, timeoutMilliseconds = 100L).complete(completionRequest),
        )

        assertEquals(1, delegate.completeCalls)
        assertTrue(delegate.completeFinallyExecuted)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
    }

    @Test
    fun `canonical provider failure is preserved exactly`() = runTest {
        val expected = FakeError()
        val delegate = RecordingQueueProvider(
            acquireResult = ProviderOperationResult.Failure(expected),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(
            protected(delegate, timeoutMilliseconds = 1_000L).acquire(acquireRequest),
        )

        assertSame(expected, failure.error)
        assertEquals(1, delegate.acquireCalls)
    }

    @Test
    fun `caller cancellation propagates unchanged`() = runTest {
        val delegate = RecordingQueueProvider(delayMilliseconds = 10_000L)
        val provider = protected(delegate, timeoutMilliseconds = 20_000L)
        val execution = backgroundScope.async {
            provider.acquire(acquireRequest)
        }
        delegate.acquireStarted.await()

        execution.cancel(CancellationException("caller cancelled"))
        val failure = captureFailure { execution.await() }

        assertIs<CancellationException>(failure)
        assertEquals("caller cancelled", failure.message)
        assertEquals(1, delegate.acquireCalls)
        assertTrue(delegate.acquireFinallyExecuted)
    }

    private fun protected(
        delegate: QueueProvider,
        timeoutMilliseconds: Long,
    ): TimeoutEnforcingQueueProvider = TimeoutEnforcingQueueProvider(
        delegate = delegate,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                providerTimeout = SchedulingDelay(timeoutMilliseconds),
            ),
            clock = FixedClock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )

    private class RecordingQueueProvider(
        private val delayMilliseconds: Long = 0L,
        private val acquireResult: ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
    ) : QueueProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("queue-timeout-test"),
            name = ProviderName("Queue Timeout Test"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        var acquireCalls: Int = 0
            private set
        var healthCalls: Int = 0
            private set
        var completeCalls: Int = 0
            private set
        var acquireFinallyExecuted: Boolean = false
            private set
        var completeFinallyExecuted: Boolean = false
            private set
        val acquireStarted: CompletableDeferred<Unit> = CompletableDeferred()

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun enqueue(
            request: QueueEnqueueRequest,
        ): ProviderOperationResult<Unit> {
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> {
            acquireCalls++
            acquireStarted.complete(Unit)
            return try {
                waitIfConfigured()
                acquireResult
            } finally {
                acquireFinallyExecuted = true
            }
        }

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> {
            completeCalls++
            return try {
                waitIfConfigured()
                ProviderOperationResult.Success(Unit)
            } finally {
                completeFinallyExecuted = true
            }
        }

        override suspend fun reschedule(
            request: QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> {
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun defer(
            request: QueueDeferralRequest,
        ): ProviderOperationResult<Unit> {
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun fail(
            request: QueueFailureRequest,
        ): ProviderOperationResult<Unit> {
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun cancel(
            request: QueueCancellationRequest,
        ): ProviderOperationResult<Unit> {
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
            waitIfConfigured()
            return ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0))
        }

        private suspend fun waitIfConfigured() {
            if (delayMilliseconds > 0L) delay(delayMilliseconds)
        }
    }

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("QUEUE_TEST_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.QUEUE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Queue test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = now
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected block to fail.")
    } catch (failure: Throwable) {
        failure
    }

    private companion object {
        val now = DataLoomInstant(1_000L)
        val acquireRequest = QueueAcquireRequest(
            consumerId = QueueConsumerId("consumer-1"),
            leaseId = QueueLeaseId("lease-1"),
            acquiredAt = now,
            leaseExpiresAt = DataLoomInstant(2_000L),
            maxEntries = 1,
        )
        val completionRequest = QueueCompletionRequest(
            entryId = QueueEntryId("entry-1"),
            leaseId = QueueLeaseId("lease-1"),
            completedAt = DataLoomInstant(3_000L),
        )
        val cancellationContext = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        )
    }
}
