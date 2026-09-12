package io.dataloom.scheduler.bgtask.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.runtime.queue.QueueProcessingFailureStage
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.queue.QueueProcessingSummary
import io.dataloom.runtime.worker.QueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every [QueueWorkerRunResult] variant maps to the single boolean
 * `BGTask.setTaskCompletedWithSuccess(_:)` accepts, exhaustively.
 */
class QueueWorkerRunOutcomeTest {

    @Test
    fun `ProcessingCompleted always succeeds`() {
        // succeeded() switches only on the outer QueueWorkerRunResult
        // variant -- never on schedulingResult's own contents (a follow-up
        // scheduler failure does not change the ProcessingCompleted variant,
        // and durable queue transitions have already committed by then and
        // must not be retried by BGTaskScheduler) -- so one representative
        // schedulingResult value is sufficient to exercise this branch.
        val completed = QueueWorkerRunResult.ProcessingCompleted(
            recoveryResult = null,
            processingResult = QueueProcessingResult.NoWork,
            schedulingResult = QueueWorkerSchedulingResult.NotRequired,
        )
        assertTrue(completed.succeeded())
    }

    @Test
    fun `RecoveryFailed never succeeds`() {
        val result = QueueWorkerRunResult.RecoveryFailed(error = fakeError())
        assertFalse(result.succeeded())
    }

    @Test
    fun `ProcessingFailed never succeeds`() {
        val result = QueueWorkerRunResult.ProcessingFailed(
            recoveryResult = null,
            processingResult = QueueProcessingResult.QueueProviderFailure(
                error = fakeError(),
                stage = QueueProcessingFailureStage.ACQUISITION,
                summary = zeroSummary(),
            ),
        )
        assertFalse(result.succeeded())
    }

    private fun fakeError(): DataLoomError = FakeError()

    private fun zeroSummary(): QueueProcessingSummary = QueueProcessingSummary(
        acquired = 0,
        executed = 0,
        completed = 0,
        rescheduled = 0,
        failed = 0,
        cancelled = 0,
        deferred = 0,
    )

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
