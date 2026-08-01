package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.RetryTimeoutExecutionRequest
import io.dataloom.runtime.retry.RetryTimeoutExecutionResult
import io.dataloom.runtime.retry.RetryTimeoutExecutor
import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QueuedWorkflowTimeoutExecutionTest {

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class CompletingExecutor : RetryTimeoutExecutor {
        var calls: Int = 0
        override suspend fun <T> execute(
            request: RetryTimeoutExecutionRequest,
            operation: suspend () -> T,
        ): RetryTimeoutExecutionResult<T> {
            calls++
            return RetryTimeoutExecutionResult.Completed(operation())
        }
    }

    @Test
    fun `entry without timeout evidence preserves historical direct execution`() {
        var calls = 0
        val result = runSuspend {
            executeQueuedWorkflowWithTimeout(
                entry = entry(workflowTimeoutState = null),
                timeoutExecutor = null,
            ) {
                calls++
                "done"
            }
        }

        assertEquals("done", assertIs<QueuedWorkflowTimeoutExecution.Completed<String>>(result).value)
        assertEquals(1, calls)
    }

    @Test
    fun `timeout evidence without executor fails closed before operation`() {
        var calls = 0
        val result = runSuspend {
            executeQueuedWorkflowWithTimeout(
                entry = entry(state()),
                timeoutExecutor = null,
            ) {
                calls++
                "never"
            }
        }

        val failure = assertIs<QueuedWorkflowTimeoutExecution.Failed>(result)
        assertEquals("QUEUED_WORKFLOW_TIMEOUT_EXECUTOR_NOT_CONFIGURED", failure.error.code.value)
        assertEquals(0, calls)
    }

    @Test
    fun `active persisted deadline executes exactly once`() {
        val delegate = CompletingExecutor()
        val timeoutExecutor = WorkflowTimeoutStateExecutor(
            clock = FixedClock(DataLoomInstant(1_500L)),
            executor = delegate,
        )
        var calls = 0

        val result = runSuspend {
            executeQueuedWorkflowWithTimeout(
                entry = entry(state()),
                timeoutExecutor = timeoutExecutor,
            ) {
                calls++
                "done"
            }
        }

        assertEquals("done", assertIs<QueuedWorkflowTimeoutExecution.Completed<String>>(result).value)
        assertEquals(1, calls)
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `exact persisted deadline prevents operation`() {
        val timeoutExecutor = WorkflowTimeoutStateExecutor(
            clock = FixedClock(DataLoomInstant(3_000L)),
            executor = CompletingExecutor(),
        )
        var calls = 0

        val result = runSuspend {
            executeQueuedWorkflowWithTimeout(
                entry = entry(state()),
                timeoutExecutor = timeoutExecutor,
            ) {
                calls++
                "never"
            }
        }

        val failure = assertIs<QueuedWorkflowTimeoutExecution.Failed>(result)
        assertEquals("QUEUED_WORKFLOW_DEADLINE_EXCEEDED", failure.error.code.value)
        assertEquals(0, calls)
    }

    @Test
    fun `clock regression prevents operation`() {
        val timeoutExecutor = WorkflowTimeoutStateExecutor(
            clock = FixedClock(DataLoomInstant(999L)),
            executor = CompletingExecutor(),
        )
        var calls = 0

        val result = runSuspend {
            executeQueuedWorkflowWithTimeout(
                entry = entry(state()),
                timeoutExecutor = timeoutExecutor,
            ) {
                calls++
                "never"
            }
        }

        val failure = assertIs<QueuedWorkflowTimeoutExecution.Failed>(result)
        assertEquals("QUEUED_WORKFLOW_TIMEOUT_CLOCK_REGRESSION", failure.error.code.value)
        assertEquals(0, calls)
    }

    private fun state(): WorkflowTimeoutState = WorkflowTimeoutState(
        startedAt = DataLoomInstant(1_000L),
        deadline = DataLoomInstant(3_000L),
    )

    private fun entry(workflowTimeoutState: WorkflowTimeoutState?): QueueEntry = QueueEntry(
        id = QueueEntryId("workflow-timeout-entry"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        ),
        state = QueueEntryState.LEASED,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        lease = io.dataloom.api.queue.QueueLease(
            id = io.dataloom.api.identifier.QueueLeaseId("lease-1"),
            consumerId = io.dataloom.api.identifier.QueueConsumerId("consumer-1"),
            acquiredAt = DataLoomInstant(1_100L),
            expiresAt = DataLoomInstant(4_000L),
        ),
        workflowTimeoutState = workflowTimeoutState,
    )

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
