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
        val workflowTimeout = configuration.workflowTimeout
        val hasWorkflowDeadline = workflowStartedAt != null && workflowTimeout != null

        if (boundaryTimeout == null && !hasWorkflowDeadline) {
            return RetryTimeoutExecutionResult.Completed(operation())
        }

        val now = clock.now()
        val deadline = if (hasWorkflowDeadline) {
            DataLoomInstant(
                addSaturated(
                    checkNotNull(workflowStartedAt).epochMilliseconds,
                    checkNotNull(workflowTimeout).milliseconds,
                ),
            )
        } else {
            null
        }

        if (deadline != null && now.epochMilliseconds < checkNotNull(workflowStartedAt).epochMilliseconds) {
            return RetryTimeoutExecutionResult.ClockRegression(
                observedAt = now,
                deadline = deadline,
            )
        }
        if (deadline != null && now.epochMilliseconds >= deadline.epochMilliseconds) {
            return RetryTimeoutExecutionResult.WorkflowDeadlineExceeded(deadline)
        }

        val remainingWorkflow = deadline?.let {
            SchedulingDelay(it.epochMilliseconds - now.epochMilliseconds)
        }
        val effective = selectEffectiveTimeout(
            requestedKind = kind,
            boundaryTimeout = boundaryTimeout,
            remainingWorkflow = remainingWorkflow,
        ) ?: return RetryTimeoutExecutionResult.Completed(operation())

        return executor.execute(
            RetryTimeoutExecutionRequest(
                kind = effective.kind,
                timeout = effective.timeout,
                workflowDeadline = deadline,
            ),
            operation,
        )
    }
}

private data class EffectiveTimeout(
    val kind: RetryTimeoutKind,
    val timeout: SchedulingDelay,
)

private fun selectEffectiveTimeout(
    requestedKind: RetryTimeoutKind,
    boundaryTimeout: SchedulingDelay?,
    remainingWorkflow: SchedulingDelay?,
): EffectiveTimeout? = when {
    boundaryTimeout == null && remainingWorkflow == null -> null
    boundaryTimeout == null -> EffectiveTimeout(
        kind = RetryTimeoutKind.WORKFLOW,
        timeout = checkNotNull(remainingWorkflow),
    )
    remainingWorkflow == null -> EffectiveTimeout(
        kind = requestedKind,
        timeout = boundaryTimeout,
    )
    remainingWorkflow.milliseconds <= boundaryTimeout.milliseconds -> EffectiveTimeout(
        kind = RetryTimeoutKind.WORKFLOW,
        timeout = remainingWorkflow,
    )
    else -> EffectiveTimeout(
        kind = requestedKind,
        timeout = boundaryTimeout,
    )
}

private fun addSaturated(left: Long, right: Long): Long {
    if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    return left + right
}
