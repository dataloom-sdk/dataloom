package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/** Result produced by one [SynchronizationRetryEvaluator.evaluate] invocation. */
public sealed interface SynchronizationRetryEvaluation {

    /** The terminal synchronization result does not require retry evaluation. */
    public data object NotRequired : SynchronizationRetryEvaluation

    /**
     * Retry was accepted for future execution.
     *
     * [retryBudgetState] is the exact next state to persist atomically with the
     * retry transition when central budgets are enabled. It is null when the
     * evaluator has no budget configuration.
     */
    public class ShouldRetry(
        public val retryAttempt: RetryAttempt,
        public val availableAt: DataLoomInstant,
        public val error: DataLoomError,
        decisions: List<RetryDecision>,
        public val selectedDelay: SchedulingDelay,
        public val retryBudgetState: RetryBudgetState? = null,
    ) : SynchronizationRetryEvaluation {

        private val _decisions: List<RetryDecision> = decisions.toList()

        public val decisions: List<RetryDecision>
            get() = _decisions

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ShouldRetry) return false
            return retryAttempt == other.retryAttempt &&
                availableAt == other.availableAt &&
                error == other.error &&
                _decisions == other._decisions &&
                selectedDelay == other.selectedDelay &&
                retryBudgetState == other.retryBudgetState
        }

        override fun hashCode(): Int {
            var result = retryAttempt.hashCode()
            result = 31 * result + availableAt.hashCode()
            result = 31 * result + error.hashCode()
            result = 31 * result + _decisions.hashCode()
            result = 31 * result + selectedDelay.hashCode()
            result = 31 * result + (retryBudgetState?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "SynchronizationRetryEvaluation.ShouldRetry(" +
                "retryAttempt=${retryAttempt.number}, " +
                "availableAt=${availableAt.epochMilliseconds}, " +
                "errorCode=${error.code.value}, " +
                "decisionCount=${_decisions.size}, " +
                "selectedDelay=${selectedDelay.milliseconds}, " +
                "budgeted=${retryBudgetState != null}" +
                ")"
    }

    /** Retry policy or central protection stopped the complete retry batch. */
    public class StopRetry(
        public val error: DataLoomError,
        decisions: List<RetryDecision>,
    ) : SynchronizationRetryEvaluation {

        private val _decisions: List<RetryDecision> = decisions.toList()

        public val decisions: List<RetryDecision>
            get() = _decisions

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StopRetry) return false
            return error == other.error && _decisions == other._decisions
        }

        override fun hashCode(): Int {
            var result = error.hashCode()
            result = 31 * result + _decisions.hashCode()
            return result
        }

        override fun toString(): String =
            "SynchronizationRetryEvaluation.StopRetry(" +
                "errorCode=${error.code.value}, " +
                "decisionCount=${_decisions.size}" +
                ")"
    }
}
