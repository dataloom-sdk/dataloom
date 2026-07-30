package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CircuitBreakerExecutionResultTest {
    private data class TestError(
        override val code: ErrorCode = ErrorCode("CIRCUIT_EXECUTION_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized circuit execution failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    @Test
    fun `operation outcomes preserve success and canonical failure`() {
        val success = CircuitProtectedOperationResult.Success("value")
        val failure = CircuitProtectedOperationResult.Failure(TestError())

        assertEquals("value", success.value)
        assertEquals("CIRCUIT_EXECUTION_FAILURE", failure.error.code.value)
    }

    @Test
    fun `execution outcomes preserve bounded rejection and clock evidence`() {
        val rejected: CircuitBreakerExecutionResult<Nothing> =
            CircuitBreakerExecutionResult.Rejected(
                reason = CircuitBreakerRejectionReason.OPEN,
                retryAt = DataLoomInstant(5_000L),
            )
        val regression: CircuitBreakerExecutionResult<Nothing> =
            CircuitBreakerExecutionResult.ClockRegression(
                observedAt = DataLoomInstant(1_000L),
                persistedAt = DataLoomInstant(2_000L),
            )

        assertEquals(DataLoomInstant(5_000L), assertIs<CircuitBreakerExecutionResult.Rejected>(rejected).retryAt)
        assertEquals(
            DataLoomInstant(2_000L),
            assertIs<CircuitBreakerExecutionResult.ClockRegression>(regression).persistedAt,
        )
    }
}
