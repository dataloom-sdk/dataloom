package io.dataloom.runtime.submission

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.queue.QueueEnqueueRequest

/**
 * Internal, provider-free queue-submission preparation shared by direct and
 * circuit-aware submission paths.
 *
 * Encoding and structural validation complete before any queue-provider,
 * timeout, or circuit operation. Unexpected encoder exceptions propagate.
 */
internal class QueueSubmissionPreflight(
    private val encoder: QueuedSynchronizationWorkEncoder,
) {

    fun prepare(
        submission: QueuedSynchronizationSubmission,
    ): QueueSubmissionPreflightResult = when (val encoding = encoder.encode(submission)) {
        is QueuedSynchronizationWorkEncodingResult.Rejected -> {
            QueueSubmissionPreflightResult.EncodingRejected(encoding.error)
        }
        is QueuedSynchronizationWorkEncodingResult.Encoded -> {
            val violation = validateEnqueueRequest(submission, encoding.request)
            if (violation == null) {
                QueueSubmissionPreflightResult.Ready(encoding.request)
            } else {
                QueueSubmissionPreflightResult.ContractViolation(
                    error = violation,
                    queueEntryId = submission.queueEntryId,
                )
            }
        }
    }

    private fun validateEnqueueRequest(
        submission: QueuedSynchronizationSubmission,
        enqueueRequest: QueueEnqueueRequest,
    ): DataLoomError? {
        val entry = enqueueRequest.entry

        if (entry.id != submission.queueEntryId) {
            return ContractViolationError(
                message = "Encoded QueueEntryId does not match submission. " +
                    "Expected ${submission.queueEntryId.value} but encoded entry has ${entry.id.value}.",
            )
        }

        if (entry.availableAt != submission.availableAt) {
            return ContractViolationError(
                message = "Encoded availableAt does not match submission. " +
                    "Expected ${submission.availableAt.epochMilliseconds} " +
                    "but encoded entry has ${entry.availableAt.epochMilliseconds}.",
            )
        }

        if (entry.workflowTimeoutState != submission.workflowTimeoutState) {
            return ContractViolationError(
                message = "Encoded workflow timeout evidence does not match submission.",
            )
        }

        return null
    }

    private data class ContractViolationError(
        override val message: String,
        override val code: ErrorCode = ErrorCode("DL-Q-SUBMISSION-CONTRACT-VIOLATION"),
        override val category: ErrorCategory = ErrorCategory.QUEUE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError
}

/** Result of local queue-submission encoding and structural validation. */
internal sealed interface QueueSubmissionPreflightResult {

    /** The encoded request is structurally valid and may reach provider policy. */
    data class Ready(
        val request: QueueEnqueueRequest,
    ) : QueueSubmissionPreflightResult

    /** The application-owned encoder rejected the submission. */
    data class EncodingRejected(
        val error: DataLoomError,
    ) : QueueSubmissionPreflightResult

    /** The encoded request does not correspond to the original submission. */
    data class ContractViolation(
        val error: DataLoomError,
        val queueEntryId: QueueEntryId,
    ) : QueueSubmissionPreflightResult
}
