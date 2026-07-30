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
    fun `operation outcomes preserve success and both failure classes`() {
        val error = TestError()
        val success = CircuitProtectedOperationResult.Success("value")
        val failure = CircuitProtectedOperationResult.Failure(error)
        val nonCircuitFailure = CircuitProtectedOperationResult.NonCircuitFailure(error)

        assertEquals("value", success.value)
        assertEquals("CIRCUIT_EXECUTION_FAILURE", failure.error.code.value)
        assertEquals(error, nonCircuitFailure.error)
    }

    @Test
    fun `executed outcome preserves operation and post execution recording result`() {
        val operation = CircuitProtectedOperationResult.Failure(TestError())
        val record = CircuitBreakerRecordResult.ContentionLimitReached
        val executed: CircuitBreakerExecutionResult<Nothing> =
            CircuitBreakerExecutionResult.Executed(operation, record)

        val typed = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(executed)
        assertEquals(operation, typed.operationResult)
        assertEquals(record, typed.recordResult)
    }

    @Test
    fun `pre execution outcomes preserve rejection and persistence evidence`() {
        val error = TestError()
        val rejected: CircuitBreakerExecutionResult<Nothing> =
            CircuitBreakerExecutionResult.Rejected(
                reason = CircuitBreakerRejectionReason.OPEN,
                retryAt = DataLoomInstant(5_000L),
            )
        val persistence: CircuitBreakerExecutionResult<Nothing> =
            CircuitBreakerExecutionResult.PermissionPersistenceFailure(error)

        assertEquals(
            DataLoomInstant(5_000L),
            assertIs<CircuitBreakerExecutionResult.Rejected>(rejected).retryAt,
        )
        assertEquals(
            error,
            assertIs<CircuitBreakerExecutionResult.PermissionPersistenceFailure>(persistence).error,
        )
    }
}
