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
 * Platform-independent retry evaluator with optional bounded delay hints and
 * central durable budgets.
 *
 * Evaluation order is fail-closed protection, bounded hint extraction, policy
 * evaluation, hint-minimum enforcement, maximum-delay selection, budget
 * enforcement, and overflow-safe availability calculation. Budgets therefore
 * account for the final accepted delay rather than the pre-hint policy delay.
 */
public class SynchronizationRetryEvaluator private constructor(
    private val retryPolicy: RetryPolicy,
    private val clock: DataLoomClock,
    private val budgetEvaluator: RetryBudgetEvaluator?,
    private val hintEvaluator: RetryHintEvaluator?,
) {
    /** Creates an evaluator without central budgets or hint handling. */
    public constructor(
        retryPolicy: RetryPolicy,
        clock: DataLoomClock,
    ) : this(retryPolicy, clock, null, null)

    /** Creates an evaluator with central [budgetConfiguration]. */
    public constructor(
        retryPolicy: RetryPolicy,
        clock: DataLoomClock,
        budgetConfiguration: RetryBudgetConfiguration,
    ) : this(
        retryPolicy,
        clock,
        RetryBudgetEvaluator(budgetConfiguration),
        null,
    )

    /** Creates an evaluator with bounded provider/server [hintConfiguration]. */
    public constructor(
        retryPolicy: RetryPolicy,
        clock: DataLoomClock,
        hintConfiguration: RetryHintConfiguration,
    ) : this(
        retryPolicy,
        clock,
        null,
        RetryHintEvaluator(hintConfiguration),
    )

    /** Creates an evaluator with central budgets and bounded retry hints. */
    public constructor(
        retryPolicy: RetryPolicy,
        clock: DataLoomClock,
        budgetConfiguration: RetryBudgetConfiguration,
        hintConfiguration: RetryHintConfiguration,
    ) : this(
        retryPolicy,
        clock,
        RetryBudgetEvaluator(budgetConfiguration),
        RetryHintEvaluator(hintConfiguration),
    )

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
            hintEvaluator = hintEvaluator,
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
