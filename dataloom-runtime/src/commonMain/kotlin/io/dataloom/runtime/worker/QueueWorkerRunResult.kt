package io.dataloom.runtime.worker

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.runtime.queue.QueueProcessingResult

/**
 * Structured result returned by [QueueWorkerCoordinator.run].
 *
 * ## Variants
 *
 * - [RecoveryFailed] — expired-lease recovery was enabled and the
 *   [io.dataloom.api.queue.QueueProvider] returned a canonical failure.
 *   No queue acquisition or scheduling was attempted.
 * - [ProcessingCompleted] — queue processing completed without a provider
 *   failure (either [QueueProcessingResult.NoWork] or
 *   [QueueProcessingResult.Processed]). The scheduling result reflects what
 *   happened after processing.
 * - [ProcessingFailed] — queue processing failed with a provider error
 *   ([QueueProcessingResult.QueueProviderFailure] or
 *   [QueueProcessingResult.QueueContractViolation]). No follow-up scheduling
 *   result is included.
 *
 * ## Scheduler failure contract
 *
 * A scheduler failure after successful queue processing is reported inside
 * [ProcessingCompleted.schedulingResult] as
 * [QueueWorkerSchedulingResult.SchedulerFailed]. It does not change the
 * variant to [ProcessingFailed]. Durable queue transitions have already been
 * persisted and must not be rolled back.
 *
 * ## Cancellation
 *
 * [kotlin.coroutines.cancellation.CancellationException] from any provider or
 * the clock propagates normally and is never converted into this result type.
 *
 * ## Sensitive-data restrictions
 *
 * No raw [Throwable], no stack trace, and no provider instance are exposed.
 * Error fields carry canonical [DataLoomError] values whose messages must
 * already be sanitized.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface QueueWorkerRunResult {

    /**
     * Expired-lease recovery was enabled and
     * [io.dataloom.api.queue.QueueProvider.recoverExpiredLeases] returned
     * a canonical failure.
     *
     * No queue acquisition was attempted. No scheduling was attempted.
     * The exact provider error is preserved.
     *
     * @param error the exact canonical [DataLoomError] returned by the
     *   recovery operation.
     */
    public data class RecoveryFailed(
        /** Exact canonical [DataLoomError] from the recovery operation. */
        public val error: DataLoomError,
    ) : QueueWorkerRunResult

    /**
     * Queue processing completed without a provider failure.
     *
     * [processingResult] is either [QueueProcessingResult.NoWork] or
     * [QueueProcessingResult.Processed]. The [schedulingResult] reflects the
     * scheduling step that followed processing. A scheduler failure does not
     * change this variant; it is represented inside [schedulingResult].
     *
     * @param recoveryResult the optional [ExpiredLeaseRecoveryResult] from a
     *   successful recovery operation, or `null` when recovery was disabled or
     *   was not performed.
     * @param processingResult the exact [QueueProcessingResult.NoWork] or
     *   [QueueProcessingResult.Processed] outcome.
     * @param schedulingResult the exact [QueueWorkerSchedulingResult]
     *   describing the scheduling step.
     */
    public data class ProcessingCompleted(
        /**
         * Optional [ExpiredLeaseRecoveryResult] from a successful recovery
         * operation. Null when recovery was disabled or not performed.
         */
        public val recoveryResult: ExpiredLeaseRecoveryResult?,

        /**
         * Exact [QueueProcessingResult.NoWork] or [QueueProcessingResult.Processed]
         * outcome from the processing cycle.
         */
        public val processingResult: QueueProcessingResult,

        /** Exact [QueueWorkerSchedulingResult] from the scheduling step. */
        public val schedulingResult: QueueWorkerSchedulingResult,
    ) : QueueWorkerRunResult

    /**
     * Queue processing failed with a provider error.
     *
     * [processingResult] is either [QueueProcessingResult.QueueProviderFailure]
     * or [QueueProcessingResult.QueueContractViolation]. No follow-up
     * scheduling is performed.
     *
     * @param recoveryResult the optional [ExpiredLeaseRecoveryResult] from a
     *   successful recovery operation, or `null` when recovery was disabled or
     *   was not performed.
     * @param processingResult the exact [QueueProcessingResult.QueueProviderFailure]
     *   or [QueueProcessingResult.QueueContractViolation] outcome.
     */
    public data class ProcessingFailed(
        /**
         * Optional [ExpiredLeaseRecoveryResult] from a successful recovery
         * operation. Null when recovery was disabled or not performed.
         */
        public val recoveryResult: ExpiredLeaseRecoveryResult?,

        /**
         * Exact [QueueProcessingResult.QueueProviderFailure] or
         * [QueueProcessingResult.QueueContractViolation] outcome from the
         * processing cycle.
         */
        public val processingResult: QueueProcessingResult,
    ) : QueueWorkerRunResult
}
