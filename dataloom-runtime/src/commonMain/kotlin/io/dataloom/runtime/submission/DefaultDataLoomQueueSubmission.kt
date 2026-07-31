package io.dataloom.runtime.submission

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueProvider

/**
 * Internal direct queue-submission implementation assembled by
 * [io.dataloom.runtime.facade.DataLoomBuilder].
 *
 * Local encoding and structural validation are delegated to the shared
 * [QueueSubmissionPreflight]. The queue provider is called exactly once only
 * after preflight succeeds. Cancellation and unexpected exceptions propagate.
 */
internal class DefaultDataLoomQueueSubmission(
    private val queueProvider: QueueProvider,
    encoder: QueuedSynchronizationWorkEncoder,
) : DataLoomQueueSubmission {

    private val preflight = QueueSubmissionPreflight(encoder)

    override suspend fun submit(
        submission: QueuedSynchronizationSubmission,
    ): QueueSubmissionResult = when (val prepared = preflight.prepare(submission)) {
        is QueueSubmissionPreflightResult.EncodingRejected -> {
            QueueSubmissionResult.EncodingRejected(prepared.error)
        }
        is QueueSubmissionPreflightResult.ContractViolation -> {
            QueueSubmissionResult.ContractViolation(
                error = prepared.error,
                queueEntryId = prepared.queueEntryId,
            )
        }
        is QueueSubmissionPreflightResult.Ready -> {
            when (val providerResult = queueProvider.enqueue(prepared.request)) {
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
    }
}
