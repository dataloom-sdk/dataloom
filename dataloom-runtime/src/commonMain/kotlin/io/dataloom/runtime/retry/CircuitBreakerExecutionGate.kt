package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope

/**
 * Executes one already-classified operation behind a [CircuitBreakerCoordinator].
 *
 * The operation owns provider invocation and canonical failure classification.
 * This gate owns permission acquisition and success/failure recording only.
 * Exceptions, including caller cancellation, are not caught or translated.
 */
public class CircuitBreakerExecutionGate(
    private val coordinator: CircuitBreakerCoordinator,
) {
    /** Acquires circuit permission, executes at most once, and records the outcome. */
    public suspend fun <T> execute(
        scope: CircuitBreakerScope,
        operation: suspend () -> CircuitProtectedOperationResult<T>,
    ): CircuitBreakerExecutionResult<T> {
        return when (val permission = coordinator.acquire(scope)) {
            CircuitBreakerPermission.Allowed -> executeAllowed(scope, null, operation)
            is CircuitBreakerPermission.ProbeAllowed -> {
                executeAllowed(scope, permission.permit, operation)
            }
            is CircuitBreakerPermission.Rejected -> CircuitBreakerExecutionResult.Rejected(
                reason = permission.reason,
                retryAt = permission.retryAt,
            )
            is CircuitBreakerPermission.PersistenceFailure -> {
                CircuitBreakerExecutionResult.PersistenceFailure(permission.error)
            }
            CircuitBreakerPermission.ContentionLimitReached -> {
                CircuitBreakerExecutionResult.ContentionLimitReached
            }
        }
    }

    private suspend fun <T> executeAllowed(
        scope: CircuitBreakerScope,
        permit: CircuitBreakerProbePermit?,
        operation: suspend () -> CircuitProtectedOperationResult<T>,
    ): CircuitBreakerExecutionResult<T> {
        val operationResult = operation()
        val recordResult = when (operationResult) {
            is CircuitProtectedOperationResult.Success -> coordinator.recordSuccess(scope, permit)
            is CircuitProtectedOperationResult.Failure -> coordinator.recordFailure(scope, permit)
        }
        return when (recordResult) {
            is CircuitBreakerRecordResult.Recorded,
            CircuitBreakerRecordResult.Ignored,
            -> CircuitBreakerExecutionResult.Executed(operationResult)

            CircuitBreakerRecordResult.StaleProbe -> CircuitBreakerExecutionResult.StaleProbe
            is CircuitBreakerRecordResult.ClockRegression -> {
                CircuitBreakerExecutionResult.ClockRegression(
                    observedAt = recordResult.observedAt,
                    persistedAt = recordResult.persistedAt,
                )
            }
            is CircuitBreakerRecordResult.PersistenceFailure -> {
                CircuitBreakerExecutionResult.PersistenceFailure(recordResult.error)
            }
            CircuitBreakerRecordResult.ContentionLimitReached -> {
                CircuitBreakerExecutionResult.ContentionLimitReached
            }
        }
    }
}
