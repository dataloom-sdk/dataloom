package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability

/** How one canonical provider failure affects circuit health. */
public enum class CircuitBreakerFailureDisposition {
    /** Count the failure toward the selected circuit scope. */
    RECORD_FAILURE,

    /** Treat the dependency as responsive and record circuit success. */
    RECORD_SUCCESS,
}

/** Classifies a canonical provider failure for circuit-breaker accounting. */
public fun interface CircuitBreakerFailureClassifier {
    public fun classify(error: DataLoomError): CircuitBreakerFailureDisposition
}

/**
 * Default V1 classifier for provider-operation circuit integration.
 *
 * Only recoverable availability or infrastructure failures contribute to the
 * circuit. Authentication, authorization, validation, serialization,
 * configuration, policy, conflict, and security failures prove that the
 * dependency responded and are therefore recorded as circuit success.
 */
public object DefaultCircuitBreakerFailureClassifier : CircuitBreakerFailureClassifier {
    override public fun classify(error: DataLoomError): CircuitBreakerFailureDisposition {
        if (error.recoverability != Recoverability.RECOVERABLE) {
            return CircuitBreakerFailureDisposition.RECORD_SUCCESS
        }
        return when (error.category) {
            ErrorCategory.NETWORK,
            ErrorCategory.STORAGE,
            ErrorCategory.QUEUE,
            ErrorCategory.SCHEDULER,
            ErrorCategory.STATE,
            ErrorCategory.PROVIDER,
            ErrorCategory.PLUGIN,
            ErrorCategory.INTERNAL,
            -> CircuitBreakerFailureDisposition.RECORD_FAILURE

            ErrorCategory.AUTHENTICATION,
            ErrorCategory.AUTHORIZATION,
            ErrorCategory.SERIALIZATION,
            ErrorCategory.VALIDATION,
            ErrorCategory.CONFIGURATION,
            ErrorCategory.POLICY,
            ErrorCategory.CONFLICT,
            ErrorCategory.SECURITY,
            -> CircuitBreakerFailureDisposition.RECORD_SUCCESS
        }
    }
}
