package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.runtime.retry.SynchronizationRetryEvaluation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/** Maps accepted-strategy execution into one exact durable queue transition. */
internal class StrategyQueueExecutionOutcomeMapper(
    private val retryEvaluator: SynchronizationRetryEvaluator,
    private val retryOperation: RetryOperation,
) {
    fun map(
        result: StrategySynchronizationExecutionResult,
        entry: QueueEntry,
    ): QueueEntryExecutionOutcome = when (result) {
        is StrategySynchronizationExecutionResult.Executed ->
            when (val output = result.output) {
                is StrategyTransportOutput.ProviderBacked ->
                    mapSynchronizationResult(output.result, entry)
                is StrategyTransportOutput.Pushed,
                is StrategyTransportOutput.Pulled,
                is StrategyTransportOutput.Bidirectional,
                is StrategyTransportOutput.RemoteFirstBidirectional,
                -> QueueEntryExecutionOutcome.Completed(result.completedAt)
            }
        is StrategySynchronizationExecutionResult.FallbackActivated ->
            QueueEntryExecutionOutcome.Completed(result.completedAt)
        is StrategySynchronizationExecutionResult.Cancelled ->
            when (val output = result.output) {
                is StrategyTransportOutput.ProviderBacked -> {
                    val cancelled = output.result as? SynchronizationResult.Cancelled
                    QueueEntryExecutionOutcome.Cancelled(
                        cancelled?.request?.context ?: entry.synchronizationRequest.context,
                    )
                }
                else -> QueueEntryExecutionOutcome.Cancelled(
                    entry.synchronizationRequest.context,
                )
            }
        is StrategySynchronizationExecutionResult.Failed ->
            evaluateRetry(result.error, result.completedAt, entry)
        is StrategySynchronizationExecutionResult.FallbackUnavailable -> {
            val primary = result.primaryError
            if (primary == null) {
                failed(AcceptedPlanFallbackUnavailableError())
            } else {
                evaluateRetry(primary, result.completedAt, entry)
            }
        }
        is StrategySynchronizationExecutionResult.Deferred ->
            failed(AcceptedPlanUnexpectedDeferredError())
        is StrategySynchronizationExecutionResult.Rejected ->
            failed(AcceptedPlanRejectedError(result.reason.name))
    }

    private fun mapSynchronizationResult(
        result: SynchronizationResult,
        entry: QueueEntry,
    ): QueueEntryExecutionOutcome = when (result) {
        is SynchronizationResult.Succeeded,
        is SynchronizationResult.Skipped,
        -> QueueEntryExecutionOutcome.Completed(result.completedAt)
        is SynchronizationResult.Cancelled ->
            QueueEntryExecutionOutcome.Cancelled(result.request.context)
        is SynchronizationResult.Failed ->
            evaluateRetry(result.error, result.completedAt, entry)
        is SynchronizationResult.PartiallySucceeded ->
            evaluateRetry(result.errors.first(), result.completedAt, entry)
    }

    private fun evaluateRetry(
        error: DataLoomError,
        completedAt: io.dataloom.api.time.DataLoomInstant,
        entry: QueueEntry,
    ): QueueEntryExecutionOutcome {
        val currentAttempt = entry.retryAttempt?.number ?: 0
        if (currentAttempt == Int.MAX_VALUE) {
            return failed(AcceptedPlanRetryAttemptExhaustedError())
        }
        val retryAttempt = RetryAttempt(currentAttempt + 1)
        val failure = SynchronizationResult.Failed(
            request = entry.synchronizationRequest,
            completedAt = completedAt,
            summary = SynchronizationSummary(),
            error = error,
        )
        return when (
            val evaluation = retryEvaluator.evaluate(
                result = failure,
                retryAttempt = retryAttempt,
                retryOperation = retryOperation,
                retryBudgetState = entry.retryBudgetState,
            )
        ) {
            is SynchronizationRetryEvaluation.ShouldRetry ->
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = evaluation.retryAttempt,
                    availableAt = evaluation.availableAt,
                    error = evaluation.error,
                    retryBudgetState = evaluation.retryBudgetState,
                )
            is SynchronizationRetryEvaluation.StopRetry -> failed(evaluation.error)
            SynchronizationRetryEvaluation.NotRequired ->
                failed(AcceptedPlanRetryEvaluationInconsistentError())
        }
    }

    private fun failed(error: DataLoomError): QueueEntryExecutionOutcome.Failed =
        QueueEntryExecutionOutcome.Failed(
            error = error,
            disposition = QueueFailureDisposition.FAILED,
        )

    private data class AcceptedPlanFallbackUnavailableError(
        override val code: ErrorCode =
            ErrorCode("DL-Q-ACCEPTED-PLAN-FALLBACK-UNAVAILABLE"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Accepted strategy fallback did not provide local state.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AcceptedPlanUnexpectedDeferredError(
        override val code: ErrorCode = ErrorCode("DL-Q-ACCEPTED-PLAN-UNEXPECTED-DEFER"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "An accepted durable continuation unexpectedly deferred again.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AcceptedPlanRejectedError(
        private val reason: String,
        override val code: ErrorCode = ErrorCode("DL-Q-ACCEPTED-PLAN-REJECTED"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Accepted strategy plan execution was rejected: $reason.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AcceptedPlanRetryEvaluationInconsistentError(
        override val code: ErrorCode =
            ErrorCode("DL-Q-ACCEPTED-PLAN-RETRY-EVALUATION-INCONSISTENT"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Retry evaluation returned NotRequired for a known accepted-plan failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AcceptedPlanRetryAttemptExhaustedError(
        override val code: ErrorCode =
            ErrorCode("DL-Q-ACCEPTED-PLAN-RETRY-ATTEMPT-EXHAUSTED"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Accepted strategy plan cannot represent another retry attempt.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
