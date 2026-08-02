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

class TransportConnectionTimeoutRuntimeTest {

    @Test
    fun `completed canonical result is preserved exactly`() = runTest {
        val expected = ProviderOperationResult.Failure(
            TestError(
                code = ErrorCode("CONNECTION_REJECTED"),
                category = ErrorCategory.NETWORK,
            ),
        )

        val actual = boundary(connectionTimeoutMilliseconds = 1_000L).execute {
            expected
        }

        assertSame(expected, actual)
    }

    @Test
    fun `zero connection timeout prevents operation invocation`() = runTest {
        var calls = 0

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(connectionTimeoutMilliseconds = 0L).execute {
                calls++
                ProviderOperationResult.Success(Unit)
            },
        )

        assertEquals(0, calls)
        assertEquals("TRANSPORT_CONNECTION_TIMEOUT", failure.error.code.value)
        assertEquals(ErrorCategory.NETWORK, failure.error.category)
        assertEquals(Recoverability.RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `executing connection timeout runs cooperative cleanup`() = runTest {
        var calls = 0
        var cleanupExecuted = false

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(connectionTimeoutMilliseconds = 100L).execute {
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
        assertEquals("TRANSPORT_CONNECTION_TIMEOUT", failure.error.code.value)
        assertEquals(Recoverability.RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `caller cancellation propagates from connection boundary`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cleanupExecuted = false
        val execution = backgroundScope.async {
            boundary(connectionTimeoutMilliseconds = 20_000L).execute {
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
    fun `expired workflow deadline prevents connection invocation`() = runTest {
        var calls = 0

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(
                clock = FixedClock(DataLoomInstant(2_000L)),
                connectionTimeoutMilliseconds = 1_000L,
                workflowTimeoutMilliseconds = 500L,
            ).execute(
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
    fun `shorter workflow deadline is not relabeled as connection timeout`() = runTest {
        var calls = 0
        var cleanupExecuted = false

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(
                clock = FixedClock(DataLoomInstant(1_000L)),
                connectionTimeoutMilliseconds = 1_000L,
                workflowTimeoutMilliseconds = 100L,
            ).execute(
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
    fun `clock regression prevents connection invocation`() = runTest {
        var calls = 0

        val failure = assertIs<ProviderOperationResult.Failure>(
            boundary(
                clock = FixedClock(DataLoomInstant(500L)),
                connectionTimeoutMilliseconds = 1_000L,
                workflowTimeoutMilliseconds = 1_000L,
            ).execute(
                workflowStartedAt = DataLoomInstant(1_000L),
            ) {
                calls++
                ProviderOperationResult.Success(Unit)
            },
        )

        assertEquals(0, calls)
        assertEquals("TRANSPORT_CONNECTION_TIMEOUT_CLOCK_REGRESSION", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `production connection boundary assembly is side effect free`() {
        val clock = CountingClock()

        TransportConnectionTimeoutRuntime.create(
            clock = clock,
            connectionTimeout = SchedulingDelay(1_000L),
            workflowTimeout = SchedulingDelay(5_000L),
        )

        assertEquals(0, clock.calls)
    }

    private fun boundary(
        clock: DataLoomClock = FixedClock(DataLoomInstant(1_000L)),
        connectionTimeoutMilliseconds: Long,
        workflowTimeoutMilliseconds: Long? = null,
    ): TransportConnectionTimeoutBoundary = TransportConnectionTimeoutRuntime.create(
        clock = clock,
        connectionTimeout = SchedulingDelay(connectionTimeoutMilliseconds),
        workflowTimeout = workflowTimeoutMilliseconds?.let(::SchedulingDelay),
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
        override val message: String = "Connection test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
