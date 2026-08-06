package io.dataloom.consumer

import io.dataloom.api.queue.QueueIdempotentAdmissionProvider
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.queue.QueueProvider

/** Compile-only inspection of the additive idempotent queue admission SPI. */
internal fun inspectQueueIdempotentAdmissionProvider(
    provider: QueueProvider,
    result: QueueIdempotentAdmissionResult,
): String {
    val supportsAdmission = provider is QueueIdempotentAdmissionProvider
    val status = when (result) {
        is QueueIdempotentAdmissionResult.Accepted -> "accepted"
        is QueueIdempotentAdmissionResult.AlreadyAccepted -> "already-accepted"
        is QueueIdempotentAdmissionResult.IdentityConflict -> "identity-conflict"
    }
    result.queueEntryId.value
    result.currentState.name
    result.toString()
    return "$supportsAdmission:$status"
}
