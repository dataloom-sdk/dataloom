package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/** Immutable request for enforcing one explicit retry timeout boundary. */
public data class RetryTimeoutExecutionRequest(
    public val kind: RetryTimeoutKind,
    public val timeout: SchedulingDelay,
    public val workflowDeadline: DataLoomInstant? = null,
)

/** Outcome returned by a timeout executor without exposing platform exceptions. */
public sealed interface RetryTimeoutExecutionResult<out T> {
    public data class Completed<T>(public val value: T) : RetryTimeoutExecutionResult<T>

    public data class TimedOut(
        public val kind: RetryTimeoutKind,
        public val timeout: SchedulingDelay,
    ) : RetryTimeoutExecutionResult<Nothing>

    public data class WorkflowDeadlineExceeded(
        public val deadline: DataLoomInstant,
    ) : RetryTimeoutExecutionResult<Nothing>

    public data class ClockRegression(
        public val observedAt: DataLoomInstant,
        public val deadline: DataLoomInstant,
    ) : RetryTimeoutExecutionResult<Nothing>
}

/**
 * Platform-neutral timeout enforcement boundary.
 *
 * Implementations must interrupt or cancel [operation] when [request.timeout]
 * expires. A caller cancellation must propagate and must not be translated into
 * [RetryTimeoutExecutionResult.TimedOut].
 */
public interface RetryTimeoutExecutor {
    public suspend fun <T> execute(
        request: RetryTimeoutExecutionRequest,
        operation: suspend () -> T,
    ): RetryTimeoutExecutionResult<T>
}
