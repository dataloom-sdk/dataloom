package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant

/**
 * Sealed result returned by a [QueueEntryExecutionHandler] after executing a
 * single acquired [io.dataloom.api.queue.QueueEntry].
 *
 * Each variant maps directly to exactly one [io.dataloom.api.queue.QueueProvider]
 * transition request. The [DurableQueueExecutionProcessor] reads this outcome
 * and issues the corresponding transition without modification.
 *
 * ## Variants
 *
 * - [Completed] — the entry was processed successfully. Maps to
 *   [io.dataloom.api.queue.QueueCompletionRequest].
 * - [Reschedule] — the entry should be retried at a future time. Maps to
 *   [io.dataloom.api.queue.QueueRescheduleRequest].
 * - [Deferred] — execution constraints were not satisfied before an attempt.
 *   Maps to [io.dataloom.api.queue.QueueDeferralRequest].
 * - [Failed] — the entry processing failed permanently or should be
 *   dead-lettered. Maps to [io.dataloom.api.queue.QueueFailureRequest].
 * - [Cancelled] — the entry was explicitly cancelled by the handler. Maps to
 *   [io.dataloom.api.queue.QueueCancellationRequest].
 *
 * ## Explicit Cancelled vs thrown CancellationException
 *
 * A [Cancelled] outcome is a deliberate, business-level signal. It triggers a
 * [io.dataloom.api.queue.QueueCancellationRequest] to transition the entry to
 * [io.dataloom.api.queue.QueueEntryState.CANCELLED].
 *
 * A thrown [kotlin.coroutines.cancellation.CancellationException] from the
 * handler is a different event: it propagates normally out of
 * [DurableQueueExecutionProcessor.process] and does not create any queue
 * transition.
 *
 * ## Diagnostics
 *
 * Outcome fields must not contain credentials, tokens, encryption keys,
 * personal data, full payloads, raw [Throwable] instances, or stack traces.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface QueueEntryExecutionOutcome {

    /**
     * The entry was processed successfully.
     *
     * Maps to [io.dataloom.api.queue.QueueCompletionRequest].
     *
     * @param completedAt required instant at which completion is reported.
     */
    public data class Completed(
        /** Required instant at which completion is reported. */
        public val completedAt: DataLoomInstant,
    ) : QueueEntryExecutionOutcome

    /**
     * The entry processing failed transiently and should be retried at a
     * future time.
     *
     * Maps to [io.dataloom.api.queue.QueueRescheduleRequest].
     *
     * @param retryAttempt required retry attempt counter. Supplied by the
     *   runtime after evaluating retry policy.
     * @param availableAt required instant at which the entry becomes eligible
     *   for re-acquisition.
     * @param error required canonical error that caused the reschedule.
     */
    public data class Reschedule(
        /**
         * Required retry attempt counter.
         *
         * Supplied by the runtime after evaluating retry policy.
         */
        public val retryAttempt: RetryAttempt,

        /**
         * Required instant at which the entry becomes eligible for
         * re-acquisition.
         */
        public val availableAt: DataLoomInstant,

        /** Required canonical error that caused the reschedule. */
        public val error: DataLoomError,
    ) : QueueEntryExecutionOutcome

    /**
     * The entry could not begin execution because a declared constraint was
     * not satisfied.
     *
     * Maps to [io.dataloom.api.queue.QueueDeferralRequest]. This outcome does
     * not contain or consume a retry attempt.
     *
     * @param availableAt instant at which the entry becomes eligible again.
     * @param reason stable reason for the non-retry deferral.
     */
    public data class Deferred(
        public val availableAt: DataLoomInstant,
        public val reason: QueueDeferralReason,
    ) : QueueEntryExecutionOutcome

    /**
     * The entry processing failed permanently or is to be dead-lettered.
     *
     * Maps to [io.dataloom.api.queue.QueueFailureRequest].
     *
     * @param error required canonical error describing the processing failure.
     * @param disposition required disposition indicating whether the entry
     *   should transition to [io.dataloom.api.queue.QueueEntryState.FAILED]
     *   or [io.dataloom.api.queue.QueueEntryState.DEAD_LETTER].
     */
    public data class Failed(
        /** Required canonical error describing the processing failure. */
        public val error: DataLoomError,

        /**
         * Required disposition indicating the terminal failure state.
         *
         * Determines whether the entry transitions to
         * [io.dataloom.api.queue.QueueEntryState.FAILED] or
         * [io.dataloom.api.queue.QueueEntryState.DEAD_LETTER].
         */
        public val disposition: QueueFailureDisposition,
    ) : QueueEntryExecutionOutcome

    /**
     * The entry was explicitly cancelled by the handler.
     *
     * Maps to [io.dataloom.api.queue.QueueCancellationRequest].
     *
     * This outcome is distinct from a thrown
     * [kotlin.coroutines.cancellation.CancellationException]. A thrown
     * exception propagates normally and creates no queue transition. This
     * outcome is a deliberate, business-level signal that transitions the
     * entry to [io.dataloom.api.queue.QueueEntryState.CANCELLED].
     *
     * @param context required execution context for the cancellation request.
     */
    public data class Cancelled(
        /**
         * Required execution context associated with this cancellation
         * request.
         */
        public val context: ExecutionContext,
    ) : QueueEntryExecutionOutcome
}
