package io.dataloom.runtime.retry

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.runtime.execution.lifecycle.SynchronizationRuntimeEventEmitter

/**
 * Platform-independent orchestrator that evaluates retry policy for a
 * terminal [SynchronizationResult] and schedules at most one future
 * synchronization attempt.
 *
 * ## Purpose
 *
 * [SynchronizationRetryOrchestrator] connects [RetryPolicy] evaluation and
 * [SchedulerProvider] scheduling into a single deterministic operation. It
 * does not execute synchronization, process queue entries, check connectivity,
 * dispatch events, or initialize providers.
 *
 * ## Required flow
 *
 * [evaluateAndSchedule] follows a strict, deterministic sequence:
 *
 * 1. Inspect the [SynchronizationResult] variant.
 * 2. Return [RetryOrchestrationStatus.NOT_REQUIRED] for
 *    [SynchronizationResult.Succeeded], [SynchronizationResult.Skipped], or
 *    [SynchronizationResult.Cancelled].
 * 3. Extract canonical errors from [SynchronizationResult.Failed] or
 *    [SynchronizationResult.PartiallySucceeded].
 * 4. Evaluate [RetryPolicy] for each error in the original order.
 * 5. Preserve ordered decisions.
 * 6. If no decision requests retry, return [RetryOrchestrationStatus.STOPPED].
 * 7. Determine the maximum requested [SchedulingDelay] across retry decisions.
 * 8. If [schedulerProvider] is `null`, return
 *    [RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED].
 * 9. Build one [ScheduleRequest] and call [SchedulerProvider.schedule] once.
 * 10. On [ProviderOperationResult.Success], return
 *    [RetryOrchestrationStatus.SCHEDULED] with the exact receipt.
 * 11. On [ProviderOperationResult.Failure], return
 *    [RetryOrchestrationStatus.SCHEDULER_FAILED] with the exact error.
 *
 * ## Cancelled result vs thrown CancellationException
 *
 * A [SynchronizationResult.Cancelled] result is a terminal outcome that is
 * not eligible for retry. It causes this orchestrator to return
 * [RetryOrchestrationStatus.NOT_REQUIRED].
 *
 * A thrown [kotlin.coroutines.cancellation.CancellationException] from
 * [SchedulerProvider.schedule] is different: it propagates normally and is
 * never converted into a [RetryOrchestrationResult].
 *
 * ## RetryAttempt semantics
 *
 * The [SynchronizationRetryRequest.retryAttempt] is passed unchanged to
 * [RetryPolicy.evaluate]. This orchestrator does not increment the attempt
 * number. Attempt advancement is the responsibility of the future runtime or
 * queue processor that creates the next [SynchronizationRetryRequest].
 *
 * ## Maximum-delay selection
 *
 * When multiple errors produce [RetryDecision.Retry], the maximum
 * [SchedulingDelay] across all retry decisions is chosen. Scheduling earlier
 * than one of the policy decisions would violate that policy's minimum delay.
 *
 * ## Single schedule operation
 *
 * [SchedulerProvider.schedule] is called at most once per
 * [evaluateAndSchedule] invocation. Stopped errors do not prevent other
 * retryable errors from being scheduled.
 *
 * ## Explicit ScheduleId
 *
 * The [SynchronizationRetryRequest.scheduleId] is forwarded verbatim to
 * [ScheduleRequest]. No new identifier is generated.
 *
 * ## Boundaries
 *
 * This orchestrator must not:
 * - invoke synchronization pipelines
 * - invoke [io.dataloom.runtime.execution.SynchronizationExecutionCoordinator]
 * - initialize or shut down providers
 * - resolve providers
 * - check provider health
 * - execute storage or transport operations
 * - check connectivity
 * - invoke [io.dataloom.api.provider.QueueProvider]
 * - acquire queue leases or update queue entries
 * - execute conflict handling
 * - dispatch lifecycle or progress events
 * - own a [kotlinx.coroutines.CoroutineScope]
 * - select a dispatcher or thread
 * - use global state, reflection, ServiceLoader, or a DI framework
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * ## DL-030 event emission
 *
 * When [eventEmitter] is non-null and the orchestration result is
 * [RetryOrchestrationStatus.SCHEDULED], a
 * [io.dataloom.api.synchronization.SynchronizationEvent.RetryScheduled] event
 * is emitted after the scheduler confirms acceptance. Ordinary observer
 * failures do not change the [RetryOrchestrationStatus.SCHEDULED] result.
 * A [kotlin.coroutines.cancellation.CancellationException] during event
 * delivery propagates. The schedule has already been accepted at that point;
 * cancellation does not cancel the accepted schedule.
 *
 * @param retryPolicy the policy evaluated for each canonical error. Required.
 * @param schedulerProvider the optional platform scheduler. When `null`,
 *   [RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED] is returned when
 *   retry is requested.
 * @param configuration immutable scheduling configuration providing
 *   [io.dataloom.api.scheduling.ScheduleConstraints] and
 *   [io.dataloom.api.scheduling.ExistingSchedulePolicy]. Required.
 * @param eventEmitter the optional [SynchronizationRuntimeEventEmitter] used
 *   to emit [io.dataloom.api.synchronization.SynchronizationEvent.RetryScheduled]
 *   after a successful schedule. When `null`, no event is emitted. Defaults to
 *   `null` for backward compatibility.
 */
