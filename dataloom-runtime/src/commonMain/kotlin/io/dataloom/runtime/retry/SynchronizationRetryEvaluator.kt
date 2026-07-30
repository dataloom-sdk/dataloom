package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Platform-independent retry evaluator with optional central durable budgets.
 *
 * Central protected-failure handling and policy evaluation occur before budget
 * enforcement. When budgets are enabled, [clock] is read once, the maximum
 * selected delay is checked against the current [RetryBudgetState], and the
 * accepted next state is returned for atomic persistence with rescheduling.
 */
public class SynchronizationRetryEvaluator private constructor(
    private val retryPolicy: RetryPolicy,
    private val clock: DataLoomClock,
    private val budgetEvaluator: RetryBudgetEvaluator?,
) {
    /** Creates an evaluator without elapsed or cumulative-delay budgets. */
    public constructor(
        retryPolicy: RetryPolicy,
        clock: DataLoomClock,
    ) : this(retryPolicy, clock, null)

    /** Creates an evaluator with central [budgetConfiguration]. */
    public constructor(
        retryPolicy: RetryPolicy,
        clock: DataLoomClock,
        budgetConfiguration: RetryBudgetConfiguration,
    ) : this(retryPolicy, clock, RetryBudgetEvaluator(budgetConfiguration))

    /** Evaluates retry without previously persisted budget state. */
    public fun evaluate(
        result: SynchronizationResult,
        retryAttempt: RetryAttempt,
        retryOperation: RetryOperation,
    ): SynchronizationRetryEvaluation = evaluate(
        result = result,
        retryAttempt = retryAttempt,
        retryOperation = retryOperation,
        retryBudgetState = null,
    )

    /**
     * Evaluates retry using the exact durable [retryBudgetState] acquired with
     * the work item. Rejected budget evaluations do not mutate that state.
     */
    public fun evaluate(
        result: SynchronizationResult,
        retryAttempt: RetryAttempt,
        retryOperation: RetryOperation,
        retryBudgetState: RetryBudgetState?,
    ): SynchronizationRetryEvaluation {
        val errors = extractRetryErrors(result)
            ?: return SynchronizationRetryEvaluation.NotRequired

        val evaluated = evaluateRetryDecisions(
            retryPolicy = retryPolicy,
            synchronizationRequest = result.request,
            retryOperation = retryOperation,
            retryAttempt = retryAttempt,
            errors = errors,
        )

        evaluated.blockingError?.let { blockingError ->
            return SynchronizationRetryEvaluation.StopRetry(
                error = blockingError,
                decisions = evaluated.decisions,
            )
        }

        val maxDelay = selectMaxRetryDelay(evaluated.decisions)
            ?: return SynchronizationRetryEvaluation.StopRetry(
                error = errors.first(),
                decisions = evaluated.decisions,
            )

        val evaluatedAt = clock.now()
        val nextBudgetState = when (val budgets = budgetEvaluator?.evaluate(
            state = retryBudgetState,
            evaluatedAt = evaluatedAt,
            proposedDelay = maxDelay,
        )) {
            null -> null
            is RetryBudgetEvaluation.Accepted -> budgets.nextState
            is RetryBudgetEvaluation.Stopped -> {
                return SynchronizationRetryEvaluation.StopRetry(
                    error = errors.first(),
                    decisions = List(evaluated.decisions.size) {
                        RetryDecision.Stop(reason = budgets.reason)
                    },
                )
            }
        }

        val availableAtMillis = addMillisOverflowSafe(
            evaluatedAt.epochMilliseconds,
            maxDelay.milliseconds,
        )

        return SynchronizationRetryEvaluation.ShouldRetry(
            retryAttempt = retryAttempt,
            availableAt = DataLoomInstant(epochMilliseconds = availableAtMillis),
            error = errors.first(),
            decisions = evaluated.decisions,
            selectedDelay = maxDelay,
            retryBudgetState = nextBudgetState,
        )
    }

    private fun addMillisOverflowSafe(epochMillis: Long, delayMillis: Long): Long {
        if (epochMillis > Long.MAX_VALUE - delayMillis) return Long.MAX_VALUE
        return epochMillis + delayMillis
    }
}
