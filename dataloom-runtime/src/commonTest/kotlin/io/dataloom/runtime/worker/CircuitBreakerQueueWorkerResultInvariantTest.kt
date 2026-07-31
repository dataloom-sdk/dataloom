package io.dataloom.runtime.worker

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueProcessingFailureStage
import io.dataloom.runtime.queue.QueueProcessingSummary
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.QueueCircuitOperation
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CircuitBreakerQueueWorkerResultInvariantTest {

    @Test
    fun `processing unconfirmed result rejects accepted circuit recording`() {
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed(
                operation = QueueCircuitOperation.ACQUIRE,
                stage = QueueProcessingFailureStage.ACQUISITION,
                recordResult = CircuitBreakerRecordResult.Ignored,
                summary = zeroSummary(),
            )
        }
    }

    @Test
    fun `processing unconfirmed result accepts persistence failure`() {
        val result = CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed(
            operation = QueueCircuitOperation.ACQUIRE,
            stage = QueueProcessingFailureStage.ACQUISITION,
            recordResult = CircuitBreakerRecordResult.PersistenceFailure(TestError),
            summary = zeroSummary(),
        )

        assertIs<CircuitBreakerRecordResult.PersistenceFailure>(result.recordResult)
    }

    @Test
    fun `recovery unconfirmed result rejects accepted circuit recording`() {
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerQueueWorkerRecoveryResult.CircuitRecordingUnconfirmed(
                result = ExpiredLeaseRecoveryResult(0),
                recordResult = CircuitBreakerRecordResult.Ignored,
            )
        }
    }

    @Test
    fun `recovery unconfirmed result accepts persistence failure`() {
        val result = CircuitBreakerQueueWorkerRecoveryResult.CircuitRecordingUnconfirmed(
            result = ExpiredLeaseRecoveryResult(0),
            recordResult = CircuitBreakerRecordResult.PersistenceFailure(TestError),
        )

        assertIs<CircuitBreakerRecordResult.PersistenceFailure>(result.recordResult)
    }

    private fun zeroSummary(): QueueProcessingSummary = QueueProcessingSummary(
        acquired = 0,
        executed = 0,
        completed = 0,
        rescheduled = 0,
        failed = 0,
        cancelled = 0,
    )

    private data object TestError : DataLoomError {
        override val code: ErrorCode = ErrorCode("CIRCUIT_RECORDING_FAILED")
        override val category: ErrorCategory = ErrorCategory.STORAGE
        override val severity: ErrorSeverity = ErrorSeverity.ERROR
        override val recoverability: Recoverability = Recoverability.RECOVERABLE
        override val message: String = "Circuit recording failed."
        override val cause: Throwable? = null
    }
}
