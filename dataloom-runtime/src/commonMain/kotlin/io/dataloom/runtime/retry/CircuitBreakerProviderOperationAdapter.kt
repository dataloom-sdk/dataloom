package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderOperationResult

/**
 * Adapts a canonical [ProviderOperationResult] to [CircuitBreakerExecutionGate].
 *
 * Provider success is recorded as circuit success. Provider failures are
 * classified before execution evidence reaches the gate. A failure classified
 * as [CircuitBreakerFailureDisposition.RECORD_SUCCESS] is preserved for the
 * caller as [CircuitProtectedOperationResult.NonCircuitFailure] while resetting
 * or closing circuit health as appropriate.
 */
public class CircuitBreakerProviderOperationAdapter(
    private val executionGate: CircuitBreakerExecutionGate,
    private val failureClassifier: CircuitBreakerFailureClassifier =
        DefaultCircuitBreakerFailureClassifier,
) {
    /** Acquires circuit permission and invokes [operation] at most once. */
    public suspend fun <T> execute(
        scope: CircuitBreakerScope,
        operation: suspend () -> ProviderOperationResult<T>,
    ): CircuitBreakerExecutionResult<T> = executionGate.execute(scope) {
        when (val result = operation()) {
            is ProviderOperationResult.Success -> {
                CircuitProtectedOperationResult.Success(result.value)
            }
            is ProviderOperationResult.Failure -> {
                when (failureClassifier.classify(result.error)) {
                    CircuitBreakerFailureDisposition.RECORD_FAILURE -> {
                        CircuitProtectedOperationResult.Failure(result.error)
                    }
                    CircuitBreakerFailureDisposition.RECORD_SUCCESS -> {
                        CircuitProtectedOperationResult.NonCircuitFailure(result.error)
                    }
                }
            }
        }
    }
}
