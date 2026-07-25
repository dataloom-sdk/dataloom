package io.dataloom.runtime.submission

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.QueueProvider
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntryState

/**
 * Internal [DataLoomQueueSubmission] implementation assembled by
 * [io.dataloom.runtime.facade.DataLoomBuilder].
 *
 * ## Delegation contract
 *
 * Encoding is delegated to the application-supplied [encoder]. Persistence is
 * delegated to the configured [queueProvider]. No encoding or queue logic is
 * duplicated in this class beyond the structural validation described below.
 *
 * ## Submission flow
 *
 * 1. Pass the exact [QueuedSynchronizationSubmission] to [encoder].
 * 2. If encoding is rejected, return [QueueSubmissionResult.EncodingRejected];
 *    call no provider.
 * 3. Validate structural correspondence between the encoded request and the
 *    submission.
 * 4. If invalid, return [QueueSubmissionResult.ContractViolation]; call no
 *    provider.
 * 5. Call [queueProvider.enqueue][QueueProvider.enqueue] exactly once.
 * 6. On provider success, return [QueueSubmissionResult.Enqueued] preserving
 *    the exact provider result.
 * 7. On provider failure, return [QueueSubmissionResult.QueueProviderFailure]
 *    preserving the exact [DataLoomError].
 * 8. Allow [kotlinx.coroutines.CancellationException] to propagate normally.
 *
 * ## Encoded-request validation
 *
 * Validation verifies structural correspondence between the
 * [QueuedSynchronizationSubmission] and the
 * [io.dataloom.api.queue.QueueEnqueueRequest] produced by the encoder:
 *
 * - Encoded entry ID must match [QueuedSynchronizationSubmission.queueEntryId].
 * - Encoded availability timestamp must match
 *   [QueuedSynchronizationSubmission.availableAt].
 * - Encoded entry state must be [QueueEntryState.PENDING] (enforced by
 *   [QueueEnqueueRequest] constructor).
 * - Encoded entry must have no lease (enforced by [QueueEnqueueRequest]
 *   constructor).
 * - Encoded entry must have no retry attempt (enforced by [QueueEnqueueRequest]
 *   constructor).
 *
 * Validation never inspects, decodes, or logs encoded payload bytes.
 * Validation never corrects encoder output silently.
 *
 * ## Immutability
 *
 * All fields are immutable after construction. No provider, encoder, or
 * registry reference is replaceable after build.
 *
 * ## Concurrency
 *
 * This implementation owns no [kotlinx.coroutines.CoroutineScope] and selects
 * no dispatcher. Callers own the coroutine context.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param queueProvider the configured queue provider for enqueue operations.
 * @param encoder the application-owned encoder that converts submissions to
 *   enqueue requests.
 */
internal class DefaultDataLoomQueueSubmission(
    private val queueProvider: QueueProvider,
    private val encoder: QueuedSynchronizationWorkEncoder,
) : DataLoomQueueSubmission {

    override suspend fun submit(
        submission: QueuedSynchronizationSubmission,
    ): QueueSubmissionResult {
        // --- Step 1: Encode ---
        val encodingResult = encoder.encode(submission)

        // --- Step 2: Handle encoding rejection ---
        if (encodingResult is QueuedSynchronizationWorkEncodingResult.Rejected) {
            return QueueSubmissionResult.EncodingRejected(
                error = encodingResult.error,
            )
        }

        val enqueueRequest =
            (encodingResult as QueuedSynchronizationWorkEncodingResult.Encoded).request

        // --- Step 3: Validate structural correspondence ---
        val violationError = validateEnqueueRequest(submission, enqueueRequest)
        if (violationError != null) {
            return QueueSubmissionResult.ContractViolation(
                error = violationError,
                queueEntryId = submission.queueEntryId,
            )
        }

        // --- Step 4: Enqueue ---
        return when (val providerResult = queueProvider.enqueue(enqueueRequest)) {
            is ProviderOperationResult.Success -> QueueSubmissionResult.Enqueued(
                queueEntryId = submission.queueEntryId,
                providerResult = providerResult,
            )
            is ProviderOperationResult.Failure -> QueueSubmissionResult.QueueProviderFailure(
                error = providerResult.error,
                queueEntryId = submission.queueEntryId,
                failureStage = QueueSubmissionFailureStage.QUEUE_PROVIDER_ENQUEUE,
            )
        }
    }

    /**
     * Validates structural correspondence between [submission] and
     * [enqueueRequest].
     *
     * Returns a safe canonical [DataLoomError] when a violation is found, or
     * `null` when validation passes.
     *
     * Constraints verified by [QueueEnqueueRequest] construction (state=PENDING,
     * no lease, no retryAttempt) are already enforced before this method is
     * called, so only submission-specific correspondence is checked here.
     *
     * This method never inspects, decodes, or logs encoded payload content.
     * This method never modifies the encoded request.
     */
    private fun validateEnqueueRequest(
        submission: QueuedSynchronizationSubmission,
        enqueueRequest: QueueEnqueueRequest,
    ): DataLoomError? {
        val entry = enqueueRequest.entry

        if (entry.id != submission.queueEntryId) {
            return contractViolationError(
                "Encoded QueueEntryId does not match submission. " +
                    "Expected ${submission.queueEntryId.value} but encoded entry has ${entry.id.value}.",
            )
        }

        if (entry.availableAt != submission.availableAt) {
            return contractViolationError(
                "Encoded availableAt does not match submission. " +
                    "Expected ${submission.availableAt.epochMilliseconds} " +
                    "but encoded entry has ${entry.availableAt.epochMilliseconds}.",
            )
        }

        // State must be PENDING, lease must be null, retryAttempt must be null.
        // These are already enforced by QueueEnqueueRequest's init block, which
        // throws IllegalArgumentException if violated. Since enqueueRequest was
        // constructed successfully by the encoder, these invariants are satisfied.

        return null
    }

    private fun contractViolationError(message: String): DataLoomError =
        ContractViolationError(message = message)

    private data class ContractViolationError(
        override val message: String,
        override val code: ErrorCode = ErrorCode("DL-Q-SUBMISSION-CONTRACT-VIOLATION"),
        override val category: ErrorCategory = ErrorCategory.QUEUE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