public class SynchronizationRetryOrchestrator(
    private val retryPolicy: RetryPolicy,
    private val schedulerProvider: SchedulerProvider?,
    private val configuration: RetrySchedulingConfiguration,
    private val eventEmitter: SynchronizationRuntimeEventEmitter? = null,
) {
    /**
     * Evaluates [RetryPolicy] for the terminal result in [request] and
     * schedules at most one future synchronization attempt.
     *
     * The coroutine is not blocked. [kotlin.coroutines.cancellation.CancellationException]
     * from [SchedulerProvider.schedule] propagates normally and is never
     * converted into a [RetryOrchestrationResult].
     *
     * @param request the immutable retry orchestration request.
     * @return a [RetryOrchestrationResult] describing the terminal outcome of
     *   this orchestration cycle.
     */
    public suspend fun evaluateAndSchedule(
        request: SynchronizationRetryRequest,
    ): RetryOrchestrationResult {
        // Delegate error extraction to shared package utility.
        val errors = extractRetryErrors(request.synchronizationResult)
            ?: return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.NOT_REQUIRED,
                decisions = emptyList(),
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )

        val decisions = errors.map { error ->
            retryPolicy.evaluate(
                RetryEvaluationRequest(
                    synchronizationRequest = request.synchronizationRequest,
                    operation = request.retryOperation,
                    error = error,
                    attempt = request.retryAttempt,
                    previousDelay = null,
                    provider = null,
                ),
            )
        }

        // Delegate maximum-delay selection to shared package utility.
        val maxDelay = selectMaxRetryDelay(decisions)
            ?: return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.STOPPED,
                decisions = decisions,
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )

        if (schedulerProvider == null) {
            return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED,
                decisions = decisions,
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
                    decisions = decisions,
                    selectedDelay = maxDelay,
                    scheduleReceipt = result.value,
                    schedulerError = null,
                )
                // Emit RetryScheduled only after scheduler confirms acceptance.
                // The schedule has been accepted at this point. CancellationException
                // propagates; the accepted schedule is not automatically cancelled.
                // Ordinary observer failures do not change the SCHEDULED result.
                if (eventEmitter != null) {
                    val primaryError = selectPrimaryRetryError(errors, decisions, maxDelay)
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
                decisions = decisions,
                selectedDelay = maxDelay,
                scheduleReceipt = null,
                schedulerError = result.error,
            )
        }
    }
}

/**
 * Returns the canonical [io.dataloom.api.error.DataLoomError] that produced
 * the maximum [io.dataloom.api.scheduling.SchedulingDelay], or the first
 * retry-eligible error when none matches the maximum delay exactly.
 *
 * Used to select the representative error for
 * [io.dataloom.api.synchronization.SynchronizationEvent.RetryScheduled].
 *
 * Returns `null` only when [errors] is empty, which cannot occur for a
 * SCHEDULED result.
 */
private fun selectPrimaryRetryError(
    errors: List<io.dataloom.api.error.DataLoomError>,
    decisions: List<io.dataloom.api.retry.RetryDecision>,
    maxDelay: io.dataloom.api.scheduling.SchedulingDelay,
): io.dataloom.api.error.DataLoomError? =
    errors.zip(decisions)
        .firstOrNull { (_, decision) ->
            decision is io.dataloom.api.retry.RetryDecision.Retry &&
                decision.delay.milliseconds == maxDelay.milliseconds
        }
        ?.first
        ?: errors.zip(decisions)
            .firstOrNull { (_, decision) -> decision is io.dataloom.api.retry.RetryDecision.Retry }
            ?.first