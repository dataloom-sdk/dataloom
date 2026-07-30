package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueCircuitBreakerFailureClassifierTest {

    @Test
    fun `unknown queue provider timeout contributes to circuit health`() {
        val error = FakeError(
            code = ErrorCode("QUEUE_PROVIDER_TIMEOUT"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.UNKNOWN,
        )

        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            QueueCircuitBreakerFailureClassifier.classify(error),
        )
    }

    @Test
    fun `unrelated unknown queue failure preserves default classification`() {
        val error = FakeError(
            code = ErrorCode("QUEUE_UNKNOWN_OUTCOME"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.UNKNOWN,
        )

        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_SUCCESS,
            QueueCircuitBreakerFailureClassifier.classify(error),
        )
    }

    @Test
    fun `ordinary recoverable queue failure contributes to circuit health`() {
        val error = FakeError(
            code = ErrorCode("QUEUE_UNAVAILABLE"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.RECOVERABLE,
        )

        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            QueueCircuitBreakerFailureClassifier.classify(error),
        )
    }

    @Test
    fun `semantic validation failure records responsive dependency`() {
        val error = FakeError(
            code = ErrorCode("QUEUE_REQUEST_INVALID"),
            category = ErrorCategory.VALIDATION,
            recoverability = Recoverability.NON_RECOVERABLE,
        )

        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_SUCCESS,
            QueueCircuitBreakerFailureClassifier.classify(error),
        )
    }

    private data class FakeError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Queue circuit classifier test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
