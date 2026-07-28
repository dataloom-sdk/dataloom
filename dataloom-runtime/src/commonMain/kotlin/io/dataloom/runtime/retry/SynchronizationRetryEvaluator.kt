package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Platform-independent evaluator that inspects a terminal
 * [SynchronizationResult], evaluates [RetryPolicy] for each canonical error,
 * selects the maximum delay, and computes an overflow-safe availability
 * instant using an injected [DataLoomClock].
 *
 * ## Purpose
 *
 * [SynchronizationRetryEvaluator] provides the retry-decision and
 * availability-time computation needed by
 * [io.dataloom.runtime.queue.QueuedSynchronizationExecutionHandler] to produce
 * a [io.dataloom.runtime.queue.QueueEntryExecutionOutcome]. It does not invoke
 * [io.dataloom.api.scheduling.SchedulerProvider] and does not interact with
 * the durable queue.
 *
 * The shared error-extraction and maximum-delay-selection logic in this class
 * is also reused by [SynchronizationRetryOrchestrator].
 *
 * ## Required flow
 *
 * [evaluate] follows a strict, deterministic sequence:
 *
 * 1. Inspect the [SynchronizationResult] variant.
 * 2. Return [SynchronizationRetryEvaluation.NotRequired] for
 *    [SynchronizationResult.Succeeded], [SynchronizationResult.Skipped], or
 *    [SynchronizationResult.Cancelled].
 * 3. Extract canonical errors from [SynchronizationResult.Failed] or
 *    [SynchronizationResult.PartiallySucceeded].
 * 4. Evaluate [RetryPolicy] for each error in the original order, passing
 *    [retryAttempt] unchanged to every [RetryEvaluationRequest].
 * 5. Preserve ordered decisions.
 * 6. If no decision requests retry, return
 *    [SynchronizationRetryEvaluation.StopRetry] with the primary error and
 *    the ordered decisions.
 * 7. Determine the maximum [io.dataloom.api.scheduling.SchedulingDelay] across all retry decisions.
 * 8. Read the current instant from [clock].
 * 9. Compute [SynchronizationRetryEvaluation.ShouldRetry.availableAt] using
 *    overflow-safe addition of the epoch milliseconds and the delay.
 * 10. Return [SynchronizationRetryEvaluation.ShouldRetry] with the exact
 *    [retryAttempt], the computed [availableAt], the primary error, the
 *    ordered decisions, and the selected delay.
 *
 * ## RetryAttempt semantics
 *
 * The [retryAttempt] supplied to [evaluate] is passed unchanged to every
 * [RetryEvaluationRequest]. This evaluator does not modify or increment the
 * attempt number. The caller is responsible for computing the correct attempt
 * value to supply.
 *
 * The same [retryAttempt] is stored in
 * [SynchronizationRetryEvaluation.ShouldRetry.retryAttempt] and is intended
 * to be written to the rescheduled [io.dataloom.api.queue.QueueEntry] via
 * [io.dataloom.api.queue.QueueRescheduleRequest.retryAttempt].
 *
 * ## Primary error selection
 *
 * When [SynchronizationResult.Failed], the single error is used. When
 * [SynchronizationResult.PartiallySucceeded], the first error in the list
 * is used as the primary error stored on the rescheduled entry.
 *
 * ## Maximum-delay selection
 *
 * When multiple errors produce [RetryDecision.Retry], the maximum
 * [SchedulingDelay] across all retry decisions is chosen. Stop decisions do
 * not contribute a delay value. A single retry decision among multiple stop
 * decisions is sufficient to request retry.
 *
 * ## Overflow-safe timestamp arithmetic
 *
 * [SynchronizationRetryEvaluation.ShouldRetry.availableAt] is computed as
 * `clock.now().epochMilliseconds + selectedDelay.milliseconds`. If the sum
 * overflows [Long.MAX_VALUE], [Long.MAX_VALUE] is used instead. The resulting
 * [DataLoomInstant] always satisfies the non-negative epoch-milliseconds
 * invariant.
 *
 * ## Cancellation
 *
 * [evaluate] is a non-suspending function. Coroutine cancellation is not
 * applicable.
 *
 * ## Exception boundary
 *
 * Unexpected exceptions from [RetryPolicy.evaluate] propagate to the caller.
 * This evaluator does not catch arbitrary programming errors.
 *
 * ## Boundaries
 *
 * This evaluator must not:
 * - invoke synchronization pipelines
 * - invoke [SynchronizationExecutionCoordinator][io.dataloom.runtime.execution.SynchronizationExecutionCoordinator]
 * - invoke [io.dataloom.api.scheduling.SchedulerProvider]
 * - invoke [io.dataloom.api.queue.QueueProvider]
 * - acquire queue leases or update queue entries
 * - initialize or shut down providers
 * - own a [kotlinx.coroutines.CoroutineScope] or select a dispatcher
 * - use global state, reflection, ServiceLoader, or a DI framework
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param retryPolicy the policy evaluated for each canonical error. Required.
 * @param clock the [DataLoomClock] used to read the current instant when
 *   computing the retry availability timestamp. Required. Injected for
 *   deterministic testing.
 */
