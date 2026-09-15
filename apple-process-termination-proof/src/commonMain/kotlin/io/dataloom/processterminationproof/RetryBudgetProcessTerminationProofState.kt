package io.dataloom.processterminationproof

/**
 * Primitive-typed snapshot of one persisted durable retry-budget record,
 * returned by [AppleRetryBudgetProcessTerminationProof].
 *
 * Every field is an Int/Long so this type crosses the Kotlin/Native
 * Objective-C/Swift interop boundary in the simplest shape available -- the
 * same design used by [ProcessTerminationProofState] for the circuit-breaker
 * proof. The real, richer production types this is derived from are
 * `io.dataloom.api.retry.RetryAttempt` and `io.dataloom.api.retry.RetryBudgetState`
 * (`dataloom-api`), as persisted on `io.dataloom.api.queue.QueueEntry.retryAttempt`
 * / `QueueEntry.retryBudgetState` by the real production `AppleFileQueueProvider`
 * (`dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/`) -- this
 * class exists only so the Apple Simulator proof app can read the outcome
 * back through plain Swift-visible fields, without needing to construct or
 * pattern-match DataLoom's richer queue/retry types from Swift.
 *
 * Field names mirror the Android precedent's own result keys exactly
 * (`AndroidProcessTerminationRetryBudgetInstrumentedTest`,
 * `RetryBudgetProcessTerminationContract`): `retry_attempt_number`,
 * `retry_window_started_at_ms`, `retry_last_evaluated_at_ms`, and
 * `retry_cumulative_delay_ms` on Android's `queue_entries` table correspond
 * respectively to [retryAttemptNumber], [retryWindowStartedAtEpochMillis],
 * [retryLastEvaluatedAtEpochMillis], and [retryCumulativeDelayMillis] here --
 * verified directly against the real Apple field names
 * ([io.dataloom.api.retry.RetryAttempt.number],
 * [io.dataloom.api.retry.RetryBudgetState.windowStartedAt],
 * [io.dataloom.api.retry.RetryBudgetState.lastEvaluatedAt],
 * [io.dataloom.api.retry.RetryBudgetState.cumulativeDelay]) rather than
 * assumed to carry over 1:1 from Android's column names.
 *
 * @property retryAttemptNumber the persisted [io.dataloom.api.retry.RetryAttempt.number].
 * @property retryWindowStartedAtEpochMillis the persisted
 *   [io.dataloom.api.retry.RetryBudgetState.windowStartedAt] in epoch milliseconds.
 * @property retryLastEvaluatedAtEpochMillis the persisted
 *   [io.dataloom.api.retry.RetryBudgetState.lastEvaluatedAt] in epoch milliseconds.
 * @property retryCumulativeDelayMillis the persisted
 *   [io.dataloom.api.retry.RetryBudgetState.cumulativeDelay] in milliseconds.
 */
public class RetryBudgetProcessTerminationProofState(
    public val retryAttemptNumber: Int,
    public val retryWindowStartedAtEpochMillis: Long,
    public val retryLastEvaluatedAtEpochMillis: Long,
    public val retryCumulativeDelayMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RetryBudgetProcessTerminationProofState) return false
        return retryAttemptNumber == other.retryAttemptNumber &&
            retryWindowStartedAtEpochMillis == other.retryWindowStartedAtEpochMillis &&
            retryLastEvaluatedAtEpochMillis == other.retryLastEvaluatedAtEpochMillis &&
            retryCumulativeDelayMillis == other.retryCumulativeDelayMillis
    }

    override fun hashCode(): Int {
        var result = retryAttemptNumber.hashCode()
        result = 31 * result + retryWindowStartedAtEpochMillis.hashCode()
        result = 31 * result + retryLastEvaluatedAtEpochMillis.hashCode()
        result = 31 * result + retryCumulativeDelayMillis.hashCode()
        return result
    }

    override fun toString(): String = "RetryBudgetProcessTerminationProofState(" +
        "retryAttemptNumber=$retryAttemptNumber, " +
        "retryWindowStartedAtEpochMillis=$retryWindowStartedAtEpochMillis, " +
        "retryLastEvaluatedAtEpochMillis=$retryLastEvaluatedAtEpochMillis, " +
        "retryCumulativeDelayMillis=$retryCumulativeDelayMillis)"
}
