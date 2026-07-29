package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.runtime.execution.lifecycle.SynchronizationRuntimeEventEmitter

/**
 * Platform-independent orchestrator that applies central retry protection,
 * evaluates [RetryPolicy] for a terminal [SynchronizationResult], and schedules
 * at most one future synchronization attempt.
 *
 * A protected error stops the complete retry batch before the configured
 * policy or scheduler is invoked. This prevents a retryable sibling error in a
 * partially successful result from hiding authentication, authorization,
 * validation, configuration, serialization, policy, conflict, security,
 * non-recoverable, or unknown failures.
 *
 * The orchestrator does not execute synchronization, process queue entries,
 * check connectivity, or initialize providers. Cancellation from the scheduler
 * or event emitter propagates normally.
 */
public class SynchronizationRetryOrchestrator(
    private val retryPolicy: RetryPolicy,
    private val schedulerProvider: SchedulerProvider?,
    private val configuration: RetrySchedulingConfiguration,
    private val eventEmitter: SynchronizationRuntimeEventEmitter? = null,
) {

    /**
     * Evaluates [RetryPolicy] for [request] and schedules at most one future
     * attempt.
     */
    public suspend fun evaluateAndSchedule(
        request: SynchronizationRetryRequest,
    ): RetryOrchestrationResult {
        val errors = extractRetryErrors(request.synchronizationResult)
            ?: return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.NOT_REQUIRED,
                decisions = emptyList(),
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )

        val evaluated = evaluateRetryDecisions(
            retryPolicy = retryPolicy,
            synchronizationRequest = request.synchronizationRequest,
            retryOperation = request.retryOperation,
            retryAttempt = request.retryAttempt,
            errors = errors,
        )

        if (evaluated.blockingError != null) {
            return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.STOPPED,
                decisions = evaluated.decisions,
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )
        }

        val maxDelay = selectMaxRetryDelay(evaluated.decisions)
            ?: return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.STOPPED,
                decisions = evaluated.decisions,
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )

        if (schedulerProvider == null) {
            return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED,
                decisions = evaluated.decisions,
                selectedDelay = maxDelay,
                scheduleReceipt = null,
                schedulerError = null,
            )
        }

        val scheduleRequest = ScheduleRequest(
            id = request.scheduleId,
            synchronizationRequest = request.synchronizationRequest,
            delay = maxDelay,
            constraints = configuration.constraints,
            existingPolicy = configuration.existingSchedulePolicy,
        )

        return when (val result = schedulerProvider.schedule(scheduleRequest)) {
            is ProviderOperationResult.Success -> {
                val orchestrationResult = RetryOrchestrationResult(
                    status = RetryOrchestrationStatus.SCHEDULED,
                    decisions = evaluated.decisions,
                    selectedDelay = maxDelay,
                    scheduleReceipt = result.value,
                    schedulerError = null,
                )

                if (eventEmitter != null) {
                    val primaryError = selectPrimaryRetryError(
                        errors = errors,
                        decisions = evaluated.decisions,
                        maxDelay = maxDelay,
                    )
                    if (primaryError != null) {
                        eventEmitter.emitRetryScheduled(
                            request = request.synchronizationRequest,
                            attempt = request.retryAttempt,
                            delay = maxDelay,
                            error = primaryError,
                        )
                    }
                }

                orchestrationResult
            }

            is ProviderOperationResult.Failure -> RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_FAILED,
                decisions = evaluated.decisions,
                selectedDelay = maxDelay,
                scheduleReceipt = null,
                schedulerError = result.error,
            )
        }
    }
}

private fun selectPrimaryRetryError(
    errors: List<DataLoomError>,
    decisions: List<RetryDecision>,
    maxDelay: SchedulingDelay,
): DataLoomError? =
    errors.zip(decisions)
        .firstOrNull { (_, decision) ->
            decision is RetryDecision.Retry &&
                decision.delay.milliseconds == maxDelay.milliseconds
        }
        ?.first
        ?: errors.zip(decisions)
            .firstOrNull { (_, decision) -> decision is RetryDecision.Retry }
            ?.first
