package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError

/**
 * Transport-specific circuit classification.
 *
 * Transport timeouts retain fail-closed completion semantics for replay safety
 * while the stable timeout code still contributes to dependency availability.
 */
public object TransportCircuitBreakerFailureClassifier : CircuitBreakerFailureClassifier {
    override fun classify(error: DataLoomError): CircuitBreakerFailureDisposition =
        if (error.code.value == TransportTimeoutErrors.PROVIDER_TIMEOUT_CODE) {
            CircuitBreakerFailureDisposition.RECORD_FAILURE
        } else {
            DefaultCircuitBreakerFailureClassifier.classify(error)
        }
}
