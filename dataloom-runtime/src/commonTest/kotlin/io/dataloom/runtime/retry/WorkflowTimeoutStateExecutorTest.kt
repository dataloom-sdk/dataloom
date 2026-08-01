package io.dataloom.runtime.retry

import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkflowTimeoutStateExecutorTest {

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class RecordingExecutor : RetryTimeoutExecutor {
        var request: RetryTimeoutExecutionRequest? = null
        var calls: Int = 0

        override suspend fun <T> execute(
            request: RetryTimeoutExecutionRequest,
            operation: suspend () -> T,
        ): RetryTimeoutExecutionResult<T> {
            calls++
            this.request = request
            return RetryTimeoutExecutionResult.Completed(operation())
        }
    }

    @Test
    fun `remaining absolute deadline is enforced exactly`() {
        val delegate = RecordingExecutor()
        val executor = WorkflowTimeoutStateExecutor(
            clock = FixedClock(DataLoomInstant(1_500L)),
            executor = delegate,
        )
        val state = WorkflowTimeoutState(
            startedAt = DataLoomInstant(1_000L),
            deadline = DataLoomInstant(3_000L),
        )

        val result = runSuspend { executor.execute(state) { "done" } }

        assertEquals("done", assertIs<RetryTimeoutExecutionResult.Completed<String>>(result).value)
        assertEquals(1, delegate.calls)
        assertEquals(RetryTimeoutKind.WORKFLOW, delegate.request?.kind)
        assertEquals(SchedulingDelay(1_500L), delegate.request?.timeout)
        assertEquals(DataLoomInstant(3_000L), delegate.request?.workflowDeadline)
    }

    @Test
    fun `exact deadline rejects before operation`() {
        val delegate = RecordingExecutor()
        val executor = WorkflowTimeoutStateExecutor(
            clock = FixedClock(DataLoomInstant(3_000L)),
            executor = delegate,
        )
        var invoked = false

        val result = runSuspend {
            executor.execute(
                WorkflowTimeoutState(
                    startedAt = DataLoomInstant(1_000L),
                    deadline = DataLoomInstant(3_000L),
                ),
            ) {
                invoked = true
                "never"
            }
        }

        assertEquals(
            DataLoomInstant(3_000L),
            assertIs<RetryTimeoutExecutionResult.WorkflowDeadlineExceeded>(result).deadline,
        )
        assertEquals(false, invoked)
        assertEquals(0, delegate.calls)
    }

    @Test
    fun `clock regression fails closed before operation`() {
        val delegate = RecordingExecutor()
        val executor = WorkflowTimeoutStateExecutor(
            clock = FixedClock(DataLoomInstant(999L)),
            executor = delegate,
        )
        var invoked = false

        val result = runSuspend {
            executor.execute(
                WorkflowTimeoutState(
                    startedAt = DataLoomInstant(1_000L),
                    deadline = DataLoomInstant(3_000L),
                ),
            ) {
                invoked = true
                "never"
            }
        }

        assertIs<RetryTimeoutExecutionResult.ClockRegression>(result)
        assertEquals(false, invoked)
        assertEquals(0, delegate.calls)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                completed = result
            }
        })
        return checkNotNull(completed).getOrThrow()
    }
}
