package io.dataloom.runtime.retry

import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock

/**
 * Enforces one immutable absolute workflow deadline.
 *
 * The persisted [WorkflowTimeoutState] is authoritative. Runtime timeout
 * configuration is not consulted, so retry, restart, or configuration changes
 * cannot extend an already accepted workflow window.
 *
 * Construction performs no clock read and launches no coroutine. Caller
 * cancellation is governed by the injected [RetryTimeoutExecutor] and must
 * propagate unchanged.
 */
public class WorkflowTimeoutStateExecutor(
    private val clock: DataLoomClock,
    private val executor: RetryTimeoutExecutor = CoroutineRetryTimeoutExecutor(),
) {

    /** Executes [operation] within the exact remaining persisted workflow window. */
    public suspend fun <T> execute(
        state: WorkflowTimeoutState,
        operation: suspend () -> T,
    ): RetryTimeoutExecutionResult<T> {
        val observedAt = clock.now()
        if (observedAt.epochMilliseconds < state.startedAt.epochMilliseconds) {
            return RetryTimeoutExecutionResult.ClockRegression(
                observedAt = observedAt,
                deadline = state.deadline,
            )
        }
        if (observedAt.epochMilliseconds >= state.deadline.epochMilliseconds) {
            return RetryTimeoutExecutionResult.WorkflowDeadlineExceeded(state.deadline)
        }

        val remaining = SchedulingDelay(
            state.deadline.epochMilliseconds - observedAt.epochMilliseconds,
        )
        return executor.execute(
            request = RetryTimeoutExecutionRequest(
                kind = RetryTimeoutKind.WORKFLOW,
                timeout = remaining,
                workflowDeadline = state.deadline,
            ),
            operation = operation,
        )
    }
}
