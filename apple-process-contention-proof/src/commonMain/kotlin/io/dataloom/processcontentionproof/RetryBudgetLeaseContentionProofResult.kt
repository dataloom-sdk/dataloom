package io.dataloom.processcontentionproof

/**
 * Plain, primitive-typed outcome of one call to
 * [AppleRetryBudgetLeaseContentionProof.waitForGoSignalThenAttemptAcquire],
 * mirroring [ProcessContentionProofResult]'s/[ConflictRecordContentionProofResult]'s
 * own "every field a primitive Kotlin type" Objective-C/Swift interop
 * discipline.
 *
 * Unlike either of those two shapes, this domain has no Android
 * contention-specific precedent to mirror a `Bundle`-key vocabulary from --
 * see [AppleRetryBudgetLeaseContentionProof]'s own KDoc for why this proof's
 * race shape (two processes racing [io.dataloom.runtime.queue.AppleFileQueueProvider.acquire]
 * for the single eligible [io.dataloom.api.queue.QueueEntry] carrying
 * already-persisted retry-budget state) was designed directly from that
 * provider's real implementation rather than ported from an existing
 * Android test. The vocabulary here (`ACQUIRED`/`NO_ELIGIBLE_ENTRY`) is this
 * proof's own, chosen to describe [io.dataloom.api.queue.QueueAcquireResult]'s
 * own two real variants directly.
 *
 * @property outcome one of `"ACQUIRED"` (this call's own [acquire] won the
 *   race and was returned the entry with a freshly assigned lease),
 *   `"NO_ELIGIBLE_ENTRY"` (this call's own [acquire] lost the race: by the
 *   time it took the provider's real cross-process `flock`, the other
 *   process had already leased the only eligible entry, so this call's own
 *   snapshot read found nothing eligible), `"PERSISTENCE_FAILURE"` (the
 *   provider itself reported a [io.dataloom.api.provider.ProviderOperationResult.Failure]),
 *   or `"TIMED_OUT_WAITING_FOR_GO_SIGNAL"` (the shared "go" marker file
 *   never appeared within the bounded poll budget -- a harness failure, not
 *   an `acquire` outcome).
 * @property retryAttemptNumber [io.dataloom.api.retry.RetryAttempt.number] as
 *   returned on the acquired [io.dataloom.api.queue.QueueEntry] when
 *   [outcome] is `"ACQUIRED"`, else `-1`. A winning race must observe the
 *   exact value [AppleRetryBudgetLeaseContentionProof.seedRetryWaitingEntryAndSignalReady]
 *   persisted before either process raced -- proving the real cross-process
 *   `acquire` contention never disturbed the already-durable retry-budget
 *   fields it did not itself own.
 * @property retryWindowStartedAtEpochMillis
 *   [io.dataloom.api.retry.RetryBudgetState.windowStartedAt] as returned on
 *   the acquired entry when [outcome] is `"ACQUIRED"`, else `-1`.
 * @property retryLastEvaluatedAtEpochMillis
 *   [io.dataloom.api.retry.RetryBudgetState.lastEvaluatedAt] as returned on
 *   the acquired entry when [outcome] is `"ACQUIRED"`, else `-1`.
 * @property retryCumulativeDelayMillis
 *   [io.dataloom.api.retry.RetryBudgetState.cumulativeDelay] as returned on
 *   the acquired entry when [outcome] is `"ACQUIRED"`, else `-1`.
 */
public class RetryBudgetLeaseContentionProofResult(
    public val outcome: String,
    public val retryAttemptNumber: Int,
    public val retryWindowStartedAtEpochMillis: Long,
    public val retryLastEvaluatedAtEpochMillis: Long,
    public val retryCumulativeDelayMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RetryBudgetLeaseContentionProofResult) return false
        return outcome == other.outcome &&
            retryAttemptNumber == other.retryAttemptNumber &&
            retryWindowStartedAtEpochMillis == other.retryWindowStartedAtEpochMillis &&
            retryLastEvaluatedAtEpochMillis == other.retryLastEvaluatedAtEpochMillis &&
            retryCumulativeDelayMillis == other.retryCumulativeDelayMillis
    }

    override fun hashCode(): Int {
        var result = outcome.hashCode()
        result = 31 * result + retryAttemptNumber
        result = 31 * result + retryWindowStartedAtEpochMillis.hashCode()
        result = 31 * result + retryLastEvaluatedAtEpochMillis.hashCode()
        result = 31 * result + retryCumulativeDelayMillis.hashCode()
        return result
    }

    override fun toString(): String = "RetryBudgetLeaseContentionProofResult(" +
        "outcome=$outcome, " +
        "retryAttemptNumber=$retryAttemptNumber, " +
        "retryWindowStartedAtEpochMillis=$retryWindowStartedAtEpochMillis, " +
        "retryLastEvaluatedAtEpochMillis=$retryLastEvaluatedAtEpochMillis, " +
        "retryCumulativeDelayMillis=$retryCumulativeDelayMillis)"
}
