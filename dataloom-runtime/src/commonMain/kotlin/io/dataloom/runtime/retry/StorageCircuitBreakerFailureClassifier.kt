package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError

/**
 * Storage-specific circuit classification.
 *
 * Mutating storage timeouts retain unknown completion for replay safety while
 * their stable timeout code still contributes to storage availability state.
 */
public object StorageCircuitBreakerFailureClassifier : CircuitBreakerFailureClassifier {
    override fun classify(error: DataLoomError): CircuitBreakerFailureDisposition =
        if (error.code.value == StorageTimeoutErrors.PROVIDER_TIMEOUT_CODE) {
            CircuitBreakerFailureDisposition.RECORD_FAILURE
        } else {
            DefaultCircuitBreakerFailureClassifier.classify(error)
        }
}
