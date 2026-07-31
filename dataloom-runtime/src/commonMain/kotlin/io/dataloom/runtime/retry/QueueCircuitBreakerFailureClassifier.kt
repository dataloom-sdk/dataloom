package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory

/**
 * Queue-aware circuit classifier that treats the canonical queue-provider
 * timeout as an availability failure even though its durable outcome is
 * intentionally classified as unknown.
 *
 * `QUEUE_PROVIDER_TIMEOUT` uses `Recoverability.UNKNOWN` because a mutating
 * queue operation may have committed before cooperative cancellation was
 * observed. That durable ambiguity must prevent automatic replay, but it does
 * not mean the dependency was healthy. The timeout therefore contributes to
 * circuit health.
 *
 * Every other error delegates to [DefaultCircuitBreakerFailureClassifier].
 * Applications with additional provider-specific timeout codes may supply a
 * custom [CircuitBreakerFailureClassifier] to
 * [CircuitBreakerQueueOperationAdapter].
 */
public object QueueCircuitBreakerFailureClassifier : CircuitBreakerFailureClassifier {

    override fun classify(error: DataLoomError): CircuitBreakerFailureDisposition {
        if (
            error.category == ErrorCategory.QUEUE &&
            error.code.value == QUEUE_PROVIDER_TIMEOUT_CODE
        ) {
            return CircuitBreakerFailureDisposition.RECORD_FAILURE
        }
        return DefaultCircuitBreakerFailureClassifier.classify(error)
    }

    private const val QUEUE_PROVIDER_TIMEOUT_CODE: String = "QUEUE_PROVIDER_TIMEOUT"
}
