package io.dataloom.runtime.queue

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
        require(command.request.commandId.value.isNotBlank()) {
            "Apple retry-administration receipt command id must not be blank."
        }
    }

    fun matches(other: AuthorizedRetryAdministrationCommand): Boolean = command == other
}
