package io.dataloom.runtime.retry

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

class RetryTimeoutCoordinatorTest {
    private class FixedClock(private val now: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(now)
    }

    private class RecordingExecutor : RetryTimeoutExecutor {
        var request: RetryTimeoutExecutionRequest? = null
        override suspend fun <T> execute(
            request: RetryTimeoutExecutionRequest,
            operation: suspend () -> T,
        ): RetryTimeoutExecutionResult<T> {
            this.request = request
            return RetryTimeoutExecutionResult.Completed(operation())
        }
    }

    @Test
    fun `unconfigured boundary without workflow evidence executes directly`() {
        val executor = RecordingExecutor()
        val coordinator = RetryTimeoutCoordinator(
            RetryTimeoutConfiguration(requestTimeout = SchedulingDelay(500L)),
            FixedClock(1_000L),
            executor,
        )

        val result = runSuspend { coordinator.execute(RetryTimeoutKind.PROVIDER) { "ok" } }

        assertEquals("ok", assertIs<RetryTimeoutExecutionResult.Completed<String>>(result).value)
        assertEquals(null, executor.request)
    }

    @Test
    fun `workflow deadline enforces an otherwise unconfigured boundary`() {
        val executor = RecordingExecutor()
        val coordinator = RetryTimeoutCoordinator(
            RetryTimeoutConfiguration(workflowTimeout = SchedulingDelay(2_000L)),
            FixedClock(1_500L),
            executor,
        )

        runSuspend {
            coordinator.execute(
                kind = RetryTimeoutKind.PROVIDER,
                workflowStartedAt = DataLoomInstant(1_000L),
            ) { "ok" }
        }

        assertEquals(RetryTimeoutKind.WORKFLOW, executor.request?.kind)
        assertEquals(SchedulingDelay(1_500L), executor.request?.timeout)
        assertEquals(DataLoomInstant(3_000L), executor.request?.workflowDeadline)
    }

    @Test
    fun `workflow remaining time caps boundary timeout and owns classification`() {
        val executor = RecordingExecutor()
        val coordinator = RetryTimeoutCoordinator(
            RetryTimeoutConfiguration(
                requestTimeout = SchedulingDelay(5_000L),
                workflowTimeout = SchedulingDelay(2_000L),
            ),
            FixedClock(2_500L),
            executor,
        )

        runSuspend {
            coordinator.execute(
                kind = RetryTimeoutKind.REQUEST,
                workflowStartedAt = DataLoomInstant(1_000L),
            ) { "ok" }
        }

        assertEquals(RetryTimeoutKind.WORKFLOW, executor.request?.kind)
        assertEquals(SchedulingDelay(500L), executor.request?.timeout)
        assertEquals(DataLoomInstant(3_000L), executor.request?.workflowDeadline)
    }

    @Test
    fun `shorter boundary retains its own classification`() {
        val executor = RecordingExecutor()
        val coordinator = RetryTimeoutCoordinator(
            RetryTimeoutConfiguration(
                requestTimeout = SchedulingDelay(400L),
                workflowTimeout = SchedulingDelay(2_000L),
            ),
            FixedClock(2_500L),
            executor,
        )

        runSuspend {
            coordinator.execute(
                kind = RetryTimeoutKind.REQUEST,
                workflowStartedAt = DataLoomInstant(1_000L),
            ) { "ok" }
        }

        assertEquals(RetryTimeoutKind.REQUEST, executor.request?.kind)
        assertEquals(SchedulingDelay(400L), executor.request?.timeout)
        assertEquals(DataLoomInstant(3_000L), executor.request?.workflowDeadline)
    }

    @Test
    fun `expired workflow stops before executor even when boundary is unconfigured`() {
        val executor = RecordingExecutor()
        val coordinator = RetryTimeoutCoordinator(
            RetryTimeoutConfiguration(workflowTimeout = SchedulingDelay(2_000L)),
            FixedClock(3_000L),
            executor,
        )

        val result = runSuspend {
            coordinator.execute(
                kind = RetryTimeoutKind.PROVIDER,
                workflowStartedAt = DataLoomInstant(1_000L),
            ) { "never" }
        }

        assertEquals(
            DataLoomInstant(3_000L),
            assertIs<RetryTimeoutExecutionResult.WorkflowDeadlineExceeded>(result).deadline,
        )
        assertEquals(null, executor.request)
    }

    @Test
    fun `clock regression stops fail closed even when boundary is unconfigured`() {
        val executor = RecordingExecutor()
        val coordinator = RetryTimeoutCoordinator(
            RetryTimeoutConfiguration(workflowTimeout = SchedulingDelay(1_000L)),
            FixedClock(900L),
            executor,
        )

        val result = runSuspend {
            coordinator.execute(
                kind = RetryTimeoutKind.PROVIDER,
                workflowStartedAt = DataLoomInstant(1_000L),
            ) { "never" }
        }

        assertIs<RetryTimeoutExecutionResult.ClockRegression>(result)
        assertEquals(null, executor.request)
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
