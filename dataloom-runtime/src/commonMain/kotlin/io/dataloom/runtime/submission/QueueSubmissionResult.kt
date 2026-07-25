package io.dataloom.runtime.submission

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderOperationResult

/**
 * Sealed result produced by [DataLoomQueueSubmission.submit] for a single
 * [QueuedSynchronizationSubmission].
 *
 * ## Variants
 *
 * | Variant                | Meaning                                                         |
 * |------------------------|-----------------------------------------------------------------|
 * | [Enqueued]             | The entry was successfully persisted in the durable queue.      |
 * | [EncodingRejected]     | The encoder rejected the submission with a canonical error.     |
 * | [ContractViolation]    | The encoded request did not satisfy structural correspondence.   |
 * | [QueueProviderFailure] | The queue provider returned a canonical failure.                |
 *
 * ## Security restrictions
 *
 * All variants must not expose credentials, tokens, encryption keys, personal
 * data, stack traces, raw exceptions, or encoded payload bytes.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface QueueSubmissionResult {

    /**
     * The queue entry was successfully persisted.
     *
     * [queueEntryId] is the exact identifier supplied in the
     * [QueuedSynchronizationSubmission]. [providerResult] is the exact
     * [ProviderOperationResult.Success] returned by
     * [io.dataloom.api.provider.QueueProvider.enqueue].
     *
     * No worker is started automatically. No scheduler is invoked. The
     * caller is responsible for triggering the queue worker when appropriate.
     *
     * @param queueEntryId the exact [QueueEntryId] from the submission.
     * @param providerResult the exact successful result from the
     *   [io.dataloom.api.provider.QueueProvider].
     */
    public data class Enqueued(
        /** The exact [QueueEntryId] from the submission. */
        public val queueEntryId: QueueEntryId,

        /**
         * The exact [ProviderOperationResult.Success] returned by
         * [io.dataloom.api.provider.QueueProvider.enqueue].
         */
        public val providerResult: ProviderOperationResult.Success<Unit>,
    ) : QueueSubmissionResult

    /**
     * The [QueuedSynchronizationWorkEncoder] rejected the submission.
     *
     * [error] is the canonical [DataLoomError] supplied by the encoder in its
     * [QueuedSynchronizationWorkEncodingResult.Rejected] result. No
     * [io.dataloom.api.provider.QueueProvider] operation was performed.
     *
     * @param error the exact canonical [DataLoomError] from the encoder's
     *   rejection.
     */
    public data class EncodingRejected(
        /** The exact canonical [DataLoomError] from the encoder's rejection. */
        public val error: DataLoomError,
    ) : QueueSubmissionResult

    /**
     * The encoded [io.dataloom.api.queue.QueueEnqueueRequest] did not satisfy
     * structural correspondence with the [QueuedSynchronizationSubmission].
     *
     * [error] is a safe canonical [DataLoomError] describing the violation.
     * [queueEntryId] is the identifier from the submission when it can be
     * safely included in diagnostics.
     *
     * No [io.dataloom.api.provider.QueueProvider] operation was performed.
     *
     * @param error a safe canonical [DataLoomError] describing the violation.
     * @param queueEntryId the [QueueEntryId] from the submission, where safe.
     */
    public data class ContractViolation(
        /** Safe canonical [DataLoomError] describing the contract violation. */
        public val error: DataLoomError,

        /** The [QueueEntryId] from the submission, where safe to include. */
        public val queueEntryId: QueueEntryId?,
    ) : QueueSubmissionResult

    /**
     * The [io.dataloom.api.provider.QueueProvider] returned a canonical
     * failure from [io.dataloom.api.provider.QueueProvider.enqueue].
     *
     * [error] is the exact [DataLoomError] from the provider failure. The
     * provider is not retried automatically. No new [QueueEntryId] is
     * generated.
     *
     * @param error the exact canonical [DataLoomError] from the provider
     *   failure.
     * @param queueEntryId the exact [QueueEntryId] from the submission.
     * @param failureStage always [QueueSubmissionFailureStage.QUEUE_PROVIDER_ENQUEUE]
     *   for this variant.
     */
    public data class QueueProviderFailure(
        /** The exact canonical [DataLoomError] from the provider failure. */
        public val error: DataLoomError,

        /** The exact [QueueEntryId] from the submission. */
        public val queueEntryId: QueueEntryId,

        /**
         * Always [QueueSubmissionFailureStage.QUEUE_PROVIDER_ENQUEUE] for
         * this result variant.
         */
        public val failureStage: QueueSubmissionFailureStage,
    ) : QueueSubmissionResult
}
