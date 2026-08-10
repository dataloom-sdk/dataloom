package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Applies the independent IDLE timeout to one explicit wait for the next
 * observable transport-progress signal.
 *
 * Protocol adapters call [awaitProgress] around each suspending wait for a
 * received or transmitted chunk, frame, acknowledgement, heartbeat, or other
 * adapter-defined progress signal. Each completed call ends one idle window; a
 * subsequent call begins a fresh window.
 *
 * This boundary must not wrap an entire request or provider lifecycle operation.
 * Those are owned by REQUEST and PROVIDER timeouts respectively.
 *
 * Completed canonical results are preserved exactly. Caller cancellation and
 * unexpected exceptions propagate. Idle timeout recoverability is UNKNOWN
 * because cancellation does not prove the final remote transfer state.
 */
public class TransportIdleTimeoutBoundary(
    private val timeoutCoordinator: RetryTimeoutCoordinator,
) {
    /**
     * Waits for the next adapter-defined observable progress signal.
     *
     * [workflowStartedAt] is optional persisted evidence for a complete workflow
     * deadline. When supplied and the remaining workflow window is shorter, the
     * workflow deadline wins without being relabeled as an idle timeout.
     */
    public suspend fun <T> awaitProgress(
        workflowStartedAt: DataLoomInstant? = null,
        operation: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = when (
        val result = timeoutCoordinator.execute(
            kind = RetryTimeoutKind.IDLE,
            workflowStartedAt = workflowStartedAt,
            operation = operation,
        )
    ) {
        is RetryTimeoutExecutionResult.Completed -> result.value
        is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
            if (result.kind == RetryTimeoutKind.WORKFLOW) {
                TransportIdleTimeoutErrors.workflowDeadlineExceeded()
            } else {
                TransportIdleTimeoutErrors.idleTimedOut()
            },
        )
        is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded -> ProviderOperationResult.Failure(
            TransportIdleTimeoutErrors.workflowDeadlineExceeded(),
        )
        is RetryTimeoutExecutionResult.ClockRegression -> ProviderOperationResult.Failure(
            TransportIdleTimeoutErrors.clockRegression(),
        )
    }
}

/** Production assembly for an explicit transport idle-progress boundary. */
public object TransportIdleTimeoutRuntime {
    /**
     * Creates one cooperative idle-progress boundary.
     *
     * Construction performs no clock read, timeout execution, I/O, identifier
     * generation, provider operation, or coroutine launch.
     */
    public fun create(
        clock: DataLoomClock,
        idleTimeout: SchedulingDelay,
        workflowTimeout: SchedulingDelay? = null,
    ): TransportIdleTimeoutBoundary = TransportIdleTimeoutBoundary(
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                idleTimeout = idleTimeout,
                workflowTimeout = workflowTimeout,
            ),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        ),
    )
}

private object TransportIdleTimeoutErrors {
    fun idleTimedOut(): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_IDLE_TIMEOUT"),
        category = ErrorCategory.NETWORK,
        recoverability = Recoverability.UNKNOWN,
        message = "No observable transport progress occurred within the configured idle timeout; remote completion is not confirmed.",
    )

    fun workflowDeadlineExceeded(): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.NETWORK,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before the next transport progress signal was observed.",
    )

    fun clockRegression(): DataLoomError = Error(
        code = ErrorCode("TRANSPORT_IDLE_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented transport idle-timeout enforcement.",
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
