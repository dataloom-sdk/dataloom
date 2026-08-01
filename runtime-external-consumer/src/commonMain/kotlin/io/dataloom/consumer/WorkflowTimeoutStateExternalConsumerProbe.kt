package io.dataloom.consumer

import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.RetryTimeoutExecutionResult
import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor

/** External-consumer compilation probe for durable workflow timeout contracts. */
public object WorkflowTimeoutStateExternalConsumerProbe {

    public fun state(
        startedAt: DataLoomInstant,
        timeout: SchedulingDelay,
    ): WorkflowTimeoutState = WorkflowTimeoutState.from(startedAt, timeout)

    public suspend fun execute(
        clock: DataLoomClock,
        state: WorkflowTimeoutState,
    ): RetryTimeoutExecutionResult<String> =
        WorkflowTimeoutStateExecutor(clock).execute(state) { "completed" }
}