public class SynchronizationRetryEvaluator(
    private val retryPolicy: RetryPolicy,
    private val clock: DataLoomClock,
) {

    /**
     * Evaluates retry policy for the terminal [SynchronizationResult] and
     * returns a [SynchronizationRetryEvaluation] describing the outcome.
     *
     * The [retryAttempt] is passed unchanged to every
     * [RetryEvaluationRequest]. The same value is stored in
     * [SynchronizationRetryEvaluation.ShouldRetry.retryAttempt] to be
     * written to the rescheduled queue entry.
     *
     * Unexpected exceptions from [RetryPolicy.evaluate] propagate normally.
     *
     * @param result the terminal [SynchronizationResult] that triggered
     *   retry evaluation. Required. Preserved unchanged.
     * @param retryAttempt the retry attempt number passed unchanged to
     *   [RetryPolicy.evaluate] and stored on the rescheduled entry if retry
     *   is requested. Required.
     * @param retryOperation the logical operation identifier passed to
     *   [RetryPolicy.evaluate]. Required.
     * @return a [SynchronizationRetryEvaluation] describing the deterministic
     *   outcome of policy evaluation.
     */
    public fun evaluate(
        result: SynchronizationResult,
        retryAttempt: RetryAttempt,
        retryOperation: RetryOperation,
    ): SynchronizationRetryEvaluation {

        // Step 1–2: Non-retryable variants.
        val errors = extractRetryErrors(result)
            ?: return SynchronizationRetryEvaluation.NotRequired

        // Step 3–5: Policy evaluation in original error order.
        val decisions = errors.map { error ->
            retryPolicy.evaluate(
                RetryEvaluationRequest(
                    synchronizationRequest = result.request,
                    operation = retryOperation,
                    error = error,
                    attempt = retryAttempt,
                    previousDelay = null,
                    provider = null,
                ),
            )
        }

        // Step 6: Determine whether any decision requests retry.
        val maxDelay = selectMaxRetryDelay(decisions)
            ?: return SynchronizationRetryEvaluation.StopRetry(
                error = errors.first(),
                decisions = decisions,
            )

        // Steps 7–10: Compute availability instant with overflow-safe arithmetic.
        val nowMillis = clock.now().epochMilliseconds
        val availableAtMillis = addMillisOverflowSafe(nowMillis, maxDelay.milliseconds)
        val availableAt = DataLoomInstant(epochMilliseconds = availableAtMillis)

        return SynchronizationRetryEvaluation.ShouldRetry(
            retryAttempt = retryAttempt,
            availableAt = availableAt,
            error = errors.first(),
            decisions = decisions,
            selectedDelay = maxDelay,
        )
    }

    /**
     * Adds [delayMillis] to [epochMillis] with overflow-safe semantics.
     *
     * If the sum would overflow [Long.MAX_VALUE] (detected by the sum
     * becoming negative after wrapping), [Long.MAX_VALUE] is returned
     * instead.
     */
    private fun addMillisOverflowSafe(epochMillis: Long, delayMillis: Long): Long {
        val sum = epochMillis + delayMillis
        return if (sum < 0L) Long.MAX_VALUE else sum
    }
}
