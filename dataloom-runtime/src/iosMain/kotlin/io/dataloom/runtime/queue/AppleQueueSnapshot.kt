package io.dataloom.runtime.queue

import io.dataloom.api.error.Recoverability
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.time.DataLoomInstant

/** Complete in-memory representation of one Apple durable queue snapshot. */
internal data class AppleQueueSnapshot(
    val entries: MutableMap<String, QueueEntry> = linkedMapOf(),
    val retryAdministrationReceipts: MutableMap<String, AppleRetryAdministrationReceipt> =
        linkedMapOf(),
)

/**
 * Durable idempotency receipt written in the same snapshot as an administrative
 * queue mutation.
 */
internal data class AppleRetryAdministrationReceipt(
    val command: AuthorizedRetryAdministrationCommand,
    val appliedAt: DataLoomInstant,
) {
    init {
        require(command.effectiveRecoverability == Recoverability.RECOVERABLE) {
            "Apple retry-administration receipt must be effectively recoverable."
        }
        require(
            appliedAt.epochMilliseconds >= command.request.requestedAt.epochMilliseconds,
        ) {
            "Apple retry-administration receipt must not predate its command request."
        }
    }

    fun matches(other: AuthorizedRetryAdministrationCommand): Boolean = command == other
}
