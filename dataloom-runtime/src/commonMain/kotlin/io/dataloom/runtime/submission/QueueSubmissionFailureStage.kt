package io.dataloom.runtime.submission

/**
 * Closed set of failure stages for a [QueueSubmissionResult.QueueProviderFailure].
 *
 * [QueueSubmissionFailureStage] identifies the operation boundary at which a
 * failure originated during a [DataLoomQueueSubmission.submit] call.
 *
 * ## Usage
 *
 * Use [QueueSubmissionFailureStage] values for structured diagnostics. Do not
 * persist or compare by ordinal; compare by enum constant name or identity.
 *
 * ## Restrictions
 *
 * - [kotlinx.coroutines.CancellationException] is not represented as a failure
 *   stage. Cancellation propagates normally from
 *   [DataLoomQueueSubmission.submit].
 * - Structured encoding rejection is represented by
 *   [QueueSubmissionResult.EncodingRejected], not by this enum.
 * - Structural contract violations are represented by
 *   [QueueSubmissionResult.ContractViolation], not by this enum.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library types only. Safe for use in Kotlin
 * Multiplatform common code. Enum ordinals must not be persisted.
 */
public enum class QueueSubmissionFailureStage {

    /**
     * Failure originated in the [QueuedSynchronizationWorkEncoder].
     *
     * Used only when an unexpected exception propagates from the encoder.
     * Structured encoding rejection is represented separately by
     * [QueueSubmissionResult.EncodingRejected].
     */
    ENCODING,

    /**
     * Failure originated in the encoded-request validation step.
     *
     * Indicates that the [QueuedSynchronizationWorkEncoder] produced a result
     * that did not satisfy structural correspondence between the
     * [QueuedSynchronizationSubmission] and the
     * [io.dataloom.api.queue.QueueEnqueueRequest].
     */
    ENCODED_REQUEST_VALIDATION,

    /**
     * Failure originated in [io.dataloom.api.queue.QueueProvider.enqueue].
     *
     * The [io.dataloom.api.queue.QueueProvider] returned a
     * [io.dataloom.api.provider.ProviderOperationResult.Failure].
     */
    QUEUE_PROVIDER_ENQUEUE,
}
