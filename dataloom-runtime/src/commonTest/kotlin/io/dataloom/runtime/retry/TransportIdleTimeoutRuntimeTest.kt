package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
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

class TransportIdleTimeoutRuntimeTest {

    @Test
    fun `completed canonical progress result is preserved exactly`() = runTest {
        val expected = ProviderOperationResult.Failure(
            TestError(
                code = ErrorCode("PROGRESS_REJECTED"),
                category = ErrorCategory.NETWORK,
            ),
        )

        val actual = boundary(idleTimeoutMilliseconds = 1_000L).awaitProgress {
            expected
        }

        assertSame(expected, actual)
    }

    @Test
    fun `zero idle timeout prevents progress wait invocation`() = runTest {
        var calls = 0

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(idleTimeoutMilliseconds = 0L).awaitProgress {
                calls++
                ProviderOperationResult.Success(Unit)
            },
        )

        assertEquals(0, calls)
        assertEquals("TRANSPORT_IDLE_TIMEOUT", failure.error.code.value)
        assertEquals(ErrorCategory.NETWORK, failure.error.category)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
        assertTrue(failure.error.message.contains("remote completion is not confirmed"))
    }

    @Test
    fun `executing idle timeout runs cooperative cleanup`() = runTest {
        var calls = 0
        var cleanupExecuted = false

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(idleTimeoutMilliseconds = 100L).awaitProgress {
                calls++
                try {
                    delay(1_000L)
                    ProviderOperationResult.Success(Unit)
                } finally {
                    cleanupExecuted = true
                }
            },
        )

        assertEquals(1, calls)
        assertTrue(cleanupExecuted)
        assertEquals("TRANSPORT_IDLE_TIMEOUT", failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
    }

    @Test
    fun `each observed progress result starts a fresh idle window`() = runTest {
        val idleBoundary = boundary(idleTimeoutMilliseconds = 100L)

        val first = idleBoundary.awaitProgress {
            delay(75L)
            ProviderOperationResult.Success("first-progress")
        }
        val second = idleBoundary.awaitProgress {
            delay(75L)
            ProviderOperationResult.Success("second-progress")
        }

        assertEquals("first-progress", assertIs<ProviderOperationResult.Success<String>>(first).value)
        assertEquals("second-progress", assertIs<ProviderOperationResult.Success<String>>(second).value)
    }

    @Test
    fun `caller cancellation propagates from idle boundary`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cleanupExecuted = false
        val execution = backgroundScope.async {
            boundary(idleTimeoutMilliseconds = 20_000L).awaitProgress {
                started.complete(Unit)
                try {
                    delay(10_000L)
                    ProviderOperationResult.Success(Unit)
                } finally {
                    cleanupExecuted = true
                }
            }
        }
        started.await()

        execution.cancel(CancellationException("caller cancelled"))
        val failure = captureFailure { execution.await() }

        assertIs<CancellationException>(failure)
        assertEquals("caller cancelled", failure.message)
        assertTrue(cleanupExecuted)
    }

    @Test
    fun `expired workflow deadline prevents progress wait invocation`() = runTest {
        var calls = 0

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(
                clock = FixedClock(DataLoomInstant(2_000L)),
                idleTimeoutMilliseconds = 1_000L,
                workflowTimeoutMilliseconds = 500L,
            ).awaitProgress(
                workflowStartedAt = DataLoomInstant(1_000L),
            ) {
                calls++
                ProviderOperationResult.Success(Unit)
            },
        )

        assertEquals(0, calls)
        assertEquals("TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED", failure.error.code.value)
        assertEquals(ErrorCategory.NETWORK, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `shorter workflow deadline is not relabeled as idle timeout`() = runTest {
        var calls = 0
        var cleanupExecuted = false

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(
                clock = FixedClock(DataLoomInstant(1_000L)),
                idleTimeoutMilliseconds = 1_000L,
                workflowTimeoutMilliseconds = 100L,
            ).awaitProgress(
                workflowStartedAt = DataLoomInstant(1_000L),
            ) {
                calls++
                try {
                    delay(1_000L)
                    ProviderOperationResult.Success(Unit)
                } finally {
                    cleanupExecuted = true
                }
            },
        )

        assertEquals(1, calls)
        assertTrue(cleanupExecuted)
        assertEquals("TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED", failure.error.code.value)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `clock regression prevents progress wait invocation`() = runTest {
        var calls = 0

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(
                clock = FixedClock(DataLoomInstant(500L)),
                idleTimeoutMilliseconds = 1_000L,
                workflowTimeoutMilliseconds = 1_000L,
            ).awaitProgress(
                workflowStartedAt = DataLoomInstant(1_000L),
            ) {
                calls++
                ProviderOperationResult.Success(Unit)
            },
        )

        assertEquals(0, calls)
        assertEquals("TRANSPORT_IDLE_TIMEOUT_CLOCK_REGRESSION", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `production idle boundary assembly is side effect free`() {
        val clock = CountingClock()

        TransportIdleTimeoutRuntime.create(
            clock = clock,
            idleTimeout = SchedulingDelay(1_000L),
            workflowTimeout = SchedulingDelay(5_000L),
        )

        assertEquals(0, clock.calls)
    }

    private fun boundary(
        clock: DataLoomClock = FixedClock(DataLoomInstant(1_000L)),
        idleTimeoutMilliseconds: Long,
        workflowTimeoutMilliseconds: Long? = null,
    ): TransportIdleTimeoutBoundary = TransportIdleTimeoutRuntime.create(
        clock = clock,
        idleTimeout = SchedulingDelay(idleTimeoutMilliseconds),
        workflowTimeout = workflowTimeoutMilliseconds?.let { SchedulingDelay(it) },
    )

    private suspend fun captureFailure(block: suspend () -> Any?): Throwable = try {
        block()
        error("Expected block to fail.")
    } catch (failure: Throwable) {
        failure
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class CountingClock : DataLoomClock {
        var calls: Int = 0
            private set

        override fun now(): DataLoomInstant {
            calls++
            return DataLoomInstant(1_000L)
        }
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Progress test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
