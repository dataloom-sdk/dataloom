package io.dataloom.runtime.retry

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult

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
 * @param retryPolicy the policy evaluated for each canonical error. Required.
 * @param schedulerProvider the optional platform scheduler. When `null`,
 *   [RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED] is returned when
 *   retry is requested.
 * @param configuration immutable scheduling configuration providing
 *   [io.dataloom.api.scheduling.ScheduleConstraints] and
 *   [io.dataloom.api.scheduling.ExistingSchedulePolicy]. Required.
 */
public class SynchronizationRetryOrchestrator(
    private val retryPolicy: RetryPolicy,
    private val schedulerProvider: SchedulerProvider?,
    private val configuration: RetrySchedulingConfiguration,
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
        val errors = extractErrors(request.synchronizationResult)
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

        val maxDelay = selectMaxDelay(decisions)
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
            is ProviderOperationResult.Success -> RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULED,
                decisions = decisions,
                selectedDelay = maxDelay,
                scheduleReceipt = result.value,
                schedulerError = null,
            )
            is ProviderOperationResult.Failure -> RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_FAILED,
                decisions = decisions,
                selectedDelay = maxDelay,
                scheduleReceipt = null,
                schedulerError = result.error,
            )
        }
    }

    /**
     * Extracts the canonical errors from a [SynchronizationResult] eligible
     * for retry evaluation, or returns `null` when the result variant is not
     * evaluable.
     *
     * Returns a non-null, non-empty list for [SynchronizationResult.Failed]
     * and [SynchronizationResult.PartiallySucceeded].
     *
     * Returns `null` for [SynchronizationResult.Succeeded],
     * [SynchronizationResult.Skipped], and [SynchronizationResult.Cancelled].
     */
    private fun extractErrors(result: SynchronizationResult): List<io.dataloom.api.error.DataLoomError>? =
        when (result) {
            is SynchronizationResult.Failed -> listOf(result.error)
            is SynchronizationResult.PartiallySucceeded -> result.errors
            is SynchronizationResult.Succeeded,
            is SynchronizationResult.Skipped,
            is SynchronizationResult.Cancelled,
            -> null
        }

    /**
     * Returns the maximum [SchedulingDelay] from any [RetryDecision.Retry]
     * decision in [decisions], or `null` when no decision requests retry.
     *
     * Stop decisions do not contribute a delay value. Decision order does not
     * affect maximum selection.
     */
    private fun selectMaxDelay(decisions: List<RetryDecision>): SchedulingDelay? =
        decisions
            .filterIsInstance<RetryDecision.Retry>()
            .maxOfOrNull { it.delay.milliseconds }
            ?.let { SchedulingDelay(it) }
}
