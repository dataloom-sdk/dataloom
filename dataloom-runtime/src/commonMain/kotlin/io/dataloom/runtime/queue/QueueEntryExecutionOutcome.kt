package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.time.DataLoomInstant

/**
 * Result returned after executing one acquired queue entry.
 *
 * Each variant maps to exactly one queue-provider transition. Outcome fields
 * must remain payload-free and redaction-safe.
 */
public sealed interface QueueEntryExecutionOutcome {

    public data class Completed(
        public val completedAt: DataLoomInstant,
    ) : QueueEntryExecutionOutcome

    /**
     * A genuine failure was accepted for future retry.
     *
     * [retryBudgetState] is persisted atomically with the retry transition when
     * elapsed/cumulative budgets are enabled.
     */
    public data class Reschedule(
        public val retryAttempt: RetryAttempt,
        public val availableAt: DataLoomInstant,
        public val error: DataLoomError,
        public val retryBudgetState: RetryBudgetState? = null,
    ) : QueueEntryExecutionOutcome

    /** Constraint deferral that preserves retry attempt and budget state. */
    public data class Deferred(
        public val availableAt: DataLoomInstant,
        public val reason: QueueDeferralReason,
    ) : QueueEntryExecutionOutcome

    public data class Failed(
        public val error: DataLoomError,
        public val disposition: QueueFailureDisposition,
    ) : QueueEntryExecutionOutcome

    public data class Cancelled(
        public val context: ExecutionContext,
    ) : QueueEntryExecutionOutcome
}
