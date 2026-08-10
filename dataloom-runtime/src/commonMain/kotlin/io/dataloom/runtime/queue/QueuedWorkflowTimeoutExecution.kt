package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.queue.QueueEntry
import io.dataloom.runtime.retry.RetryTimeoutExecutionResult
import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor

/** Result of applying a persisted workflow deadline before queued execution. */
internal sealed interface QueuedWorkflowTimeoutExecution<out T> {
    data class Completed<T>(val value: T) : QueuedWorkflowTimeoutExecution<T>
    data class Failed(val error: DataLoomError) : QueuedWorkflowTimeoutExecution<Nothing>
}

/**
 * Executes [operation] under [QueueEntry.workflowTimeoutState] when present.
 *
 * Entries without timeout evidence retain historical behavior. Entries with
 * timeout evidence fail closed when no executor is assembled. The operation is
 * never replayed or invoked after pre-execution expiry or clock regression.
 */
internal suspend fun <T> executeQueuedWorkflowWithTimeout(
    entry: QueueEntry,
    timeoutExecutor: WorkflowTimeoutStateExecutor?,
    operation: suspend () -> T,
): QueuedWorkflowTimeoutExecution<T> {
    val state = entry.workflowTimeoutState
        ?: return QueuedWorkflowTimeoutExecution.Completed(operation())
    val executor = timeoutExecutor
        ?: return QueuedWorkflowTimeoutExecution.Failed(
            QueuedWorkflowTimeoutErrors.executorNotConfigured(),
        )

    return when (val result = executor.execute(state, operation)) {
        is RetryTimeoutExecutionResult.Completed ->
            QueuedWorkflowTimeoutExecution.Completed(result.value)
        is RetryTimeoutExecutionResult.TimedOut ->
            QueuedWorkflowTimeoutExecution.Failed(
                QueuedWorkflowTimeoutErrors.deadlineExceeded(),
            )
        is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded ->
            QueuedWorkflowTimeoutExecution.Failed(
                QueuedWorkflowTimeoutErrors.deadlineExceeded(),
            )
        is RetryTimeoutExecutionResult.ClockRegression ->
            QueuedWorkflowTimeoutExecution.Failed(
                QueuedWorkflowTimeoutErrors.clockRegression(),
            )
    }
}

private object QueuedWorkflowTimeoutErrors {
    fun executorNotConfigured(): DataLoomError = Error(
        code = ErrorCode("QUEUED_WORKFLOW_TIMEOUT_EXECUTOR_NOT_CONFIGURED"),
        category = ErrorCategory.CONFIGURATION,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Queued workflow timeout evidence is present but enforcement is not configured.",
    )

    fun deadlineExceeded(): DataLoomError = Error(
        code = ErrorCode("QUEUED_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The accepted queued workflow deadline was reached before execution completed.",
    )

    fun clockRegression(): DataLoomError = Error(
        code = ErrorCode("QUEUED_WORKFLOW_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented deterministic queued workflow deadline enforcement.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
