package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Selects an explicit timeout boundary, propagates the workflow deadline, and
 * delegates cancellation-capable enforcement to [RetryTimeoutExecutor].
 */
public class RetryTimeoutCoordinator(
    private val configuration: RetryTimeoutConfiguration,
    private val clock: DataLoomClock,
    private val executor: RetryTimeoutExecutor,
) {
    public suspend fun <T> execute(
        kind: RetryTimeoutKind,
        workflowStartedAt: DataLoomInstant? = null,
        operation: suspend () -> T,
    ): RetryTimeoutExecutionResult<T> {
        val boundaryTimeout = configuration.timeoutFor(kind)
            ?: return RetryTimeoutExecutionResult.Completed(operation())

        val now = clock.now()
        val workflowTimeout = configuration.workflow
        val deadline = if (workflowStartedAt != null && workflowTimeout != null) {
            DataLoomInstant(addSaturated(workflowStartedAt.epochMilliseconds, workflowTimeout.milliseconds))
        } else {
            null
        }

        if (deadline != null && now.epochMilliseconds < workflowStartedAt!!.epochMilliseconds) {
            return RetryTimeoutExecutionResult.ClockRegression(
                observedAt = now,
                deadline = deadline,
            )
        }
        if (deadline != null && now.epochMilliseconds >= deadline.epochMilliseconds) {
            return RetryTimeoutExecutionResult.WorkflowDeadlineExceeded(deadline)
        }

        val effectiveTimeout = if (deadline == null) {
            boundaryTimeout
        } else {
            SchedulingDelay(
                minOf(
                    boundaryTimeout.milliseconds,
                    deadline.epochMilliseconds - now.epochMilliseconds,
                ),
            )
        }

        return executor.execute(
            RetryTimeoutExecutionRequest(
                kind = kind,
                timeout = effectiveTimeout,
                workflowDeadline = deadline,
            ),
            operation,
        )
    }
}

private fun RetryTimeoutConfiguration.timeoutFor(kind: RetryTimeoutKind): SchedulingDelay? = when (kind) {
    RetryTimeoutKind.CONNECTION -> connection
    RetryTimeoutKind.REQUEST -> request
    RetryTimeoutKind.IDLE -> idle
    RetryTimeoutKind.PROVIDER -> provider
    RetryTimeoutKind.POLICY -> policy
    RetryTimeoutKind.WORKFLOW -> workflow
}

private fun addSaturated(left: Long, right: Long): Long {
    if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    return left + right
}
