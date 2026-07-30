package io.dataloom.runtime.retry

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.ProviderId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
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

class TimeoutEnforcingSchedulerProviderTest {

    @Test
    fun `fast lifecycle and scheduling results are preserved`() = runTest {
        val delegate = RecordingSchedulerProvider()
        val provider = protected(delegate, timeoutMilliseconds = 1_000L)

        assertSame(delegate.descriptor, provider.descriptor)
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.initialize(ProviderInitializationContext()),
        )
        assertIs<ProviderOperationResult.Success<ProviderHealth>>(provider.health())
        assertIs<ProviderOperationResult.Success<ScheduleReceipt>>(
            provider.schedule(scheduleRequest),
        )
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.cancel(cancellationRequest),
        )
        assertIs<ProviderOperationResult.Success<Unit>>(provider.close())

        assertEquals(1, delegate.initializeCalls)
        assertEquals(1, delegate.healthCalls)
        assertEquals(1, delegate.scheduleCalls)
        assertEquals(1, delegate.cancelCalls)
        assertEquals(1, delegate.closeCalls)
    }

    @Test
    fun `canonical delegate failure is preserved exactly`() = runTest {
        val expected = FakeError()
        val delegate = RecordingSchedulerProvider(
            scheduleResult = ProviderOperationResult.Failure(expected),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(
            protected(delegate, timeoutMilliseconds = 1_000L).schedule(scheduleRequest),
        )

        assertSame(expected, failure.error)
    }

    @Test
    fun `zero provider timeout rejects before delegate invocation`() = runTest {
        val delegate = RecordingSchedulerProvider()

        val failure = assertIs<ProviderOperationResult.Failure>(
            protected(delegate, timeoutMilliseconds = 0L).schedule(scheduleRequest),
        )

        assertEquals(0, delegate.scheduleCalls)
        assertEquals("SCHEDULER_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(ErrorCategory.SCHEDULER, failure.error.category)
        assertEquals(Recoverability.RECOVERABLE, failure.error.recoverability)
        assertEquals(
            "The scheduler provider schedule operation exceeded its configured timeout.",
            failure.error.message,
        )
    }

    @Test
    fun `provider timeout cancels the delegate operation`() = runTest {
        val delegate = RecordingSchedulerProvider(delayMilliseconds = 1_000L)

        val failure = assertIs<ProviderOperationResult.Failure>(
            protected(delegate, timeoutMilliseconds = 100L).schedule(scheduleRequest),
        )

        assertEquals("SCHEDULER_PROVIDER_TIMEOUT", failure.error.code.value)
        assertTrue(delegate.scheduleFinallyExecuted)
    }

    @Test
    fun `caller cancellation propagates unchanged`() = runTest {
        val delegate = RecordingSchedulerProvider(delayMilliseconds = 10_000L)
        val provider = protected(delegate, timeoutMilliseconds = 20_000L)
        val execution = backgroundScope.async {
            provider.schedule(scheduleRequest)
        }
        delegate.scheduleStarted.await()

        execution.cancel(CancellationException("caller cancelled"))
        val thrown = captureFailure { execution.await() }

        assertIs<CancellationException>(thrown)
        assertEquals("caller cancelled", thrown.message)
        assertTrue(delegate.scheduleFinallyExecuted)
    }

    @Test
    fun `provider timeout also protects lifecycle operations`() = runTest {
        val delegate = RecordingSchedulerProvider(delayMilliseconds = 1_000L)

        val failure = assertIs<ProviderOperationResult.Failure>(
            protected(delegate, timeoutMilliseconds = 100L)
                .initialize(ProviderInitializationContext()),
        )

        assertEquals("SCHEDULER_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(
            "The scheduler provider initialize operation exceeded its configured timeout.",
            failure.error.message,
        )
    }

    private fun protected(
        delegate: SchedulerProvider,
        timeoutMilliseconds: Long,
    ): TimeoutEnforcingSchedulerProvider = TimeoutEnforcingSchedulerProvider(
        delegate = delegate,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                providerTimeout = SchedulingDelay(timeoutMilliseconds),
            ),
            clock = FixedClock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )

    private class RecordingSchedulerProvider(
        private val delayMilliseconds: Long = 0L,
        private val scheduleResult: ProviderOperationResult<ScheduleReceipt>? = null,
    ) : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("timeout-test-scheduler"),
            name = ProviderName("Timeout Test Scheduler"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )

        var initializeCalls: Int = 0
        var healthCalls: Int = 0
        var scheduleCalls: Int = 0
        var cancelCalls: Int = 0
        var closeCalls: Int = 0
        var scheduleFinallyExecuted: Boolean = false
        val scheduleStarted: CompletableDeferred<Unit> = CompletableDeferred()

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(
                ProviderHealth(status = ProviderHealthStatus.HEALTHY),
            )
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun schedule(
            request: ScheduleRequest,
        ): ProviderOperationResult<ScheduleReceipt> {
            scheduleCalls++
            scheduleStarted.complete(Unit)
            return try {
                waitIfConfigured()
                scheduleResult ?: ProviderOperationResult.Success(ScheduleReceipt(request.id))
            } finally {
                scheduleFinallyExecuted = true
            }
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> {
            cancelCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        private suspend fun waitIfConfigured() {
            if (delayMilliseconds > 0L) {
                delay(delayMilliseconds)
            }
        }
    }

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("SCHEDULER_FAKE_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.SCHEDULER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "A fake scheduler failure occurred.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(1_000L)
    }

    private companion object {
        val scheduleRequest = ScheduleRequest(id = ScheduleId("schedule-1"))
        val cancellationRequest = ScheduleCancellationRequest(
            id = ScheduleId("schedule-1"),
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        )
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        return try {
            block()
            error("Expected block to fail.")
        } catch (failure: Throwable) {
            failure
        }
    }
}
