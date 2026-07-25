package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/**
 * Sealed result produced by a single [SynchronizationRetryEvaluator.evaluate]
 * invocation.
 *
 * ## Variants
 *
 * | Variant        | Meaning                                                         |
 * |----------------|-----------------------------------------------------------------|
 * | [NotRequired]  | The result variant is not eligible for retry evaluation.        |
 * | [ShouldRetry]  | At least one retry decision was produced; retry is requested.   |
 * | [StopRetry]    | All decisions were Stop; no retry will occur.                   |
 *
 * ## Relationship to [QueueEntryExecutionOutcome][io.dataloom.runtime.queue.QueueEntryExecutionOutcome]
 *
 * [ShouldRetry] maps to
 * [QueueEntryExecutionOutcome.Reschedule][io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Reschedule].
 * [StopRetry] maps to
 * [QueueEntryExecutionOutcome.Failed][io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Failed].
 * [NotRequired] maps to
 * [QueueEntryExecutionOutcome.Completed][io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Completed]
 * when emitted for a non-retry-eligible result.
 *
 * ## Sensitive-data restrictions
 *
 * Fields must not expose credentials, tokens, encryption keys, personal data,
 * full synchronization payloads, raw [Throwable] instances, or stack traces.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface SynchronizationRetryEvaluation {

    /**
     * The [io.dataloom.api.synchronization.SynchronizationResult] variant is
     * not eligible for retry evaluation.
     *
     * Returned for [io.dataloom.api.synchronization.SynchronizationResult.Succeeded],
     * [io.dataloom.api.synchronization.SynchronizationResult.Skipped], and
     * [io.dataloom.api.synchronization.SynchronizationResult.Cancelled].
     *
     * No [io.dataloom.api.retry.RetryPolicy] invocation occurs for this result.
     */
    public data object NotRequired : SynchronizationRetryEvaluation

    /**
     * Retry policy evaluation produced at least one
     * [io.dataloom.api.retry.RetryDecision.Retry] decision and the entry
     * should be rescheduled.
     *
     * ## Properties
     *
     * - [retryAttempt]: the exact attempt number to store on the rescheduled
     *   entry. This is the same value supplied to [SynchronizationRetryEvaluator.evaluate]
     *   and passed unchanged to [io.dataloom.api.retry.RetryPolicy.evaluate].
     * - [availableAt]: the computed instant at which the rescheduled entry
     *   becomes eligible for re-acquisition, derived from the injected
     *   [io.dataloom.api.time.DataLoomClock] and the [selectedDelay].
     *   Timestamp arithmetic is overflow-safe.
     * - [error]: the primary canonical error that triggered retry evaluation.
     * - [decisions]: the ordered list of decisions produced by policy evaluation.
     * - [selectedDelay]: the maximum [SchedulingDelay] across all
     *   [io.dataloom.api.retry.RetryDecision.Retry] decisions.
     *
     * ## Sensitive-data restrictions
     *
     * [error] must not expose credentials, tokens, encryption keys, personal
     * data, full payloads, raw [Throwable] instances, or stack traces.
     *
     * @param retryAttempt the retry attempt number to store on the rescheduled
     *   entry. Passed unchanged to [io.dataloom.api.retry.RetryPolicy.evaluate].
     * @param availableAt the instant at which the entry becomes eligible for
     *   re-acquisition. Computed from clock plus [selectedDelay] with
     *   overflow-safe arithmetic.
     * @param error the primary canonical error that triggered retry evaluation.
     * @param decisions ordered list of [RetryDecision] values from policy
     *   evaluation.
     * @param selectedDelay the maximum [SchedulingDelay] across all retry
     *   decisions.
     */
    public class ShouldRetry(
        /** The retry attempt number, passed unchanged to policy and stored on reschedule. */
        public val retryAttempt: RetryAttempt,
        /** The computed instant at which the entry becomes eligible for re-acquisition. */
        public val availableAt: DataLoomInstant,
        /** The primary canonical error that triggered retry evaluation. */
        public val error: DataLoomError,
        decisions: List<RetryDecision>,
        /** The maximum selected delay across all Retry decisions. */
        public val selectedDelay: SchedulingDelay,
    ) : SynchronizationRetryEvaluation {

        private val _decisions: List<RetryDecision> = decisions.toList()

        /**
         * Ordered [RetryDecision] values produced by policy evaluation.
         *
         * The collection is a defensive copy of the caller-supplied list.
         */
        public val decisions: List<RetryDecision>
            get() = _decisions

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ShouldRetry) return false
            return retryAttempt == other.retryAttempt &&
                availableAt == other.availableAt &&
                error == other.error &&
                _decisions == other._decisions &&
                selectedDelay == other.selectedDelay
        }

        override fun hashCode(): Int {
            var result = retryAttempt.hashCode()
            result = 31 * result + availableAt.hashCode()
            result = 31 * result + error.hashCode()
            result = 31 * result + _decisions.hashCode()
            result = 31 * result + selectedDelay.hashCode()
            return result
        }

        override fun toString(): String =
            "SynchronizationRetryEvaluation.ShouldRetry(" +
                "retryAttempt=${retryAttempt.number}, " +
                "availableAt=${availableAt.epochMilliseconds}, " +
                "errorCode=${error.code.value}, " +
                "decisionCount=${_decisions.size}, " +
                "selectedDelay=${selectedDelay.milliseconds}" +
                ")"
    }

    /**
     * Retry policy evaluation produced only
     * [io.dataloom.api.retry.RetryDecision.Stop] decisions. No retry will
     * occur.
     *
     * ## Properties
     *
     * - [error]: the primary canonical error that triggered retry evaluation.
     * - [decisions]: the ordered list of Stop decisions produced by policy
     *   evaluation.
     *
     * ## Sensitive-data restrictions
     *
     * [error] must not expose credentials, tokens, encryption keys, personal
     * data, full payloads, raw [Throwable] instances, or stack traces.
     *
     * @param error the primary canonical error that triggered retry evaluation.
     * @param decisions ordered list of [RetryDecision] values from policy
     *   evaluation. All decisions are [io.dataloom.api.retry.RetryDecision.Stop].
     */
    public class StopRetry(
        /** The primary canonical error that triggered retry evaluation. */
        public val error: DataLoomError,
        decisions: List<RetryDecision>,
    ) : SynchronizationRetryEvaluation {

        private val _decisions: List<RetryDecision> = decisions.toList()

        /**
         * Ordered [RetryDecision] values produced by policy evaluation.
         *
         * All decisions are [io.dataloom.api.retry.RetryDecision.Stop].
         * The collection is a defensive copy of the caller-supplied list.
         */
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
