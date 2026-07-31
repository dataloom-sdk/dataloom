package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError

/**
 * Transport-specific circuit classification.
 *
 * A push timeout deliberately retains [io.dataloom.api.error.Recoverability.UNKNOWN]
 * because the remote mutation may already have committed, while the stable
 * timeout code still contributes to transport availability state.
 */
public object TransportCircuitBreakerFailureClassifier : CircuitBreakerFailureClassifier {
    override fun classify(error: DataLoomError): CircuitBreakerFailureDisposition =
        if (error.code.value == TransportTimeoutErrors.PROVIDER_TIMEOUT_CODE) {
            CircuitBreakerFailureDisposition.RECORD_FAILURE
        } else {
            DefaultCircuitBreakerFailureClassifier.classify(error)
        }
}
