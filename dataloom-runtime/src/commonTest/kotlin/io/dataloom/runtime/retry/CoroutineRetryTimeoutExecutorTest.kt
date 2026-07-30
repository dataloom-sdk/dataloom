package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class CoroutineRetryTimeoutExecutorTest {

    private val executor = CoroutineRetryTimeoutExecutor()

    @Test
    fun `completed nullable value is preserved`() = runTest {
        val result: RetryTimeoutExecutionResult<String?> = executor.execute(
            request(timeoutMilliseconds = 1_000L),
        ) {
            null
        }

        assertNull(assertIs<RetryTimeoutExecutionResult.Completed<String?>>(result).value)
    }

    @Test
    fun `zero timeout rejects before operation invocation`() = runTest {
        var invoked = false

        val result = executor.execute(
            request(timeoutMilliseconds = 0L),
        ) {
            invoked = true
            "unexpected"
        }

        val timedOut = assertIs<RetryTimeoutExecutionResult.TimedOut>(result)
        assertFalse(invoked)
        assertEquals(RetryTimeoutKind.PROVIDER, timedOut.kind)
        assertEquals(SchedulingDelay.ZERO, timedOut.timeout)
    }

    @Test
    fun `own timeout cancels operation and returns bounded result`() = runTest {
        var finallyExecuted = false

        val result = executor.execute(
            request(timeoutMilliseconds = 100L),
        ) {
            try {
                delay(1_000L)
                "late"
            } finally {
                finallyExecuted = true
            }
        }

        val timedOut = assertIs<RetryTimeoutExecutionResult.TimedOut>(result)
        assertEquals(RetryTimeoutKind.PROVIDER, timedOut.kind)
        assertEquals(SchedulingDelay(100L), timedOut.timeout)
        assertTrue(finallyExecuted)
    }

    @Test
    fun `caller cancellation propagates instead of becoming timeout`() = runTest {
        val started = CompletableDeferred<Unit>()
        val execution = backgroundScope.async {
            executor.execute(request(timeoutMilliseconds = 10_000L)) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        execution.cancel(CancellationException("caller cancelled"))
        val thrown = captureFailure { execution.await() }

        assertIs<CancellationException>(thrown)
        assertEquals("caller cancelled", thrown.message)
    }

    @Test
    fun `nested operation timeout propagates instead of being reclassified`() = runTest {
        val thrown = captureFailure {
            executor.execute(request(timeoutMilliseconds = 10_000L)) {
                withTimeout(100L) {
                    delay(1_000L)
                    "never"
                }
            }
        }

        assertIs<TimeoutCancellationException>(thrown)
    }

    private fun request(timeoutMilliseconds: Long): RetryTimeoutExecutionRequest =
        RetryTimeoutExecutionRequest(
            kind = RetryTimeoutKind.PROVIDER,
            timeout = SchedulingDelay(timeoutMilliseconds),
        )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        return try {
            block()
            error("Expected block to fail.")
        } catch (failure: Throwable) {
            failure
        }
    }
}
