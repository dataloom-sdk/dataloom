package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.connectivity.SynchronizationConnectivityConfiguration
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.facade.DataLoomProtectedStrategySynchronization
import io.dataloom.runtime.facade.DataLoomProtectedSynchronization
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult
import io.dataloom.runtime.retry.SynchronizationRetryEvaluation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor

/**
 * Resolves and executes one acquired queue entry through the protected
 * synchronization facade while preserving the exact admission, provider, and
 * circuit-recording evidence.
 *
 * This handler does not access a queue provider or perform a durable transition.
 * It returns one [ProviderProtectedQueueEntryExecutionResult] for a later queue
 * processor to transition exactly once.
 *
 * The resolved strategy decision is compared with durable queue state before
 * persisted workflow timeout enforcement or protected synchronization. Local
 * resolver rejection, strategy mismatch, and deadline rejection therefore
 * produce no provider evidence and invoke no protected provider operation.
 */
public class ProviderProtectedQueuedSynchronizationExecutionHandler(
    private val workResolver: QueuedSynchronizationWorkResolver,
    private val protectedSynchronization: DataLoomProtectedSynchronization,
    private val retryEvaluator: SynchronizationRetryEvaluator,
    private val retryOperation: RetryOperation,
    private val connectivityConfiguration: SynchronizationConnectivityConfiguration? = null,
    private val clock: DataLoomClock? = null,
    private val workflowTimeoutExecutor: WorkflowTimeoutStateExecutor? = null,
    private val protectedStrategySynchronization: DataLoomProtectedStrategySynchronization? = null,
) {
    private val strategyOutcomeMapper = StrategyQueueExecutionOutcomeMapper(
        retryEvaluator = retryEvaluator,
        retryOperation = retryOperation,
    )

    /**
     * Executes the exact [entry] once after local work resolution.
     *
     * Caller cancellation and unexpected resolver, facade, policy, or clock
     * exceptions propagate unchanged.
     */
    public suspend fun execute(
        entry: QueueEntry,
    ): ProviderProtectedQueueEntryExecutionResult {
        val resolution = workResolver.resolve(entry)
        if (resolution is QueuedSynchronizationWorkResolution.Rejected) {
            return localFailure(entry, resolution.error)
        }
        val work = (resolution as QueuedSynchronizationWorkResolution.Resolved).work
        QueuedStrategyDecisionCorrespondence.validate(entry, work)?.let { error ->
            return localFailure(entry, error)
        }

        val acceptedPlan = work.strategyPlan
        if (acceptedPlan != null) {
            val protectedStrategy = protectedStrategySynchronization
                ?: return localFailure(entry, AcceptedStrategyProtectionMissingError())
            val decision = work.strategyDecision
                ?: return localFailure(entry, AcceptedStrategyDecisionMissingError())
            val protectedResult = when (val timedExecution = executeQueuedWorkflowWithTimeout(
                entry = entry,
                timeoutExecutor = workflowTimeoutExecutor,
            ) {
                protectedStrategy.synchronizeAcceptedPlan(
                    request = work.request,
                    decision = decision,
                    plan = acceptedPlan,
                    bindings = work.bindings.toStrategyProviderBindings(),
                )
            }) {
                is QueuedWorkflowTimeoutExecution.Completed -> timedExecution.value
                is QueuedWorkflowTimeoutExecution.Failed -> {
                    return localFailure(entry, timedExecution.error)
                }
            }
            return ProviderProtectedQueueEntryExecutionResult(
                entryId = entry.id,
                outcome = strategyOutcomeMapper.map(
                    protectedResult.strategyResult,
                    entry,
                ),
                strategyExecutionResult = protectedResult,
            )
        }

        val protectedExecution = when (val timedExecution = executeQueuedWorkflowWithTimeout(
            entry = entry,
            timeoutExecutor = workflowTimeoutExecutor,
        ) {
            protectedSynchronization.synchronize(work.request, work.bindings)
        }) {
            is QueuedWorkflowTimeoutExecution.Completed -> timedExecution.value
            is QueuedWorkflowTimeoutExecution.Failed -> {
                return localFailure(entry, timedExecution.error)
            }
        }

        val outcome = when (protectedExecution) {
            is ProviderProtectedSynchronizationExecutionResult.Rejected ->
                mapCoordinatorRejection(protectedExecution.rejection)
            is ProviderProtectedSynchronizationExecutionResult.Executed ->
                mapSynchronizationResult(
                    result = protectedExecution.result.synchronizationResult,
                    entry = entry,
                )
        }

        return ProviderProtectedQueueEntryExecutionResult(
            entryId = entry.id,
            outcome = outcome,
            executionResult = protectedExecution,
        )
    }

    private fun localFailure(
        entry: QueueEntry,
        error: DataLoomError,
    ): ProviderProtectedQueueEntryExecutionResult =
        ProviderProtectedQueueEntryExecutionResult(
            entryId = entry.id,
            outcome = QueueEntryExecutionOutcome.Failed(
                error = error,
                disposition = QueueFailureDisposition.FAILED,
            ),
        )

    private fun mapCoordinatorRejection(
        rejected: SynchronizationExecutionResult.Rejected,
    ): QueueEntryExecutionOutcome = when (rejected.reason) {
        SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET -> {
            val config = connectivityConfiguration
            val offlineClock = clock
            if (config != null && offlineClock != null) {
                offlineDeferral(config, offlineClock)
            } else {
                failed(structuralRejectionError(rejected))
            }
        }
        SynchronizationExecutionRejectionReason.CONNECTIVITY_PROVIDER_NOT_CONFIGURED ->
            failed(connectivityProviderMissingError())
        SynchronizationExecutionRejectionReason.CONNECTIVITY_CHECK_FAILED ->
            failed(rejected.connectivityCheckError ?: structuralRejectionError(rejected))
        else -> failed(structuralRejectionError(rejected))
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

        is SynchronizationResult.Failed,
        is SynchronizationResult.PartiallySucceeded,
        -> evaluateRetry(result, entry)
    }

    private fun evaluateRetry(
        result: SynchronizationResult,
        entry: QueueEntry,
    ): QueueEntryExecutionOutcome {
        val currentAttempt = entry.retryAttempt?.number ?: 0
        if (currentAttempt == Int.MAX_VALUE) {
            return failed(RetryAttemptExhaustedError())
        }
        val nextAttempt = RetryAttempt(currentAttempt + 1)

        return when (val evaluation = retryEvaluator.evaluate(
            result = result,
            retryAttempt = nextAttempt,
            retryOperation = retryOperation,
            retryBudgetState = entry.retryBudgetState,
        )) {
            is SynchronizationRetryEvaluation.ShouldRetry ->
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = evaluation.retryAttempt,
                    availableAt = evaluation.availableAt,
                    error = evaluation.error,
                    retryBudgetState = evaluation.retryBudgetState,
                )
            is SynchronizationRetryEvaluation.StopRetry -> failed(evaluation.error)
            SynchronizationRetryEvaluation.NotRequired ->
                QueueEntryExecutionOutcome.Completed(result.completedAt)
        }
    }

    private fun offlineDeferral(
        config: SynchronizationConnectivityConfiguration,
        offlineClock: DataLoomClock,
    ): QueueEntryExecutionOutcome.Deferred {
        val observedAt = offlineClock.now().epochMilliseconds
        val delay = config.offlineRescheduleDelay.milliseconds
        val availableAt = if (observedAt > Long.MAX_VALUE - delay) {
            Long.MAX_VALUE
        } else {
            observedAt + delay
        }
        return QueueEntryExecutionOutcome.Deferred(
            availableAt = DataLoomInstant(availableAt),
            reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
        )
    }

    private fun failed(error: DataLoomError): QueueEntryExecutionOutcome.Failed =
        QueueEntryExecutionOutcome.Failed(
            error = error,
            disposition = QueueFailureDisposition.FAILED,
        )

    private fun structuralRejectionError(
        rejected: SynchronizationExecutionResult.Rejected,
    ): DataLoomError = StructuralRejectionError(rejected.reason)

    private fun connectivityProviderMissingError(): DataLoomError =
        ConnectivityProviderMissingError()

    private data class AcceptedStrategyProtectionMissingError(
        override val code: ErrorCode =
            ErrorCode("DL-PROTECTED-QUEUE-ACCEPTED-PLAN-NOT-CONFIGURED"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Protected queued accepted-plan execution is not configured.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AcceptedStrategyDecisionMissingError(
        override val code: ErrorCode =
            ErrorCode("DL-PROTECTED-QUEUE-ACCEPTED-PLAN-DECISION-MISSING"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Protected queued accepted-plan execution requires a durable decision.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class StructuralRejectionError(
        private val reason: SynchronizationExecutionRejectionReason,
        override val code: ErrorCode = ErrorCode("DL-PROTECTED-QUEUE-EXECUTION-REJECTED"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Protected synchronization was rejected before pipeline invocation: $reason",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class ConnectivityProviderMissingError(
        override val code: ErrorCode =
            ErrorCode("DL-PROTECTED-QUEUE-CONNECTIVITY-PROVIDER-NOT-CONFIGURED"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Protected queued synchronization requires a configured connectivity provider.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class RetryAttemptExhaustedError(
        override val code: ErrorCode = ErrorCode("DL-PROTECTED-QUEUE-RETRY-ATTEMPT-EXHAUSTED"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Protected queued synchronization cannot represent another retry attempt.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
